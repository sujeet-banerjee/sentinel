package com.sentinel.api;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.sentinel.api.service.RateLimiterService;

/*
 * We explicitly point to the class here, to avoid error during test-run:
 * [ERROR] Errors:
   [ERROR]  SentinelApiApplicationTests » IllegalState Unable to find a 
   			@SpringBootConfiguration by searching packages upwards from 
   			the test. You can use @ContextConfiguration, 
   			@SpringBootTest(classes=...) or other Spring Test supported 
   			mechanisms to explicitly declare the configuration classes 
   			to load. Classes annotated with @TestConfiguration are not considered.
 */
@Testcontainers
@SpringBootTest(
		classes = SentinelApiApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class SentinelApiApplicationTests {
	
	@SuppressWarnings("resource")
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
	
	/**
     * @since Day-9. Required due to the introduction of application.yml 
     * (resolving application.properties).
     */
    @MockBean
    private VectorStore vectorStore;
    
    /**
     * @since Day-9. Required due to the introduction of application.yml 
     * (resolving application.properties).
     */
    @MockBean 
    private ReactiveStringRedisTemplate redisTemplate;
	
	@Autowired
	private WebTestClient webTestClient;
	
	@Autowired
	private RateLimiterService ratelimiterService;

	@Test
	void contextLoads() {
	    // Basic check to ensure the Spring context starts up
		System.out.println("======== STARTING =========");
	}
	
	@Test
    void healthCheckShouldReturnOnline() {
        webTestClient.get().uri("/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo("SENTINEL ENGINE: ONLINE");
    }

}
