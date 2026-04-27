package com.sentinel.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.api.model.ReviewRequest;
import com.sentinel.api.model.SentinelChunk;
import com.sentinel.api.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitingFlowTest {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFlowTest.class);

    // 1. Spin up REAL Redis to test the true algorithmic behavior
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
    		DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
        // Disable Ollama auto-configuration since we are mocking the AI
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:9999"); 
    }

    @LocalServerPort
    private String port;

    @Autowired
    private ObjectMapper objectMapper;

    // 2. MOCK the expensive AI service. We only care about the gateway logic!
    @MockBean
    private AnalysisService analysisService;

    /**
     * POSITIVE & NEGATIVE TEST:
     * Tests that a specific tenant can make exactly 5 requests, 
     * and the 6th request is instantaneously rejected by the Handler.
     */
    @Test
    void testTenantRateLimiting_EnforcesQuotaAndBlocksExcess() throws Exception {
        WebSocketClient client = new ReactorNettyWebSocketClient();
        URI url = URI.create("ws://localhost:" + port + "/ws/analyze");
        
        String tenantId = "ACME_CORP_TEST";
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Sentinel-Tenant-ID", tenantId);

        ReviewRequest request = new ReviewRequest("Rate Limit Test", "Security", 1);
        String jsonRequest = objectMapper.writeValueAsString(request);

        // Set up the Mock AI to return a successful stream instantly
        when(analysisService.analyze(any())).thenReturn(
            Flux.just(new SentinelChunk("MOCK COMPLETE", 
            		SentinelChunk.ChunkType.ANALYSIS_COMPLETE, System.currentTimeMillis()))
        );

        // --- THE POSITIVE TESTS (Requests 1 to 5) ---
        for (int i = 1; i <= 5; i++) {
            log.info("Executing Allowed Request #{}", i);
            Sinks.One<SentinelChunk> chunkSink = Sinks.one();

            client.execute(url, headers, session -> {
                Mono<Void> send = session.send(Mono.just(session.textMessage(jsonRequest)));
                Mono<Void> receive = session.receive()
                        .map(msg -> {
                            try {
                                return objectMapper.readValue(
                                		msg.getPayloadAsText(), SentinelChunk.class);
                            } catch (Exception e) {
                                return new SentinelChunk("Error", SentinelChunk.ChunkType.TEXT, 0);
                            }
                        })
                        .doOnNext(chunk -> {
                            chunkSink.tryEmitValue(chunk);
                            session.close().subscribe(); // Hang up immediately
                        })
                        .then();
                return send.then(receive);
            }).subscribe();

            // Assert that the request passed through the limiter and hit the (mocked) LLM
            StepVerifier.create(chunkSink.asMono())
                .assertNext(chunk -> {
                	assert chunk.type() == SentinelChunk.ChunkType.ANALYSIS_COMPLETE;
                			})
                .verifyComplete();
        }

        log.info("--- Quota Exhausted. Executing Blocked Request #6 ---");

        // --- THE NEGATIVE TEST (Request 6) ---
        Sinks.One<SentinelChunk> rejectionSink = Sinks.one();

        client.execute(url, headers, session -> {
            Mono<Void> send = session.send(Mono.just(session.textMessage(jsonRequest)));
            Mono<Void> receive = session.receive()
                    .map(msg -> {
                        try {
                            return objectMapper.readValue(msg.getPayloadAsText(), SentinelChunk.class);
                        } catch (Exception e) {
                            return new SentinelChunk("Error", SentinelChunk.ChunkType.TEXT, 0);
                        }
                    })
                    .doOnNext(chunk -> {
                        rejectionSink.tryEmitValue(chunk);
                        session.close().subscribe(); 
                    })
                    .then();
            return send.then(receive);
        }).subscribe();

        // Assert that the Handler short-circuited the pipeline and emitted the Rejection Chunk
        StepVerifier.create(rejectionSink.asMono())
            .assertNext(chunk -> {
                log.info("Received Expected Rejection: {}", chunk.content());
                assert chunk.type() == SentinelChunk.ChunkType.RATE_LIMIT_EXCEEDED;
            })
            .verifyComplete();
    }
}