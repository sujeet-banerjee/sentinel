package com.sentinel.api.service;

import java.util.logging.Level;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.sentinel.api.model.AnalysisContext;
import com.sentinel.api.model.ReviewRequest;
import com.sentinel.api.model.SentinelChunk;
import com.sentinel.api.model.TokenProcessingState;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
	
	/*
	 * Auto injected through contructor injection
	 */
	private final OllamaChatModel chatModel;
	private final ConversationalMemoryService memoryService;
	private final VectorStore vectorStore;
	
	private static final String SYST_PROMPT_TEMPLATE = 
            "You are Sentinel, an expert Enterprise Architecture AI. "
            + "Critique the following architecture focusing on %s. "
            + "1. Use standard Markdown for text. "
            + "2. If illustrating a flow or structure, "
            + "you MUST use a Mermaid.js block: ```mermaid [code] ```. "
            + "3. Always begin with 'SENTINEL ANALYSIS STARTING...'. "
            + "4. Always conclude with the exact string: 'ANALYSIS COMPLETE'. \n\n"
            + "=== CORPORATE GUIDELINES (Enforce these strictly) ===\n"
            + "%s \n\n" // <--- RAG CONTEXT INJECTION
            + "=== CONVERSATION HISTORY ===\n"
            + "%s \n\n" // <--- HISTORY INJECTION
            + "CURRENT REQUEST: %s";

	private static final String ALT_PROMPT = "No specific corporate guidelines "
			+ "found for this topic. Rely on industry best practices.";

	/**
	 * 
	 * @param chatModel
	 * @param memoryService
	 * @param vectorStore
	 */
	public AnalysisService(OllamaChatModel chatModel, 
			ConversationalMemoryService memoryService,
			VectorStore vectorStore) {
        this.chatModel = chatModel;
		this.memoryService = memoryService;
		this.vectorStore = vectorStore;
		
		checkServiceLoaded("chatModel", this.chatModel);
		checkServiceLoaded("memoryService", this.memoryService);
		checkServiceLoaded("vectorStore", this.vectorStore);
    }


	/**
	 * Sanity check!
	 * @param name
	 * @param obj
	 */
	private void checkServiceLoaded(String name, Object obj) {
		try {
			log.debug("Loaded {}: {}", name, obj.getClass().getSimpleName());
		} catch (Exception e) {
			log.error("NOT LOADED: {}", name);
		}
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
                .flatMap(this::enrichWithVectorData)
                .map(this::buildSystemPrompt)
                .flatMapMany(this::executeInferenceStream);
    }
    
    // --- PIPELINE COMPONENTS --- //

    private Mono<AnalysisContext> initializeContext(
    		reactor.util.context.ContextView ctx, 
    		ReviewRequest request) {
        String tenantId = ctx.getOrDefault("TENANT_ID", "UNKNOWN_TENANT");
        String sessionId = ctx.getOrDefault("SESSION_ID", "UNKNOWN_SESSION");
        log.debug("[TENANT: {}] Initializing Context...", tenantId);
        return Mono.just(new AnalysisContext(tenantId, 
        		sessionId, request, null, null, null));
    }

    private Mono<AnalysisContext> enrichWithHistory(AnalysisContext context) {
    	log.debug("[TENANT: {}] Fetching Conversational History...", 
    			context.tenantId());
        return memoryService.getHistory(context.tenantId(), context.sessionId())
                .map(context::withHistory);
    }

    private AnalysisContext buildSystemPrompt(AnalysisContext context) {
    	/*
    	 *  Handle case where Postgres returns nothing
    	 */
        String safeRagContext = context.ragContext() != null && 
        		!context.ragContext().isEmpty()  
        		? context.ragContext() 
                : ALT_PROMPT;
        String prompt = String.format(
                SYST_PROMPT_TEMPLATE, 
                context.request().focusArea(),
                // 1. Inject RAG
                safeRagContext,
                // 2. Inject History
                context.history(),
                // 3. Inject User Request
                context.request().content()
        );
        
        log.info("[TENANT: {} | SESSION: {}] Commencing RAG Review. "
        		+ "History size: {}, Vector Context size: {}", 
                context.tenantId(), context.sessionId(), 
                context.history().length(), safeRagContext.length());
        
        log.debug("[TENANT: {}] SystemMessage: {}", context.tenantId(), prompt);
                
        return context.withPrompt(prompt);
    }

    private Flux<SentinelChunk> executeInferenceStream(AnalysisContext context) {
    	log.debug("[TENANT: {}] Initiating inferencing...", 
    			context.tenantId());
        return Flux.defer(() -> {
            // Instantiate state safely inside the defer block for thread isolation
            TokenProcessingState state = new TokenProcessingState();

            return chatModel.stream(new Prompt(context.finalPrompt()))
                    .map(ChatResponse::getResult)
                    .map(result -> result.getOutput().getText())
                    .filter(text -> text != null && !text.isEmpty())
                    .map(state::processToken)
                    .doOnComplete(() -> commitMemoryAsync(context, 
                    		state.getCapturedMemory()));
        });
    }

    private void commitMemoryAsync(AnalysisContext context, String capturedMemory) {
        log.debug("[TENANT: {}] Stream complete. Committing to memory.", 
        		context.tenantId());
        memoryService.saveInteraction(
                context.tenantId(), 
                context.sessionId(), 
                context.request().content(), 
                capturedMemory
        ).subscribe(); // Fire and forget
    }
    
    /**
     * THE NON-BLOCKING BRIDGE: 
     * Offloads the blocking JDBC vector search to a dedicated background thread pool.
     */
    private Mono<AnalysisContext> enrichWithVectorData(AnalysisContext context) {
    	log.debug("[TENANT: {}] Querying vector-store for semantic context...", 
        		context.tenantId());
        return Mono.fromCallable(() -> {
            
        	log.debug("[TENANT: {}] Building VS search request... ", 
            		context.tenantId());
            // The actual Semantic Search against PGVector
            // TODO Add .filterExpression("tenant_id == '" + context.tenantId() + "'")
            SearchRequest request = SearchRequest.builder()
                    .query(context.request().content())
                    // TODO top-k should be configurable
                    .topK(2)
                    .build();
            
            log.debug("[TENANT: {}] Search req={} ",  context.tenantId(),
            		request);

            return vectorStore.similaritySearch(request).stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));
        })
        /*
         * Sentinel is built on Spring WebFlux (Reactive/Asynchronous). 
         * However, querying PostgreSQL via VectorStore is a blocking JDBC operation. 
         * If you execute a blocking database call on a reactive Netty thread, 
         * the entire server will instantly crash with a BlockHound or thread starvation
         * exception.
         * 
         * FIX: To fix this, we must wrap the Postgres query in Mono.fromCallable() 
         * and assign it to a dedicated background thread pool using 
         * Schedulers.boundedElastic().
         */
        .subscribeOn(Schedulers.boundedElastic()) // <--- CRITICAL FOR WEBFLUX
        .log("VECTOR STORE", Level.INFO)
        .map(context::withRagContext);
    }
}