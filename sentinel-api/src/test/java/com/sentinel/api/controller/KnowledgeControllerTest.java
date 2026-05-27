package com.sentinel.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.sentinel.api.service.KnowledgeIngestionService;

@Testcontainers
// RANDOM_PORT is crucial here: it starts the actual Netty web server for WebTestClient to hit
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class KnowledgeControllerTest {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeControllerTest.class);

    // ====================================================================
    // INFRASTRUCTURE: Identical to your Service Test to reuse containers
    // ====================================================================
    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
            .withEnv("POSTGRES_USER", "sentinel")
            .withEnv("POSTGRES_PASSWORD", "password")
            .withEnv("POSTGRES_DB", "sentinel_db")
            .withExposedPorts(5432);

    @SuppressWarnings({ "rawtypes", "resource" })
    @Container
    static final GenericContainer ollama = new GenericContainer<>(
            DockerImageName.parse("ghcr.io/sujeet-banerjee/sentinel/sentinel-ollama-llama3:latest"))
            .withExposedPorts(11434)
            .withEnv("OLLAMA_SKIP_GPU_CHECK", "true")
            .withReuse(true)
            .waitingFor(Wait.forHttp("/").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(20));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
        registry.add("spring.datasource.url", () -> 
            "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/sentinel_db");
        registry.add("spring.ai.ollama.base-url", () -> 
            "http://" + ollama.getHost() + ":" + ollama.getMappedPort(11434));
    }

    // ====================================================================
    // BEANS & MOCKS
    // ====================================================================
    
    @Autowired
    private WebTestClient webTestClient;

    /*
     * We use @SpyBean instead of @MockBean. 
     * @SpyBean wraps the REAL fully-functioning KnowledgeIngestionService.
     * It allows the data to actually save to Postgres, but gives us Mockito 
     * superpowers to verify method calls and arguments.
     */
    @SpyBean
    private KnowledgeIngestionService ingestionService;

    // ====================================================================
    // TESTS
    // ====================================================================

    @Test
    @DisplayName("Should return 202 Accepted and process file asynchronously")
    void shouldAcceptMultipartFileAndTriggerBackgroundIngestion() {
        log.info("--- STARTING WEBFLUX CONTROLLER TEST ---");

        String tenantId = "TEST_TENANT_X";
        
        // 1. Build the Reactive Multipart Body
        // We use the test-doc.txt you created earlier
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource(
        		"docs/gemm-architecture-guidelines.txt"))
               .filename("gemm-architecture-guidelines.txt");

        // 2. Execute the HTTP Request
        webTestClient.post()
                .uri("/api/v1/knowledge/ingest")
                .header("X-Sentinel-Tenant-ID", tenantId)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                // 3. ASSERT: The Controller immediately returned HTTP 202 Accepted
                .expectStatus().isAccepted()
                .expectBody().isEmpty();

        log.info("HTTP 202 received. Controller successfully detached the request.");

        // 4. ASSERT ASYNC BEHAVIOR:
        // Because the controller offloads to Schedulers.boundedElastic(), 
        // if we assert immediately, the test might fail because the thread hasn't started yet.
        // We use Mockito.timeout() to poll the SpyBean for up to 10 seconds.
        verify(ingestionService, timeout(10000).times(1))
                .ingestDocument(any(), any());
                
        log.info("Verified that background boundedElastic thread "
        		+ "successfully picked up the ETL job.");
    }
}