package com.sentinel.api.model;

public record AnalysisContext(
    String tenantId,
    String sessionId,
    ReviewRequest request,
    String history,
    String finalPrompt
) {
    /**
     * Helper to create a new context with history
     * @param history
     * @return
     */
    public AnalysisContext withHistory(String history) {
        return new AnalysisContext(this.tenantId, this.sessionId,
        		this.request, history, this.finalPrompt);
    }

    /** 
     * Helper to create a new context with the final prompt
     * @param prompt
     * @return
     */
    public AnalysisContext withPrompt(String prompt) {
        return new AnalysisContext(this.tenantId, this.sessionId,
        		this.request, this.history, prompt);
    }
}