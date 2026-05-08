package com.sentinel.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class ConversationalMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationalMemoryService.class);
    
    private final ReactiveStringRedisTemplate redisTemplate;
    
    public ConversationalMemoryService(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Appends the interaction to the tenant's session list, trims it, and resets TTL.
     */
    public Mono<Void> saveInteraction(String tenantId, 
    		String sessionId, String userPrompt, String aiResponse) {
        String key = String.format(
        		Constants.KEY_TMPL_SESSION_MEM_KEY, tenantId, sessionId);
        
        String formattedUser = "USER: " + userPrompt;
        String formattedAi = "SENTINEL AI: " + aiResponse;

        return redisTemplate.opsForList().rightPushAll(key, formattedUser, formattedAi)
            .then(redisTemplate.opsForList().trim(key, -Constants.MAX_HISTORY_MESSAGES, -1))
            .then(redisTemplate.expire(key, Constants.SESSION_TTL))
            .doOnSuccess(v -> log.debug(
            	"[TENANT: {} | SESSION: {}] Memory updated and trimmed.", 
            		tenantId, sessionId))
            .then();
    }

    /**
     * Fetches the formatted conversation history for prompt injection.
     */
    public Mono<String> getHistory(String tenantId, String sessionId) {
    	String key = String.format(
        		Constants.KEY_TMPL_SESSION_MEM_KEY, tenantId, sessionId);
        
        return redisTemplate.opsForList().range(key, 0, -1)
            .collectList()
            .map(list -> {
                if (list.isEmpty()) {
                    return ""; // No history yet
                }
                
                StringBuilder sb = new StringBuilder("--- CONVERSATION HISTORY ---\n");
                sb.append(String.join("\n\n", list));
                sb.append("\n--------------------------\n\n");
                return sb.toString();
            });
    }
}