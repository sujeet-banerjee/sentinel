package com.sentinel.api.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = "com.sentinel.api")
public class SentinelApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(SentinelApiApplication.class, args);
	}

}
