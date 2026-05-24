package com.sentinel.api.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import io.github.bucket4j.distributed.AsyncBucketProxy;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "sentinel.ratelimit.strategy", havingValue = "bucket")
public class RateLimiterServiceTokenBucket implements RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterServiceTokenBucket.class);
    
    private final LettuceBasedProxyManager<byte[]> proxyManager;
    
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
    
    private static final BucketConfiguration bucketConfiguration = BucketConfiguration.builder()
        .addLimit(limit -> limit
                .capacity(MAX_REQUESTS_PER_MINUTE)
                .refillIntervally(MAX_REQUESTS_PER_MINUTE, Duration.ofMinutes(1))
        )
        .build();

    public RateLimiterServiceTokenBucket(LettuceConnectionFactory redisConnectionFactory) {
        log.info("INITIALIZING RATE LIMITER: Token-Bucket Strategy (Bucket4j)");
        RedisClient redisClient = (RedisClient) redisConnectionFactory.getNativeClient();
        
        // Updated builder syntax using Bucket4jLettuce
        this.proxyManager = Bucket4jLettuce.casBasedBuilder(redisClient)
                .expirationAfterWrite(
                		ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                				Duration.ofMinutes(2)))
                .build();
        
    }

    @Override
    public Mono<Pair<Boolean, Long>> isAllowed(String tenantId, String sessionId) {
    	// TODO make a provision for injecting KeyBuilder, 
    	// and invoke KeyBuilder.build(tenantId)
    	
    	/*
    	 *  DO NOT append sessionId: else it will be a new key per session,
    	 *  making teh ratelimiter useless!
    	 */
        String bucketKey = String.format("rate_limit:rigid:tenant:%s", 
        		tenantId);
        
        return Mono.fromFuture(() -> {
            AsyncBucketProxy bucket = proxyManager.asAsync().builder().build(
            		bucketKey.getBytes(), bucketConfiguration);
            /*
             *  Advanced Bucket4j API: Returns detailed consumption 
             *  math instead of just boolean. We need the math to respond 
             *  the client with the 'Retry-After' duration (long).
             */
            return bucket.tryConsumeAndReturnRemaining(1);
        }).map(probe -> {
            if (probe.isConsumed()) {
                // Tokens were available. Allowed.
                return Pair.of(true, 0L);
            } else {
                // Tokens exhausted. Calculate exact seconds until next token generation.
                long waitSeconds = TimeUnit.NANOSECONDS.toSeconds(
                		probe.getNanosToWaitForRefill());
                
                // Edge case: if sub-second, ensure we at least tell them to wait 1 second
                long retryAfter = Math.max(1L, waitSeconds);
                
                log.warn("[Rate-Limiter][TENANT: {}] Token Bucket exhausted. Retry-After: {}s", 
                    tenantId, sessionId, retryAfter);
                
                return Pair.of(false, retryAfter);
            }
        });
    }
}