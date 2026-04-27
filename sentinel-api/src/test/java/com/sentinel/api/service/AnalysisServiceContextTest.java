package com.sentinel.api.service;

import com.sentinel.api.model.ReviewRequest;
import com.sentinel.api.model.SentinelChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceContextTest {

    @Mock
    private OllamaChatModel chatModel;

    @InjectMocks
    private AnalysisService analysisService;

    @Test
    void shouldExtractTenantIdFromReactiveContext() {
        // 1. Prepare the dummy request
        ReviewRequest request = new ReviewRequest("Code Snippet", "Security", 1);
        String expectedTenantId = "ACME_CORP";

        // 2. Mock the AI to instantly return the completion token
        when(chatModel.stream(any(Prompt.class)))
	        .thenReturn(Flux.just(new ChatResponse(List.of(
	            new Generation(new AssistantMessage("ANALYSIS COMPLETE"))
	        ))));

        // 3. THE TEST: Subscribe to the service and inject the mock context
        Flux<SentinelChunk> stream = analysisService.analyze(request)
            // This simulates the ArchitecturalReviewHandler injecting the context
            .contextWrite(Context.of(Constants.TENANT_ID, expectedTenantId));

        // 4. Verify the reactive pipeline completes cleanly
        StepVerifier.create(stream)
            .expectSubscription()
            .assertNext(chunk -> {
                assert chunk.type() == SentinelChunk.ChunkType.ANALYSIS_COMPLETE;
                // If you watch your console during this test, you will see:
                // [TENANT: ACME_CORP] Commencing Architectural Review...
            })
            .verifyComplete();
    }
    
    @Test
    void shouldFallbackToUnknownIfTenantContextIsMissing() {
        ReviewRequest request = new ReviewRequest("Code Snippet", "Security", 1);

        when(chatModel.stream(any(Prompt.class)))
            .thenReturn(Flux.just(new ChatResponse(List.of(
            		 new Generation(new AssistantMessage("ANALYSIS COMPLETE")
            )))));

        // Notice we do NOT append .contextWrite() here
        Flux<SentinelChunk> stream = analysisService.analyze(request);

        StepVerifier.create(stream)
            .expectSubscription()
            .assertNext(chunk -> {
                assert chunk.type() == SentinelChunk.ChunkType.ANALYSIS_COMPLETE;
                // Console will log: [TENANT: UNKNOWN_TENANT] Commencing Architectural Review...
            })
            .verifyComplete();
    }
}