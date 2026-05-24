package com.sentinel.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Enterprise Guardrail: Tenant-Isolated Reactive Rate Limiting
 * 
 * @Refactor
 * I originally built a Fixed-Window limiter. When we projected scale to 10K users, 
 * I realized we needed a Token Bucket to prevent dual-burst saturation. Instead of 
 * deleting my old code, I abstracted it behind a Strategy Interface and used Spring
 * @ConditionalOnProperty to enable zero-downtime canary testing of the new Bucket4j 
 * implementation.
 */
@Service
@ConditionalOnProperty(name = "sentinel.ratelimit.strategy", 
	havingValue = "rigid", matchIfMissing = true)
public class RateLimiterServiceRigidWindow implements RateLimiterService {
    
    private static final Logger log = LoggerFactory.getLogger(RateLimiterServiceRigidWindow.class);
    
    private final ReactiveStringRedisTemplate redisTemplate;
    
    /*
	 *  In a production app, this would be loaded dynamically from a DB per tenant.
	 *  We are hardcoding a strict limit of 5 requests per minute for this demonstration.
	 *  
	 *  TODO move to DB (config)
	 */
	/**
	 * @deprecated Use {@link Constants#MAX_REQUESTS_PER_MINUTE} instead
	 */
	private static final int MAX_REQUESTS_PER_MINUTE = Constants.MAX_REQUESTS_PER_MINUTE; 

    public RateLimiterServiceRigidWindow(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Implements Fixed-Window RateLimiting.
     * Atomically increments the tenant's usage counter in Redis.
     * @return Mono<Boolean> - True if allowed, False if quota exceeded.
     */
    @Override
    public Mono<Pair<Boolean, Long> > isAllowed(String tenantId, String sessionId) {
    	
    	// TODO make a provision for injecting KeyBuilder, 
    	// and invoke KeyBuilder.build(tenantId)
    	
    	/*
    	 *  DO NOT append sessionId: else it will be a new key per session,
    	 *  making teh ratelimiter useless!
    	 */
        String key = String.format("rate_limit:rigid:tenant:%s", 
        		tenantId);
        
       log.debug("[RATE-LIMITER] Checking the consumption of: {}", key);

        // Reactive atomic increment
        return redisTemplate.opsForValue().increment(key)
            .flatMap(currentCount -> {
            	log.debug("[RATE-LIMITER][TENANT: {}][Session {}] Request Count: {}", 
                		tenantId, sessionId, currentCount);
                if (currentCount == 1) {
                    // This is the first request in the window. Set the expiry clock.
                    return redisTemplate.expire(key, Duration.ofMinutes(1))
                        .thenReturn(Pair.of(true, 0L));
                }
                
                if (currentCount > Constants.MAX_REQUESTS_PER_MINUTE) {
                    log.warn("[RATE-LIMITER][TENANT: {}][Session {}] Rate limit exceeded. Count: {}", 
                		tenantId, sessionId, currentCount);
                    // Fetch the exact remaining TTL from Redis
                    return redisTemplate.getExpire(key)
                        .map(Duration::getSeconds)
                        .defaultIfEmpty(60L)
                        .map(ttl -> Pair.of(false, ttl));
                }
                
                return Mono.just(Pair.of(true, 0L)); // Allow
            });
    }
}