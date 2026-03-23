package com.sentinel.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

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

@SpringBootTest(
		classes = SentinelApiApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class SentinelApiApplicationTests {
	
	@Autowired
	private WebTestClient webTestClient;

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
