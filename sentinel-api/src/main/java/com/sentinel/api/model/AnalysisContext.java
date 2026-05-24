package com.sentinel.api.model;

public record AnalysisContext(
    String tenantId,
    String sessionId,
    ReviewRequest request,
    String history,
    String ragContext,
    String finalPrompt
) {
    /**
     * Helper to create a new context with history
     * @param history
     * @return
     */
    public AnalysisContext withHistory(String history) {
        return new AnalysisContext(this.tenantId, this.sessionId,
        		this.request, history, this.ragContext, this.finalPrompt);
    }

    /** 
     * Helper to create a new context with the final prompt
     * @param prompt
     * @return
     */
    public AnalysisContext withPrompt(String prompt) {
        return new AnalysisContext(this.tenantId, this.sessionId,
        		this.request, this.history, this.ragContext, prompt);
    }
    
    /**
     * 
     * @param ragContext
     * @return
     */
    public AnalysisContext withRagContext(String ragContext) {
        return new AnalysisContext(this.tenantId, this.sessionId,
        		this.request, this.history, ragContext, this.finalPrompt);
    }
}