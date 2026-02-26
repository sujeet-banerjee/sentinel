package com.sentinel.api.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;


@RestController
public class HealthController {
    @GetMapping("/health")
    public Mono<String> health() {
        return Mono.just("SENTINEL ENGINE: ONLINE");
    }
}
