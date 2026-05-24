package com.sentinel.api.service;

import org.springframework.data.util.Pair;

import reactor.core.publisher.Mono;

/**
 * Enterprise Guardrail: Strategy Interface for Distributed Rate Limiting.
 * 
 * TODO make a provision for injecting KeyBuilder.
 * 
 * @since Day-10
 */
public interface RateLimiterService {
	
	/**
     * Non-blocking check to determine if a specific tenant session is within quota limits.
     * @param tenantId The unique identifier of the corporate tenant.
     * @param sessionId The specific user's WebSocket session ID.
     * @return Mono<Pair<Boolean, Long> > - True if allowed, False if quota exceeded.
     */
	Mono<Pair<Boolean, Long> > isAllowed(String tenantId, String sessionId);

}