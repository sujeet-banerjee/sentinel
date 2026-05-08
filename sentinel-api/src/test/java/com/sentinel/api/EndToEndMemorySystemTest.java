package com.sentinel.api;

import com.sentinel.api.model.ReviewRequest;
import com.sentinel.api.model.SentinelChunk;
import com.sentinel.api.service.AnalysisService;
import com.sentinel.api.service.ConversationalMemoryService;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test for the context memory
 */
@Testcontainers
@SpringBootTest
class EndToEndMemorySystemTest {

    private static final Logger log = LoggerFactory.getLogger(EndToEndMemorySystemTest.class);

    // 1. Spin up a real, isolated Redis container for the test
    @SuppressWarnings("resource") // RYUK will terminate the test containers.
	@Container
    static final GenericContainer<?> redis = new GenericContainer<>(
    		DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
        /* Note: We are NOT mocking Ollama. We are letting Spring 
         * connect to your real local Ollama container!
         */
        registry.add("spring.ai.ollama.base-url", () -> "http://localhost:11434");
    }

    @Autowired
    private AnalysisService analysisService;
    
    @Autowired
    private ConversationalMemoryService memoryService;

    @Test
    void llmShouldRememberContextFromPreviousInteraction() throws InterruptedException {
        String tenantId = "E2E_TENANT";
        String sessionId = "E2E_SESSION_" + System.currentTimeMillis();

        // ====================================================================
        // PROMPT 1: Inject the Secret Knowledge
        // ====================================================================
        ReviewRequest request1 = new ReviewRequest(
                "I am designing a new system. The secret codename for this project is 'PROJECT_OMEGA'. Please acknowledge.",
                "General",
                1
        );

        log.info("--- SENDING PROMPT 1 ---");
        Flux<SentinelChunk> response1 = analysisService.analyze(request1)
                .contextWrite(Context.of("TENANT_ID", tenantId, "SESSION_ID", sessionId));

        // Consume the entire stream to trigger the doOnComplete() memory save
        StepVerifier.create(response1)
                .thenConsumeWhile(chunk -> true) 
                .verifyComplete();

        // SRE TRIPWIRE: Give the async background thread 1 second to write to Redis
        Thread.sleep(1000); 

        // ====================================================================
        // PROMPT 2: Retrieve the Secret Knowledge
        // ====================================================================
        ReviewRequest request2 = new ReviewRequest(
                "What is the secret codename for the project I am designing?",
                "Memory Verification",
                1
        );

        log.info("--- SENDING PROMPT 2 ---");
        Flux<SentinelChunk> response2 = analysisService.analyze(request2)
                .contextWrite(Context.of("TENANT_ID", tenantId, "SESSION_ID", sessionId));

        // Collect all chunks of the second response into a single string
        final StringBuilder finalAnswer = new StringBuilder();
        
        StepVerifier.create(response2)
                .thenConsumeWhile(chunk -> {
                	if (chunk.content() != null) 
                    	finalAnswer.append(chunk.content());
                	return true;
                	})
                .verifyComplete();

        log.info("LLM FINAL ANSWER: {}", finalAnswer.toString());

        // ====================================================================
        // THE ASSERTION
        // ====================================================================
        assertThat(finalAnswer.toString())
                .containsIgnoringCase("PROJECT_OMEGA")
                .as("The LLM completely forgot the context from Prompt 1!");
        
        // ====================================================================
        // THE REDIS DATABASE VERIFICATION
        // ====================================================================
        // SRE Tripwire: Give the second prompt's async background thread time to write
        Thread.sleep(1000); 

        log.info("--- VERIFYING PHYSICAL REDIS MEMORY FOR SESSION: {} ---", sessionId);
        
        StepVerifier.create(memoryService.getHistory(tenantId, sessionId))
                .assertNext(history -> {
                    // 1. Verify Prompt/Response 1 is in the database
                    assertThat(history).contains("The secret codename for this project is 'PROJECT_OMEGA'");
                    
                    // 2. Verify Prompt/Response 2 is in the database
                    assertThat(history).contains("What is the secret codename for the project I am designing?");
                    assertThat(history).containsIgnoringCase("PROJECT_OMEGA");
                    
                    log.info("REDIS VERIFICATION SUCCESSFUL. Full History Retained:\n{}", history);
                })
                .verifyComplete();
    }
}