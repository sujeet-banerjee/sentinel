package com.sentinel.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.stereotype.Service;

import com.sentinel.api.model.ReviewRequest;
import com.sentinel.api.model.SentinelChunk;
import com.sentinel.api.model.SentinelChunk.ChunkType;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The core domain service orchestrating the Generative AI architectural review process.
 * * <p><b>Main Purpose:</b></p>
 * This service bridges the application layer and the AI inference engine. It takes an inbound 
 * {@link com.sentinel.api.model.ReviewRequest}, applies strict system prompts, and manages 
 * the lifecycle of the reactive LLM token stream. 
 * * <p><b>Encapsulated Details:</b></p>
 * <ul>
 * <li><b>Context Propagation:</b> Safely extracts multitenancy identifiers (e.g., {@code X-Sentinel-Tenant-ID}) 
 * from the Reactor {@link reactor.util.context.Context}, ensuring tenant isolation without 
 * polluting the method signatures.</li>
 * <li><b>Thread Isolation:</b> Wraps the entire generation pipeline in {@link reactor.core.publisher.Flux#defer()}, 
 * guaranteeing that local state variables (like trigger flags) are instantiated uniquely per 
 * WebSocket session and are immune to multi-threading race conditions.</li>
 * <li><b>Memory Optimization (Sliding Window):</b> Implements a highly optimized, capped-capacity 
 * {@link java.lang.StringBuilder} to track multi-token phrases (e.g., "ANALYSIS COMPLETE"). 
 * This O(1) space complexity algorithm prevents unbounded heap growth and protects against 
 * Out-Of-Memory (OOM) errors during massive LLM responses.</li>
 * <li><b>DTO Mapping:</b> Transforms the raw {@link org.springframework.ai.chat.model.ChatResponse} 
 * into a structured stream of {@link com.sentinel.api.model.SentinelChunk} objects.</li>
 * </ul>
 */
@Service
public class AnalysisService {
	private static final Logger log = LoggerFactory.getLogger(
			AnalysisService.class);
	
	/**
	 * Auto injected through contructor injection
	 */
	private final OllamaChatModel chatModel;
	
	private static final String SYST_PROMPT_TEMPLATE = 
			"You are an expert Software Architect. "
			+ "Critique the following architecture focusing on %s. "
			+ "1. Use standard Markdown for text. "
			+ "2. If illustrating a flow or structure, "
			+ "you MUST use a Mermaid.js block: ```mermaid [code] ```. "
			+ "3. Always begin with 'SENTINEL ANALYSIS STARTING...'. "
			+ "4. Always conclude with the exact string: 'ANALYSIS COMPLETE'. \n\n %s";

	private static final int MAX_WINDOW_SIZE = 64;
	
	public AnalysisService(OllamaChatModel chatModel) {
        this.chatModel = chatModel;
    }

	
public Flux<SentinelChunk> analyze(ReviewRequest request) {
    	
    	String systemPrompt = String.format(
    			SYST_PROMPT_TEMPLATE, request.focusArea(), 
    			request.content());

        /*
         *  Flux.defer ensures every new request gets 
         *  its own fresh state variables.
         *  
         *  If 500 users connect to your Sentinel Engine at the same time, 
         *  Flux.defer() ensures 500 separate StringBuilder instances are created. 
         *  No data bleeding between users.
         */
        return Mono.deferContextual(
        		ctx -> Mono.just(ctx.getOrDefault(Constants.TENANT_ID, 
        				Constants.UNKNOWN_TENANT)))
                .flatMapMany(tenantId -> Flux.defer(() -> {
            
            // The Memory Buffer to stitch fragments together
            //StringBuilder conversationBuffer = new StringBuilder();
            StringBuilder slidingWindow = new StringBuilder(MAX_WINDOW_SIZE * 2);
            
         // 2. We now have absolute context of WHO is requesting this analysis
            // In Week 3, we will use this tenantId to filter PGVector RAG results!
            log.info("[TENANT: {}] Commencing Architectural Review for focus area: {}", 
            		tenantId, request.focusArea());
            
            /*
             *  State flags to ensure we only emit the signal ONCE.
             *  Array used for access from lambda (.map)
             */
            boolean[] diagramTriggered = {false};
            boolean[] completeTriggered = {false};

            return chatModel.stream(new Prompt(systemPrompt))
                    .map(ChatResponse::getResult)
                    .map(result -> result.getOutput().getText())
                    .filter(text -> text != null && !text.isEmpty())
                    .map(token -> {
                        // 1. Accumulate the fragments into the memory buffer
                    	slidingWindow.append(token);
                    	
                    	// 2. Truncate from the front to maintain the sliding window
                        if (slidingWindow.length() > MAX_WINDOW_SIZE) {
                            slidingWindow.delete(0, slidingWindow.length() - MAX_WINDOW_SIZE);
                        }
                        String memory = slidingWindow.toString();
                        
                        ChunkType type = ChunkType.TEXT;
                        
                        // 3. Check the accumulated memory, not the isolated token
                        if (!diagramTriggered[0] && memory.contains("```mermaid")) {
                            type = ChunkType.DIAGRAM_START;
                            diagramTriggered[0] = true; // Lock it so it only fires once
                        } 
                        else if (!completeTriggered[0] && memory.contains("ANALYSIS COMPLETE")) {
                            type = ChunkType.ANALYSIS_COMPLETE;
                            completeTriggered[0] = true; // Lock it so it only fires once
                        }
                        
                        // 4. Emit the original token, but with the intelligent metadata attached
                        return new SentinelChunk(token, type, System.currentTimeMillis());
                    });
        }));
    }
}