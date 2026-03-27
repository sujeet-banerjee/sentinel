package com.sentinel.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;

import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.api.model.ReviewRequest;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.util.logging.Level;

import reactor.netty.http.client.HttpClient;

//@Testcontainers
@SpringBootTest(
		classes = SentinelApiApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class WebSocketIntegrationTests {
	private static final Logger log = LoggerFactory.getLogger(
			WebSocketIntegrationTests.class);
	
	/**
     * Define the Ollama container and 
     * Automatically sets spring.ai.ollama.base-url
     */
    @Container 
    
    /*
     * 1. NOT USING: org.testcontainers.ollama.OllamaContainer
     * WHY:
     * The container is tuned to run on NVDIA gpus, and somehow did NOT
     * work locally on Windows (over WSL) - ERROR:
     * Caused by: com.github.dockerjava.api.exception.InternalServerErrorException: 
     * Status 500: {"message":"failed to create task for container: failed to create 
     * shim task: OCI runtime create failed: runc create failed: unable to start 
     * container process: error during container init: error running prestart hook #0: 
     * exit status 1, stdout: , stderr: Auto-detected mode as 
     * 'legacy'\nnvidia-container-cli: initialization error: WSL environment detected 
     * but no adapters were found"}
     * ----------------------------------
     * 
     * 2. COMMENTED OUT @ServiceConnection
     * WHY: 
     * ----------------------------------
     * 
     * 3. USED static block: configureProperties with @DynamicPropertySource
     * WHY: 
     * When you run a container in Testcontainers with .withExposedPorts(11434), 
     * Docker does not bind it to localhost:11434. If it did, you could never run 
     * two tests at once! Instead, it binds the container's internal 11434 to a 
     * random high-level port on your host (e.g., localhost:55012).
	 * ** Internal (Container): Ollama is listening on 11434.
	 * ** External (Host/Test): Docker is listening on 55012 and forwarding traffic 
	 *    to the container.
	 * Because the port is random, we cannot hardcode http://localhost:11434 in the 
	 * application.properties. This is why your test failed with "Connection Refused"—
	 * it was looking for a service on your laptop that wasn't there. 
	 * The @DynamicPropertySource is the bridge. It waits for the container to start,
	 * asks Docker "What random port did you give me?", and then injects that into 
	 * Spring's environment.
     *  ----------------------------------
     */
    
    static final GenericContainer ollama = new GenericContainer<>(
            // Replace 'your-github-user' with your actual GitHub username
            DockerImageName.parse(
            		"ghcr.io/sujeet-banerjee/sentinel/sentinel-ollama-llama3:latest")
        )
        .withExposedPorts(11434)
        .withEnv("OLLAMA_SKIP_GPU_CHECK", "true")
        .withReuse(true) // 1. Allow the container to persist
        .waitingFor(Wait.forHttp("/").forStatusCode(200))
        .withStartupTimeout(Duration.ofMinutes(20));
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
    	ollama.start();
    	log.info("Test Ollama Container started: {} --> ",
    			ollama.getContainerId(),
    			ollama.getContainerName());
    	
        // THIS IS THE KEY: We override the 'localhost:11434' default
        // with the actual dynamic port from Testcontainers.
        registry.add("spring.ai.ollama.base-url", 
            () -> "http://" + ollama.getHost() + ":" + ollama.getMappedPort(11434));
        
        // Force the Ollama client to wait for the slow CPU-based model load
        registry.add("spring.ai.ollama.chat.options.timeout", () -> "30m");
        
        // For Spring WebFlux, sometimes the underlying Netty needs this:
        registry.add("spring.codec.max-in-memory-size", () -> "10MB");
    }
	

    @LocalServerPort
    private String port;
    
    
    /**
     * We use the same mapper as the app for consistency
     */
    @Autowired
    private ObjectMapper objectMapper; 

    
    /**
     * TEST: AI Analysis Stream
     * CONTEXT: Sends a JSON ReviewRequest and expects a stream of 5 messages 
     * back from the AnalysisService.
     */
    @Test
    void testAIAnalysisStreaming() throws Exception {
    	// 1. Configure a Netty HttpClient with a long response timeout
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofMinutes(5));
        
        WebSocketClient client = new ReactorNettyWebSocketClient(httpClient);
        URI url = URI.create("ws://localhost:" + port + "/ws/analyze");

        // 1. Prepare our structured JSON request
        String focusArea = "Security";
		ReviewRequest request = new ReviewRequest("App -> DB", focusArea, 1);
        String jsonRequest = objectMapper.writeValueAsString(request);
        
        // Create a "Many" Sink to capture the stream of strings
        Sinks.Many<String> replaySink = Sinks.many().replay().all();

        // 2. Execute the WebSocket session
        Mono<Void> connection = client.execute(url, session -> {
            // MONO1: Send the JSON
            Mono<Void> send = session.send(Mono.just(session.textMessage(jsonRequest)));
            
            // MONO2: Receive the stream of critiques
            Mono<Void> receive = session.receive()
                .map(msg -> msg.getPayloadAsText())
                .log(" [AI-ST] ", Level.FINEST)
                .doOnNext(text -> replaySink.tryEmitNext(text)) // Push into the sink
                .doOnComplete(() -> {
                    log.info("DEBUG: WebSocket Stream Ended. Completing Sink.");
                    replaySink.tryEmitComplete(); // <--- THIS triggers the StepVerifier to finish
                })
                .doOnError(e -> {
                	log.error("DEBUG: WebSocket Error: " + e.getMessage(), e);
                    replaySink.tryEmitError(e);
                })
                .then(); // Return Mono<Void> to signal receive loop completion

            // First do MONO1 and then MONO2
            return send.then(receive);
        });
        
        // 3. Start the connection in the background
        Disposable disposable = connection.subscribe();

        /*
         * StepVerifier: The "Chronometer" of Reactive Tests.
         * It will subscribe to the flux and check every single emission.
         */
        // 4. Run StepVerifier on the Sink's Flux
        StepVerifier.create(replaySink.asFlux()
        		.take(Duration.ofMinutes(4))
        		// 1. Collect everything into a buffer 
        		// only for the first 5 minutes, so we join the fragmented words!
        		.collect(StringBuilder::new, StringBuilder::append) 
                .map(StringBuilder::toString))
            .expectSubscription()
            .assertNext(fullResponse -> {
                // 2. Now you can use standard string assertions
                log.info("FULL AI RESPONSE: {}", fullResponse);
                assert fullResponse.contains("SENTINEL ANALYSIS STARTING...");
                assert fullResponse.contains("ANALYSIS COMPLETE");
            })
            
            .expectComplete()
            .verify(Duration.ofMinutes(5));
        
        log.info("Step verifier done!");
        
        // 5. once the test passes, don't keep subscribing to the channel, 
        // so the test can finish 
        disposable.dispose();
        log.info("Client subscription disposed.");
		/*
		 * 
		 * 
		 * log.info("--- START THREAD AUDIT ---");
		 * Thread.getAllStackTraces().keySet().forEach(t -> {
		 * System.out.printf("Thread: %-20s | Daemon: %-5b | State: %s%n", t.getName(),
		 * t.isDaemon(), t.getState()); }); log.info("--- END THREAD AUDIT ---");
		 */
    }
    
    @AfterAll
    public static void teardown() {
    	ollama.close();
    }
}