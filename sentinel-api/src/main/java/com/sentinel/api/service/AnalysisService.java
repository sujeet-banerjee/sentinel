package com.sentinel.api.service;

import com.sentinel.api.model.ReviewRequest;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import java.time.Duration;

@Service
public class AnalysisService {
	
	/**
	 * Auto injected through contructor injection
	 */
	private final OllamaChatModel chatModel;
	
	private static final String SYST_PROMPT_TEMPLATE = 
			"You are an expert Software Architect. "
			+ "Critique the following architecture " +
            "focusing on %s. Be concise and technical. "
            + "Make your response less than 10000 words. "
            + "Always begin your response with "
            + "'SENTINEL ANALYSIS STARTING...' and complete "
            + " with 'ANALYSIS COMPLETE'. \n\n %s";
	
	public AnalysisService(OllamaChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * core method to simulate AI "Thinking" and streaming responses.
     * We use Flux.interval to mimic the delay of an LLM generating tokens.
     */
    public Flux<String> analyze(ReviewRequest request) {
    	
    	String systemPrompt = String.format(
    			SYST_PROMPT_TEMPLATE, request.focusArea(), 
    			request.content());

        // This is the "Magic": streaming tokens directly from Ollama
        return chatModel.stream(new Prompt(systemPrompt))
                .map(ChatResponse::getResult)
                .map(result -> result.getOutput().getText())
                .filter(text -> text != null && !text.isEmpty());
    	
    }
}