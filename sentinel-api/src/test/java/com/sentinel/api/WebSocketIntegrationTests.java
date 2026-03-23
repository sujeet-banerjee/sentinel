package com.sentinel.api;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.api.model.ReviewRequest;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;

@SpringBootTest(
		classes = SentinelApiApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class WebSocketIntegrationTests {
	
	private static final Logger log = LoggerFactory.getLogger(
			WebSocketIntegrationTests.class);

    @LocalServerPort
    private String port;
    
    /**
     * We use the same mapper as the app for consistency
     */
    @Autowired
    private ObjectMapper objectMapper; 

    /**
     * TEST: Verify the WebSocket Echo Highway
     * CONTEXT: We open a real connection to the running server, send an 
     * architecture string, and wait for the Sentinel to echo it back.
     */
    @Test
    void testWebSocketEcho() throws InterruptedException {
        WebSocketClient client = new ReactorNettyWebSocketClient();
        URI url = URI.create("ws://localhost:" + port + "/ws/analyze");
        java.util.concurrent.BlockingQueue<String> queue = new java.util.concurrent.LinkedBlockingQueue<>();

        // We capture the 'Disposable' so we can manually kill the connection if needed
        client.execute(url, session -> 
            session.send(Mono.just(session.textMessage("Architecture Plan V1")))
                .thenMany(session.receive())
                .take(1) // IMPORTANT: Tell the stream to stop after 1 message
                .map(msg -> msg.getPayloadAsText())
                .doOnNext(text -> queue.offer(text))
                .then(session.close()) // IMPORTANT: Explicitly close the socket
        ).subscribe(); 

        // Wait for the response
        String response = queue.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        log.info("Response (after long poll): {}", response);
        org.junit.jupiter.api.Assertions.assertEquals("SENTINEL ANALYSIS STARTING...", response);
        
        
        // Small sleep to let the shutdown hook breathe
        Thread.sleep(1000); 
    }
    
    /**
     * TEST: AI Analysis Stream
     * CONTEXT: Sends a JSON ReviewRequest and expects a stream of 5 messages 
     * back from the AnalysisService.
     */
    @Test
    void testAIAnalysisStreaming() throws Exception {
        WebSocketClient client = new ReactorNettyWebSocketClient();
        URI url = URI.create("ws://localhost:" + port + "/ws/analyze");

        // 1. Prepare our structured JSON request
        String focusArea = "Security";
		ReviewRequest request = new ReviewRequest("App -> DB", focusArea, 1);
        String jsonRequest = objectMapper.writeValueAsString(request);
        
        // Create a "Many" Sink to capture the stream of strings
        Sinks.Many<String> replaySink = Sinks.many().replay().all();

        // 2. Execute the WebSocket session
        Mono<Void> connection = client.execute(url, session -> {
            // Send the JSON
            Mono<Void> send = session.send(Mono.just(session.textMessage(jsonRequest)));
            
            // Receive the stream of critiques
            Mono<Void> receive = session.receive()
                .map(msg -> msg.getPayloadAsText())
                .doOnNext(text -> replaySink.tryEmitNext(text)) // Push into the sink
                .then(); // Return Mono<Void> to signal receive loop completion

            return send.then(receive);
        });
        
        // 3. Start the connection in the background
        connection.subscribe();

        /*
         * StepVerifier: The "Chronometer" of Reactive Tests.
         * It will subscribe to the flux and check every single emission.
         */
        // 4. Run StepVerifier on the Sink's Flux
        StepVerifier.create(replaySink.asFlux())
            .expectNext("SENTINEL ANALYSIS STARTING...")
            .expectNextMatches(text -> text.contains(focusArea))
            .expectNextCount(2)
            .expectNext("ANALYSIS COMPLETE.")
            .thenCancel() // Important: Stop the sink so the test finishes
            .verify(Duration.ofSeconds(10));
    }
}