package com.sentinel.api.service;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the Conversation Memory Persistence
 */
@Testcontainers
@SpringBootTest
class ConversationalMemoryServiceTest {
	private static final Logger log = LoggerFactory.getLogger(ConversationalMemoryServiceTest.class);

    // Spin up a fresh, real Redis instance for this test suite
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
    		DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
        // Mock the LLM url so Spring doesn't try to connect to a real Ollama instance during context load
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:9999");
    }
    
    /**
     * @since Day-9. Required due to the introduction of application.yml 
     * (resolving application.properties).
     */
    @MockBean
    private VectorStore vectorStore;
    
    @Autowired
    private ConversationalMemoryService memoryService;

    @Test
    void shouldSaveAndRetrieveFormattedHistory() {
        String tenantId = "TEST_TENANT";
        String sessionId = UUID.randomUUID().toString();

        // 1. Save a single interaction
        StepVerifier.create(memoryService.saveInteraction(
        		tenantId, sessionId, "Hello AI", "Hello Human"))
                .verifyComplete();

        // 2. Retrieve and assert the formatting
        StepVerifier.create(memoryService.getHistory(tenantId, sessionId))
                .assertNext(history -> {
                    assertThat(history).contains("--- CONVERSATION HISTORY ---");
                    assertThat(history).contains("USER: Hello AI");
                    assertThat(history).contains("SENTINEL AI: Hello Human");
                })
                .verifyComplete();
    }

    @Test
    void shouldEnforceSlidingWindowAndTrimOldMessages() {
        String tenantId = "TRIM_TENANT";
        String sessionId = UUID.randomUUID().toString();

        // The service is hardcoded to keep 10 messages (5 user/ai pairs)
        // We will push 15 pairs to force the LTRIM to trigger.
        for (int i = 1; i <= 15; i++) {
            memoryService.saveInteraction(
                    tenantId, 
                    sessionId, 
                    "Prompt " + i, 
                    "Response " + i
            ).block(); 
        }

        // Retrieve the history
        Mono<String> conversatinoHistory = memoryService.getHistory(tenantId, sessionId);
        conversatinoHistory.log().doFinally(logs -> 
        {
        	log.info("Redis Retrieved Conversation History: {}", logs);
        });
        
		StepVerifier.create(conversatinoHistory)
                .assertNext(history -> {
                    // It should NO LONGER contain Prompt 1 through 10
                    assertThat(history).doesNotContain("Prompt 1\n");
                    assertThat(history).doesNotContain("Prompt 6\n");
                    assertThat(history).doesNotContain("Response 10\n");
                    
                    // It SHOULD contain exactly the last 5 pairs (11 through 15)
                    assertThat(history).contains("Prompt 11");
                    assertThat(history).contains("Response 15");
                })
                .verifyComplete();
    }
}