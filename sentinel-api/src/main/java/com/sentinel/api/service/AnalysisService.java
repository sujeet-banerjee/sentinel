package com.sentinel.api.service;

import com.sentinel.api.model.ReviewRequest;
import com.sentinel.api.model.SentinelChunk;
import com.sentinel.api.model.SentinelChunk.ChunkType;

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
			+ "Critique the following architecture focusing on %s. "
			+ "1. Use standard Markdown for text. "
			+ "2. If illustrating a flow or structure, "
			+ "you MUST use a Mermaid.js block: ```mermaid [code] ```. "
			+ "3. Always begin with 'SENTINEL ANALYSIS STARTING...'. "
			+ "4. Always conclude with the exact string: 'ANALYSIS COMPLETE'. \n\n %s";
	
	public AnalysisService(OllamaChatModel chatModel) {
        this.chatModel = chatModel;
    }

	/*
	 * public Flux<String> analyze(ReviewRequest request) {
	 * 
	 * String systemPrompt = String.format( SYST_PROMPT_TEMPLATE,
	 * request.focusArea(), request.content());
	 * 
	 * // This is the "Magic": streaming tokens directly from Ollama return
	 * chatModel.stream(new Prompt(systemPrompt)) .map(ChatResponse::getResult)
	 * .map(result -> result.getOutput().getText()) .filter(text -> text != null &&
	 * !text.isEmpty());
	 * 
	 * }
	 */
	
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
        return Flux.defer(() -> {
            
            // The Memory Buffer to stitch fragments together
            StringBuilder conversationBuffer = new StringBuilder();
            
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
                        conversationBuffer.append(token);
                        String memory = conversationBuffer.toString();
                        
                        ChunkType type = ChunkType.TEXT;
                        
                        // 2. Check the accumulated memory, not the isolated token
                        if (!diagramTriggered[0] && memory.contains("```mermaid")) {
                            type = ChunkType.DIAGRAM_START;
                            diagramTriggered[0] = true; // Lock it so it only fires once
                        } 
                        else if (!completeTriggered[0] && memory.contains("ANALYSIS COMPLETE")) {
                            type = ChunkType.ANALYSIS_COMPLETE;
                            completeTriggered[0] = true; // Lock it so it only fires once
                        }
                        
                        // 3. Emit the original token, but with the intelligent metadata attached
                        return new SentinelChunk(token, type, System.currentTimeMillis());
                    });
        });
    }
}