/**
 * 
 */
package com.sentinel.api.model;

/**
 * 
 */
public record SentinelChunk(
	    String content,
	    ChunkType type,
	    long timestamp
	) {
	    public enum ChunkType {
	        TEXT,
	        /**
	         * SENTINEL ANALYSIS STARTING
	         */
	        DIAGRAM_START, 
	        /**
	         * ANALYSIS COMPLETE
	         */
	        ANALYSIS_COMPLETE
	    }
}
