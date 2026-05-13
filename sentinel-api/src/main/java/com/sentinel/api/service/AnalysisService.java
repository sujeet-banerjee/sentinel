package com.sentinel.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;

import com.sentinel.api.model.AnalysisContext;
import com.sentinel.api.model.ReviewRequest;
import com.sentinel.api.model.SentinelChunk;
import com.sentinel.api.model.SentinelChunk.ChunkType;
import com.sentinel.api.model.TokenProcessingState;

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
	private final ConversationalMemoryService memoryService;
	
	private static final String SYST_PROMPT_TEMPLATE = 
			"You are Sentinel, an expert Software Architect. "
			//
			+ "Critique the following architecture focusing on %s. "
			+ "1. Use standard Markdown for text. "
			+ "2. If illustrating a flow or structure, "
			+ "you MUST use a Mermaid.js block: ```mermaid [code] ```. "
			+ "3. Always begin with 'SENTINEL ANALYSIS STARTING...'. "
			+ "4. Always conclude with the exact string: 'ANALYSIS COMPLETE'. \n\n "
			+ "%s \n\n" // Memory
			+ "CURRENT REQUEST: %s";

	private static final int MAX_WINDOW_SIZE = 64;
	
	/**
	 * 
	 * @param chatModel
	 * @param memoryService
	 */
	public AnalysisService(OllamaChatModel chatModel, 
			ConversationalMemoryService memoryService) {
        this.chatModel = chatModel;
		this.memoryService = memoryService;
    }

	
	/**
     * The Master Pipeline Coordinator.
     * Reads like a high-level sequence diagram.
     */
    public Flux<SentinelChunk> analyze(ReviewRequest request) {
    	/*
         *  Flux.defer ensures every new request gets 
         *  its own fresh state variables.
         *  
         *  If 500 users connect to your Sentinel Engine at the same time, 
         *  Flux.defer() ensures 500 separate AnalysisContext instances are created. 
         *  No data bleeding between users.
         */
        return Mono.deferContextual(ctx -> initializeContext(ctx, request))
                .flatMap(this::enrichWithHistory)
                .map(this::buildSystemPrompt)
                .flatMapMany(this::executeInferenceStream);
    }
    
    // --- PIPELINE COMPONENTS --- //

    private Mono<AnalysisContext> initializeContext(reactor.util.context.ContextView ctx, ReviewRequest request) {
        String tenantId = ctx.getOrDefault("TENANT_ID", "UNKNOWN_TENANT");
        String sessionId = ctx.getOrDefault("SESSION_ID", "UNKNOWN_SESSION");
        return Mono.just(new AnalysisContext(tenantId, sessionId, request, null, null));
    }

    private Mono<AnalysisContext> enrichWithHistory(AnalysisContext context) {
        return memoryService.getHistory(context.tenantId(), context.sessionId())
                .map(context::withHistory);
    }

    private AnalysisContext buildSystemPrompt(AnalysisContext context) {
        String prompt = String.format(
                SYST_PROMPT_TEMPLATE, 
                context.request().focusArea(), 
                context.history(), 
                context.request().content()
        );
        
        log.info("[TENANT: {} | SESSION: {}] Commencing Review. History size: {} chars", 
                context.tenantId(), context.sessionId(), context.history().length());
                
        return context.withPrompt(prompt);
    }

    private Flux<SentinelChunk> executeInferenceStream(AnalysisContext context) {
        return Flux.defer(() -> {
            // Instantiate state safely inside the defer block for thread isolation
            TokenProcessingState state = new TokenProcessingState();

            return chatModel.stream(new Prompt(context.finalPrompt()))
                    .map(ChatResponse::getResult)
                    .map(result -> result.getOutput().getText())
                    .filter(text -> text != null && !text.isEmpty())
                    .map(state::processToken)
                    .doOnComplete(() -> commitMemoryAsync(context, state.getCapturedMemory()));
        });
    }

    private void commitMemoryAsync(AnalysisContext context, String capturedMemory) {
        log.debug("[TENANT: {}] Stream complete. Committing to memory.", context.tenantId());
        memoryService.saveInteraction(
                context.tenantId(), 
                context.sessionId(), 
                context.request().content(), 
                capturedMemory
        ).subscribe(); // Fire and forget
    }
}