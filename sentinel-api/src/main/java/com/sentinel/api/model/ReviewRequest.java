/**
 * 
 */
package com.sentinel.api.model;

/**
 * Represents the user review request. 
 * 
 * Instead of just "Text," we want to know the Context 
 * (e.g., "Scale," "Security," or "Cost").
 * 
 * MODEL: ReviewRequest
 * CONTEXT: Captures the user's architecture (Mermaid/Text) and their 
 * specific focus area for the Sentinel's critique.
 */
public record ReviewRequest(
		String content,      // The actual diagram or description
	    String focusArea,    // e.g., "Scalability", "Security", "Cost"
	    int priority         // 1-5
		
		) {
	

}
