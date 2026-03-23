package com.sentinel.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * <pre>
 * The "Root Package" Rule
 * 
 * In Spring Boot, the location of your @SpringBootApplication class is the 
 * "Starting Point" for a process called Component Scanning.
 * How it works: Spring starts at the package where SentinelApiApplication lives 
 * and scans downwards into all sub-packages to find your @Service, @Component, 
 * and @Configuration classes.
 * 
 * The Problem: Since your main class is currently inside com.sentinel.api.config,
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
