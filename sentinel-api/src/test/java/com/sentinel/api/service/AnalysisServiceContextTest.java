package com.sentinel.api.service;

import com.sentinel.api.model.ReviewRequest;
import com.sentinel.api.model.SentinelChunk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;


import java.util.List;

/**
 * @since Day 8
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceContextTest {

    @Mock
    private OllamaChatModel chatModel;
    
    
    @Test
    void shouldExtractTenantIdFromReactiveContext() {
        // 1. PURE JAVA MOCKS (Zero Annotations)
        ConversationalMemoryService memoryService = Mockito.mock(
        		ConversationalMemoryService.class);

        // 2. BULLETPROOF STUBBING
        Mockito.when(memoryService.getHistory(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just("--- MOCK HISTORY ---"));
        Mockito.when(memoryService.saveInteraction(Mockito.any(), 
        		Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());
        
        ChatResponse mockResponse = new ChatResponse(List.of(
        		new Generation(new AssistantMessage(" ANALYSIS COMPLETE"))));
        Mockito.when(chatModel.stream(Mockito.any(Prompt.class)))
                .thenReturn(Flux.just(mockResponse));

        // 3. MANUAL WIRING
        AnalysisService analysisService = new AnalysisService(
        		chatModel, memoryService);

        // 4. THE TEST EXECUTION
        ReviewRequest request = new ReviewRequest("Test", "Security", 1);
        
        Flux<SentinelChunk> result = analysisService.analyze(request)
                .contextWrite(Context.of(
                		/*
                		 * (key : value) pairs
                		 */
                		"TENANT_ID", "TEST_TENANT", 
                		"SESSION_ID", "TEST_SESSION"));
        StepVerifier.create(result)
                .assertNext(chunk -> {assert chunk.content() != null;})
                .verifyComplete();
        
        // 5. THE AUTOMATED ASSERTION (No eyeballs required)
        // This will instantly fail the GitHub Action if the wrong string is passed down!
        Mockito.verify(memoryService).getHistory(Mockito.eq("TEST_TENANT"), 
        		Mockito.eq("TEST_SESSION"));
        
        // Note: saveInteraction is async (doOnComplete), so we use a small timeout 
        // to let the background thread finish
        Mockito.verify(memoryService, Mockito.timeout(500)).saveInteraction(
                Mockito.eq("TEST_TENANT"), 
                Mockito.eq("TEST_SESSION"), 
                Mockito.anyString(), 
                Mockito.anyString()
        );
    }

    
    @Test
    void shouldFallbackToUnknownIfTenantContextIsMissing() {
        // 1. PURE JAVA MOCKS
        OllamaChatModel chatModel = Mockito.mock(OllamaChatModel.class);
        ConversationalMemoryService memoryService = Mockito.mock(
        		ConversationalMemoryService.class);

        // 2. BULLETPROOF STUBBING
        Mockito.when(memoryService.getHistory(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just("--- MOCK HISTORY ---"));
        Mockito.when(memoryService.saveInteraction(Mockito.any(), 
        		Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        ChatResponse mockResponse = new ChatResponse(
        		List.of(new Generation(new AssistantMessage(" ANALYSIS COMPLETE"))));
        Mockito.when(chatModel.stream(Mockito.any(Prompt.class)))
                .thenReturn(Flux.just(mockResponse));

        // 3. MANUAL WIRING
        AnalysisService analysisService = new AnalysisService(
        		chatModel, memoryService);

        // 4. THE TEST EXECUTION (No context provided)
        ReviewRequest request = new ReviewRequest("Test", "Security", 1);

        Flux<SentinelChunk> result = analysisService.analyze(request);

        StepVerifier.create(result)
                .assertNext(chunk -> {assert chunk.content() != null;})
                .verifyComplete();
        
     // 5. THE AUTOMATED ASSERTION (No eyeballs required)
        // This will instantly fail the GitHub Action if the wrong string is passed down!
        Mockito.verify(memoryService).getHistory(
        		Mockito.eq("UNKNOWN_TENANT"), 
        		Mockito.eq("UNKNOWN_SESSION"));
        
        // Note: saveInteraction is async (doOnComplete), so we use a small timeout 
        // to let the background thread finish
        Mockito.verify(memoryService, Mockito.timeout(500)).saveInteraction(
                Mockito.eq("UNKNOWN_TENANT"), 
                Mockito.eq("UNKNOWN_SESSION"),
                Mockito.anyString(), 
                Mockito.anyString()
        );
    }
}