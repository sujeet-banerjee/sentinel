package com.sentinel.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;


/**
 * * The primary bootstrap class and entry point for the Sentinel API platform.
 * * <p><b>Main Purpose:</b></p>
 * Initializes the Spring WebFlux application context, triggering the auto-configuration 
 * of all reactive web components, AI model bindings (Ollama), and distributed caching (Redis) layers.
 * * <p><b>Encapsulated Details:</b></p>
 * <ul>
 * <li>Acts as the root configuration class for component scanning, ensuring all 
 * {@code @Service}, {@code @Configuration}, and {@code @Component} stereotypes are 
 * discovered and injected.</li>
 * <li>Embeds the Netty reactive web server startup sequence, binding the application 
 * to the configured host port and initializing the non-blocking event loop groups 
 * that drive the application's asynchronous I/O operations.</li>
 * </ul>
 * 
 * 
 * <p><b>The "Root Package" Rule</b></p>
 * <pre>
 * In Spring Boot, the location of your @SpringBootApplication class is the 
 * "Starting Point" for a process called Component Scanning.
 * How it works: Spring starts at the package where SentinelApiApplication lives 
 * and scans downwards into all sub-packages to find your @Service, @Component, 
 * and @Configuration classes.
 * 
 * Note: Since the main class is currently inside com.sentinel.api.config,
 * Spring is only looking inside that config folder. It is completely blind to
 * ArchitecturalReviewHandler because that is sitting in com.sentinel.api.service 
 * (which is a "sibling" folder, not a "child").
 * </pre>
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.sentinel.api")
public class SentinelApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(SentinelApiApplication.class, args);
	}

}
