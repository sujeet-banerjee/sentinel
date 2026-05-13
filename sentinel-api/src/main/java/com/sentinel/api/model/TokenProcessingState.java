package com.sentinel.api.model;

/**
 * Encapsulates the mutable state for a single LLM stream.
 * Instantiated uniquely per request to ensure thread safety.
 */
public class TokenProcessingState {
    private static final int MAX_WINDOW_SIZE = 64;
    private static final int MAX_MEMORY_RECORD_SIZE = 2000;

    private final StringBuilder slidingWindow = new StringBuilder(MAX_WINDOW_SIZE * 2);
    private final StringBuilder memoryRecorder = new StringBuilder(MAX_MEMORY_RECORD_SIZE + 50);
    
    private boolean diagramTriggered = false;
    private boolean completeTriggered = false;
    private boolean memoryTruncated = false;

    public SentinelChunk processToken(String token) {
        // 1. Sliding Window logic
        slidingWindow.append(token);
        if (slidingWindow.length() > MAX_WINDOW_SIZE) {
            slidingWindow.delete(0, slidingWindow.length() - MAX_WINDOW_SIZE);
        }

        // 2. Memory Recorder logic
        if (!memoryTruncated) {
            if (memoryRecorder.length() + token.length() <= MAX_MEMORY_RECORD_SIZE) {
                memoryRecorder.append(token);
            } else {
                memoryRecorder.append("\n...[TRUNCATED FOR MEMORY]");
                memoryTruncated = true;
            }
        }

        // 3. Trigger Detection
        String memory = slidingWindow.toString();
        SentinelChunk.ChunkType type = SentinelChunk.ChunkType.TEXT;

        if (!diagramTriggered && memory.contains("```mermaid")) {
            type = SentinelChunk.ChunkType.DIAGRAM_START;
            diagramTriggered = true;
        } else if (!completeTriggered && memory.contains("ANALYSIS COMPLETE")) {
            type = SentinelChunk.ChunkType.ANALYSIS_COMPLETE;
            completeTriggered = true;
        }

        return new SentinelChunk(token, type, System.currentTimeMillis());
    }

    public String getCapturedMemory() {
        return memoryRecorder.toString();
    }
}