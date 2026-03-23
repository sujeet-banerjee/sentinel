package com.sentinel.api;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;


import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

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
     * TEST: Verify the WebSocket Echo Highway
     * CONTEXT: We open a real connection to the running server, send an 
     * architecture string, and wait for the Sentinel to echo it back.
     */
    /*
    @Test
    void testWebSocketEcho() {
        WebSocketClient client = new ReactorNettyWebSocketClient();
        URI url = URI.create("ws://localhost:" + port + "/ws/analyze");
        
        // Using a 'Sink' to capture the async data for the test thread
        Sinks.One<String> replySink = Sinks.one();

        Mono<Void> sessionMono = client.execute(url, session -> {
            // 1. Send the message
            Mono<Void> send = session.send(Mono.just(session.textMessage("Architecture Plan V1")));
            
            // 2. Receive the reply and push it into the sink
            Mono<Void> receive = session.receive()
                    .map(msg -> msg.getPayloadAsText())
                    .doOnNext(text -> replySink.tryEmitValue(text))
                    .then(); // complete the receive flux

            // Run both together
            return Mono.zip(send, receive).then();
        });

        // We MUST subscribe to start the actual connection
        sessionMono.subscribe();

        // Now we verify the sink, not the sessionMono
        StepVerifier.create(replySink.asMono())
            .expectNext("SENTINEL RECEIVED: Architecture Plan V1")
            .thenCancel()
            .verify(Duration.ofSeconds(10));
    }
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
        String response = queue.poll(10, java.util.concurrent.TimeUnit.SECONDS);
        log.info("Response (after long poll): {}", response);
        org.junit.jupiter.api.Assertions.assertEquals("SENTINEL RECEIVED: Architecture Plan V1", response);
        
        
        // Small sleep to let the shutdown hook breathe
        Thread.sleep(1000); 
    }
}