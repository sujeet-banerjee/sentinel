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
import com.sentinel.api.model.SentinelChunk;

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
	
	
	/*
	 * Test constants
	 */
	/**
	 * 
	 */
	private static final int CONCURRENT_USERS = 4;
	
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
     * TEST 0: AI Analysis Stream
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
        
        /*
         *  Create a "Many" Sink to capture the stream of SentinelChunk
         *  instead of raw string (which would be JSON)
         */
        Sinks.Many<SentinelChunk> chunkReplaySink = Sinks.many().replay().all();

        // 2. Execute the WebSocket session
        Mono<Void> connection = client.execute(url, session -> {
            // MONO1: Send the JSON
            Mono<Void> send = session.send(Mono.just(session.textMessage(jsonRequest)));
            
            // MONO2: Receive the stream of critiques
            Mono<Void> receive = session.receive()
        		.map(msg -> {
                    try {
                        // 2.1 DESERIALIZE each incoming WebSocket message 
                    	// into a SentinelChunk
                        return objectMapper.<SentinelChunk>readValue(msg.getPayloadAsText(), 
                        		SentinelChunk.class);
                    } catch (Exception e) {
                        log.error("Failed to parse chunk: {}", msg.getPayloadAsText());
                        return new SentinelChunk("", SentinelChunk.ChunkType.TEXT, 0);
                    }
                })
                .log(" [AI-ST] ", Level.FINEST)
                // Push into the sink
                .doOnNext(chunk -> { 
                	chunkReplaySink.tryEmitNext(chunk);
                	if (chunk.type() == SentinelChunk.ChunkType.ANALYSIS_COMPLETE) {
                        log.debug("DEBUG: Received ANALYSIS_COMPLETE metadata! Completing Sink.");
                        chunkReplaySink.tryEmitComplete(); 
                    }
                }) 
                .doOnComplete(() -> {
                    log.info("DEBUG: WebSocket Stream Ended. Completing Sink.");
                    // THIS triggers the StepVerifier to finish
                    chunkReplaySink.tryEmitComplete(); 
                })
                .doOnError(e -> {
                	log.error("DEBUG: WebSocket Error: " + e.getMessage(), e);
                    chunkReplaySink.tryEmitError(e);
                })
                // Return Mono<Void> to signal receive loop completion
                .then(); 

            // First do MONO1 and then MONO2
            return send.then(receive);
        });
        
        // 3. Start the connection in the background
        Disposable disposable = connection.subscribe();

        /*
         * 4. Run StepVerifier on the Sink's Flux 
         * StepVerifier: The "Chronometer" of Reactive Tests.
         * It will subscribe to the flux and check every single emission.
         * (to validate the Metadata AND the Content).
         */
        StepVerifier.create(chunkReplaySink.asFlux())
            .expectSubscription()
            .recordWith(java.util.ArrayList::new) 
            // Consume until the stream completes
            .thenConsumeWhile(chunk -> true)
            /*
             * removed 'assertNext', as now there is nothing 
             * left in the stream
             */
            .consumeRecordedWith(allChunks -> {           
                // No-op here, logic moved to assertBelow
                log.debug("FULL AI RESPONSE: {}", allChunks);
            })
            .expectComplete()
            .verify(Duration.ofMinutes(10));
        log.info("Step verifier done!");
        
        /*
         * 5. Post-completion assertions on the recorded data
         */
        java.util.List<SentinelChunk> recorded = chunkReplaySink.
        		asFlux().collectList().block();
        
        // Stitch the content fragments together for assertion
        String fullText = recorded.stream()
                .map(SentinelChunk::content)
                .collect(java.util.stream.Collectors.joining(""));

        log.info("FULL RECONSTRUCTED TEXT: {}", fullText);
        // ASSERTION: Verify the "Fragment-Safe" detection logic worked
        boolean hasDiagramStart = recorded.stream()
                .anyMatch(c -> c.type() == SentinelChunk.ChunkType.DIAGRAM_START);
        
        boolean hasAnalysisComplete = recorded.stream()
                .anyMatch(c -> c.type() == SentinelChunk.ChunkType.ANALYSIS_COMPLETE);

        assert fullText.contains("SENTINEL ANALYSIS STARTING...");
        assert hasAnalysisComplete : "Metadata ANALYSIS_COMPLETE was never triggered!";
        
        // If the AI generated a diagram, verify the metadata was tagged
        if (fullText.contains("```mermaid")) {
            assert hasDiagramStart : "Mermaid block found but DIAGRAM_START metadata missing!";
        }
        
        // 5. once the test passes, don't keep subscribing to the channel, 
        // so the test can finish 
        disposable.dispose();
        log.info("Client subscription disposed.");

    }
    
    /**
     * TEST 1: Aggressive Memory Benchmark
     * CONTEXT: Forces the LLM to stream a massive, deterministic payload.
     * We discard the chunks immediately to isolate the memory footprint 
     * of the AnalysisService's internal buffer.
     */
    @Test
    void testMemoryConsumption_SlidingWindowBenchmark() throws Exception {
        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofMinutes(15));
        WebSocketClient client = new ReactorNettyWebSocketClient(httpClient);
        URI url = URI.create("ws://localhost:" + port + "/ws/analyze");

        // Force a massive, guaranteed long-running token stream
        ReviewRequest request = new ReviewRequest(
                "Count out loud from 1 to 3000, printing each number on a new line. "
                + "Do not stop or summarize.", 
                "Endurance Test", 1);
        String jsonRequest = objectMapper.writeValueAsString(request);
        
        Sinks.Many<SentinelChunk> chunkReplaySink = Sinks.many().replay().all();
        java.util.concurrent.atomic.AtomicInteger tokenCounter = 
        		new java.util.concurrent.atomic.AtomicInteger(0);

        // 1. Force GC and record baseline memory
        System.gc();
        Thread.sleep(500); // Give GC a moment to settle
        long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        log.info("--- MEMORY BENCHMARK START ---");
        log.info("Baseline Heap Usage: {} KB", memoryBefore / 1024);

        Mono<Void> connection = client.execute(url, session -> {
            Mono<Void> send = session.send(Mono.just(session.textMessage(jsonRequest)));
            Mono<Void> receive = session.receive()
                .map(msg -> {
                    try {
                        return objectMapper.<SentinelChunk>readValue(
                        		msg.getPayloadAsText(), SentinelChunk.class);
                    } catch (Exception e) {
                        return new SentinelChunk("", SentinelChunk.ChunkType.TEXT, 0);
                    }
                })
                .doOnNext(chunk -> {
                    chunkReplaySink.tryEmitNext(chunk);
                    if (chunk.type() == SentinelChunk.ChunkType.ANALYSIS_COMPLETE) {
                        chunkReplaySink.tryEmitComplete(); 
                    }
                })
                .doOnError(chunkReplaySink::tryEmitError)
                .then();
            return send.then(receive);
        });
        
        Disposable disposable = connection.subscribe();

        StepVerifier.create(chunkReplaySink.asFlux())
            .expectSubscription()
            // We DO NOT use recordWith() here. We want to let the chunks be Garbage Collected 
            // instantly so we only measure the AnalysisService buffer.
            .thenConsumeWhile(chunk -> {
                tokenCounter.incrementAndGet();
                return true; 
            })
            .verifyComplete();

        // 2. Force GC and record post-stream memory
        System.gc();
        Thread.sleep(500);
        long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        // Use KB instead of MB to see the exact footprint
        long memoryUsedInKb = (memoryAfter - memoryBefore) / 1024;
        
        log.info("Total tokens processed: {}", tokenCounter.get());
        log.info("Final Heap Usage: {} KB", memoryAfter / 1024);
        log.info("Approximate Pipeline Memory Delta: {} KB", memoryUsedInKb);
        log.info("--- MEMORY BENCHMARK END ---");

        disposable.dispose();
    }
    
    
    /**
     * TEST 2: Aggressive Multi-Threaded Concurrency (ExecutorService)
     * CONTEXT: Uses a CountDownLatch to freeze 4 independent threads 
     * at the starting line, firing them all at the exact same millisecond
     * to test true WebSocket collision and stream isolation.
     */
    @Test
    void testConcurrentWebSocketConnections_Executor() throws Exception {
        
        // 1. Classic Thread Pool
        java.util.concurrent.ExecutorService executor = 
        		java.util.concurrent.Executors.newFixedThreadPool(CONCURRENT_USERS);
        
        // 2. The "Starter Pistol" - Holds all threads until we say GO
        java.util.concurrent.CountDownLatch startLatch = 
        		new java.util.concurrent.CountDownLatch(1);
        
        // 3. The "Finish Line" - Waits for all threads to complete
        java.util.concurrent.CountDownLatch doneLatch = 
        		new java.util.concurrent.CountDownLatch(CONCURRENT_USERS);
        
        java.util.concurrent.atomic.AtomicInteger successCount = 
        		new java.util.concurrent.atomic.AtomicInteger(0);

        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofMinutes(15));
        WebSocketClient client = new ReactorNettyWebSocketClient(httpClient);
        URI url = URI.create("ws://localhost:" + port + "/ws/analyze");

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            final int userId = i;
            executor.submit(() -> {
                try {
                    // Unique request for each thread
                    ReviewRequest request = new ReviewRequest(
                            "Say a quick hello to user " + userId, "Greeting", userId);
                    String jsonRequest = objectMapper.writeValueAsString(request);

                    Sinks.Many<SentinelChunk> chunkSink = Sinks.many().replay().all();

                    // FREEZE THIS THREAD UNTIL THE LATCH IS RELEASED
                    startLatch.await(); 

                    // Execute WebSocket connection (Blocking inside the thread)
                    client.execute(url, session -> {
                        Mono<Void> send = session.send(
                        		Mono.just(session.textMessage(jsonRequest))
                        		.log(" -- AI TEST SEND -- "+ userId + " --", Level.FINEST)
                        		);
                        Mono<Void> receive = session.receive()
                            .map(msg -> {
                                try {
                                    return objectMapper.<SentinelChunk>readValue(
                                    		msg.getPayloadAsText(), SentinelChunk.class);
                                } catch (Exception e) {
                                    return new SentinelChunk("", SentinelChunk.ChunkType.TEXT, 0);
                                }
                            })
                            .log("-- AI TEST RECV -- "+userId+" --", Level.FINEST)
                            .doOnNext(chunk -> {
                                chunkSink.tryEmitNext(chunk);
                                if (chunk.type() == SentinelChunk.ChunkType.ANALYSIS_COMPLETE) {
                                	log.info("ANALYSIS_COMPLETE for the user {}", userId);
                                    chunkSink.tryEmitComplete();
                                    /*
                                     * 	THE FIX: Tell the client to explicitly hang up 
                                     *  the WebSocket. This triggers the completion of 
                                     *  session.receive(), which unblocks the thread!
                                     */
                                    session.close().subscribe();
                                }
                            })
                            .doOnError(chunkSink::tryEmitError)
                            .then();
                        
                        return send.then(receive);
                    })
                    // Force thread to wait for WS completion
                    .block(Duration.ofMinutes(10)); 

                    // If we reach here without timeout, the stream isolated perfectly
                    successCount.incrementAndGet();
                    log.info("User {} completed successfully.", userId);

                } catch (Exception e) {
                    log.error("User {} failed with error: {}", userId, e.getMessage());
                } finally {
                    // Mark this thread as finished
                    doneLatch.countDown();
                }
            });
        }

        log.info("--- THREADS ARMED. SIMULATING {} CONCURRENT USERS ---", CONCURRENT_USERS);
        long startTime = System.currentTimeMillis();
        
        // FIRE! All 10 threads hit the WebSocket endpoint simultaneously
        startLatch.countDown(); 

        // Wait up to 10 minutes for all threads to cross the finish line
        boolean allFinished = doneLatch.await(11, java.util.concurrent.TimeUnit.MINUTES);
        executor.shutdown();

        long duration = (System.currentTimeMillis() - startTime) / 1000;
        log.info("--- CONCURRENCY TEST COMPLETE ---");
        log.info("Finished in {} seconds. Successful streams: {}/{}", 
        		duration, successCount.get(), CONCURRENT_USERS);

        assert allFinished : "Test timed out before all threads completed!";
        assert successCount.get() == CONCURRENT_USERS : "One or more streams failed or leaked state!";
    }
    
    @AfterAll
    public static void teardown() {
    	ollama.close();
    }
}