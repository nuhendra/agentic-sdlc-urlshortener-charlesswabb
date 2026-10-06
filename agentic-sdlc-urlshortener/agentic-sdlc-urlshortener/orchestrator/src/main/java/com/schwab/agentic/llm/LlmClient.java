package com.schwab.agentic.llm;

/**
 * Abstraction over a large language model backend.
 *
 * <p>Two implementations ship: {@link AnthropicLlmClient} (the real, default runtime backend)
 * and {@link DeterministicLlmClient} (hermetic, offline — used by unit tests and the no-key
 * console demo). Keeping this an interface is what makes the orchestration engine testable
 * without network calls while still running against a real model in production.
 */
public interface LlmClient {

    record LlmRequest(String systemPrompt, String userPrompt, int maxTokens) {
    }

    record LlmResponse(String text, long inputTokens, long outputTokens) {
        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    /**
     * Completes a prompt.
     *
     * @param request the system prompt, user prompt, and max output tokens
     * @return the model's text response and token usage
     */
    LlmResponse complete(LlmRequest request);

    /**
     * Returns a short backend identifier for audit/metrics.
     *
     * @return e.g. {@code "anthropic:claude-..."} or {@code "deterministic"}
     */
    String backendId();
}
