package com.sentinel.api.service;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.stereotype.Service;

import com.sentinel.api.model.ReviewRequest;
import com.sentinel.api.model.SentinelChunk;
import com.sentinel.api.model.SentinelChunk.ChunkType;

import reactor.core.publisher.Flux;

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
        return Flux.defer(() -> {
            
            // The Memory Buffer to stitch fragments together
            //StringBuilder conversationBuffer = new StringBuilder();
            StringBuilder slidingWindow = new StringBuilder(MAX_WINDOW_SIZE * 2);
            
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
        });
    }
}