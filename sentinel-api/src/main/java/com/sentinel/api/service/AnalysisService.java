package com.sentinel.api.service;

import com.sentinel.api.model.ReviewRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import java.time.Duration;

@Service
public class AnalysisService {

    /**
     * core method to simulate AI "Thinking" and streaming responses.
     * We use Flux.interval to mimic the delay of an LLM generating tokens.
     */
    public Flux<String> analyze(ReviewRequest request) {
        return Flux.just(
            "SENTINEL ANALYSIS STARTING...",
            "Focusing on: " + request.focusArea(),
            "Analyzing components...",
            "CRITIQUE: Consider adding a Load Balancer for higher availability.",
            "ANALYSIS COMPLETE."
        )
        .delayElements(Duration.ofMillis(500)); // Simulate AI processing time
    }
}