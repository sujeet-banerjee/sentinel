package com.sentinel.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Enterprise Guardrail: Tenant-Isolated Reactive Rate Limiting
 */
@Service
public class RateLimiterService {
    
    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);
    
    private final ReactiveStringRedisTemplate redisTemplate;
    
    /*
     *  In a production app, this would be loaded dynamically from a DB per tenant.
     *  We are hardcoding a strict limit of 5 requests per minute for this demonstration.
     *  
     *  TODO move to DB (config)
     */
    private static final int MAX_REQUESTS_PER_MINUTE = 5; 

    public RateLimiterService(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Atomically increments the tenant's usage counter in Redis.
     * @return Mono<Boolean> - True if allowed, False if quota exceeded.
     */
    public Mono<Pair<Boolean, Long> > isAllowed(String tenantId) {
        String key = "rate_limit:tenant:" + tenantId;

        // Reactive atomic increment
        return redisTemplate.opsForValue().increment(key)
            .flatMap(currentCount -> {
                if (currentCount == 1) {
                    // This is the first request in the window. Set the expiry clock.
                    return redisTemplate.expire(key, Duration.ofMinutes(1))
                        .thenReturn(Pair.of(true, 0L));
                }
                
                if (currentCount > MAX_REQUESTS_PER_MINUTE) {
                    log.warn("[TENANT: {}] Rate limit exceeded. Count: {}", 
                		tenantId, currentCount);
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