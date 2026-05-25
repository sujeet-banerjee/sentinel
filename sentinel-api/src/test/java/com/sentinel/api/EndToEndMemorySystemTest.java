package com.sentinel.api;

import com.sentinel.api.model.ReviewRequest;
import com.sentinel.api.model.SentinelChunk;
import com.sentinel.api.service.AnalysisService;
import com.sentinel.api.service.ConversationalMemoryService;
import com.sentinel.api.service.RateLimiterService;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.util.Pair;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

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
    
    
    @SuppressWarnings({ "rawtypes", "resource" })
	/*
     * Define the Ollama container and 
     * Automatically sets spring.ai.ollama.base-url
     */
    @Container
    static final GenericContainer ollama = new GenericContainer<>(
            // TODO read from the DB / Config
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
    	
    	log.info("Test Redis Container started (Already): {} --> ", 
    			redis.getContainerId(), 
    			redis.getContainerName());
    	
    	/*
    	 * - - - OLLAMA Config - - -
    	 */
        // THIS IS THE KEY: We override the 'localhost:11434' default
        // with the actual dynamic port from Testcontainers.
        registry.add("spring.ai.ollama.base-url", 
            () -> "http://" + ollama.getHost() + ":" + ollama.getMappedPort(11434));
        
        // Force the Ollama client to wait for the slow CPU-based model load
        registry.add("spring.ai.ollama.chat.options.timeout", () -> "30m");
        
        // For Spring WebFlux, sometimes the underlying Netty needs this:
        registry.add("spring.codec.max-in-memory-size", () -> "10MB");
        
        /*
    	 * - - - REDIS Config - - -
    	 */
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    }
    
    /**
     *  Add this! This prevents Spring from trying to connect to a real Postgres DB.
     *  @since Day-9
     */
    @MockBean
    private VectorStore vectorStore;

    @Autowired
    private AnalysisService analysisService;
    
    @Autowired
    private ConversationalMemoryService memoryService;
    
    @Autowired
    private RateLimiterService rateLimiterService;

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

        log.info("--- [[SENDING PROMPT 1]] ---");
        log.info(request1.content());
        log.info("--- [/SENDING PROMPT 1/] ---");
        
        Flux<SentinelChunk> response1 = analysisService.analyze(request1)
                .contextWrite(Context.of("TENANT_ID", tenantId, "SESSION_ID", sessionId));

        // Consume the entire stream to trigger the doOnComplete() memory save
        StepVerifier.create(response1)
                .thenConsumeWhile(chunk -> true) 
                .verifyComplete();
        
        StepVerifier.create(memoryService.getHistory(tenantId, sessionId))
		        .assertNext(history -> {
		            log.info("Conv. History So far:\n{}\n\n", history);
		        })
		        .verifyComplete();

        log.info("Simulating 3 sec delay time to allow async Redis commit...");
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // ====================================================================
        // PROMPT 2: Retrieve the Secret Knowledge
        // ====================================================================
        ReviewRequest request2 = new ReviewRequest(
                "What is the secret codename for the project I am designing?",
                "Memory Verification",
                1
        );

        Flux<SentinelChunk> response2 = analysisService.analyze(request2)
                .contextWrite(Context.of("TENANT_ID", tenantId, "SESSION_ID", sessionId));
        log.info("--- [[SENDING PROMPT 2]] ---");
        log.info(request2.content());
        log.info("--- [/SENDING PROMPT 2/] ---");

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
        
        rateLimiterService.isAllowed(tenantId, sessionId)
	        .doOnNext(pair -> {
	            log.info("[RL={}] RateLimit details -> First: {}, Second: {}", 
	            		 rateLimiterService.getClass().getSimpleName(),
	                     pair.getFirst(), 
	                     pair.getSecond());
	        })
	        .subscribe();
        
        log.info("Simulating another 3 sec delay time to allow async Redis commit...");
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // ====================================================================
        // THE ASSERTION
        // ====================================================================
        
        /*
         * Redis memory verification.
         */
        log.info("--- VERIFYING PHYSICAL REDIS MEMORY FOR SESSION: {} ---", sessionId);
        StepVerifier.create(memoryService.getHistory(tenantId, sessionId))
                .assertNext(history -> {
                	log.info("Full Conv. History:\n{}", history);
                	
                    // 1. Verify Prompt/Response 1 is in the database
                    assertThat(history).contains(
                    		"The secret codename for this project is 'PROJECT_OMEGA'");
                    
                    // 2. Verify Prompt/Response 2 is in the database
                    assertThat(history).contains(
                    		"What is the secret codename for the project I am designing?");
                    assertThat(history).containsIgnoringCase("PROJECT_OMEGA");
                })
                .verifyComplete();
        
        /*
         * Final LLM response verification
         */
        assertThat(finalAnswer.toString())
                .containsIgnoringCase("PROJECT_OMEGA")
                .as("The LLM completely forgot the context from Prompt 1!");
        
    }
}