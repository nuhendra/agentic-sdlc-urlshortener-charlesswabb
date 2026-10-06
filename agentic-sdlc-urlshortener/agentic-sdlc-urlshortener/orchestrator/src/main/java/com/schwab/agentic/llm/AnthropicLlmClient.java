package com.schwab.agentic.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real LLM backend calling the Anthropic Messages API over the JDK HTTP client.
 *
 * <p>Deliberately dependency-free (no SDK, no JSON library): it hand-builds the request body
 * and extracts the response with small, well-scoped parsing so the entire orchestration core
 * compiles and runs on a bare JDK. Requires {@code ANTHROPIC_API_KEY} in the environment.
 *
 * <p>This is the default runtime backend. It is intentionally <i>not</i> used by unit tests or
 * the offline demo, which use {@link DeterministicLlmClient} — you never want hermetic tests
 * making live, paid, non-deterministic network calls.
 */
public final class AnthropicLlmClient implements LlmClient {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient http;
    private final String apiKey;
    private final String model;

    /**
     * Creates a client bound to an API key and model.
     *
     * @param apiKey the Anthropic API key; must be non-blank
     * @param model  the model id to call
     * @throws IllegalStateException if {@code apiKey} is null or blank
     */
    public AnthropicLlmClient(String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is not set; set it, or use DeterministicLlmClient for offline runs");
        }
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    /**
     * Builds a client from the environment: {@code ANTHROPIC_API_KEY} and optional
     * {@code ANTHROPIC_MODEL} (defaulting to a current model id).
     *
     * @return a configured client
     * @throws IllegalStateException if {@code ANTHROPIC_API_KEY} is not set
     */
    public static AnthropicLlmClient fromEnv() {
        String model = System.getenv().getOrDefault("ANTHROPIC_MODEL", "claude-opus-4-8");
        return new AnthropicLlmClient(System.getenv("ANTHROPIC_API_KEY"), model);
    }

    /**
     * Sends the prompt to the Anthropic Messages API and parses the response.
     *
     * @param request the system/user prompts and max output tokens
     * @return the model's text and token usage
     * @throws LlmCallException if the HTTP call fails or returns a non-2xx status
     */
    @Override
    public LlmResponse complete(LlmRequest request) {
        String body = "{"
                + "\"model\":\"" + jsonEscape(model) + "\","
                + "\"max_tokens\":" + request.maxTokens() + ","
                + "\"system\":\"" + jsonEscape(request.systemPrompt()) + "\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\""
                + jsonEscape(request.userPrompt()) + "\"}]"
                + "}";
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LlmCallException(
                        "Anthropic API returned HTTP " + response.statusCode() + ": " + response.body());
            }
            return parse(response.body());
        } catch (LlmCallException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmCallException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String backendId() {
        return "anthropic:" + model;
    }

    /**
     * Parses the Messages API JSON into a response, extracting the first text block and token
     * usage.
     *
     * @param json the raw response body
     * @return the parsed {@link LlmResponse}
     */
    private static LlmResponse parse(String json) {
        String text = extractFirstTextBlock(json);
        long inputTokens = extractLong(json, "\"input_tokens\"\\s*:\\s*(\\d+)");
        long outputTokens = extractLong(json, "\"output_tokens\"\\s*:\\s*(\\d+)");
        return new LlmResponse(text, inputTokens, outputTokens);
    }

    /**
     * Extracts the first {@code "text": "..."} value, unescaping JSON string escapes.
     *
     * @param json the raw response body
     * @return the decoded text, or an empty string if no text block is present
     */
    private static String extractFirstTextBlock(String json) {
        Matcher m = Pattern.compile("\"text\"\\s*:\\s*\"").matcher(json);
        if (!m.find()) {
            return "";
        }
        int i = m.end();
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        while (i < json.length()) {
            char c = json.charAt(i++);
            if (escaped) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    default -> sb.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Extracts the first capture group of a regex as a long.
     *
     * @param json  the text to search
     * @param regex a pattern whose group 1 is a decimal number
     * @return the parsed value, or 0 if the pattern does not match
     */
    private static long extractLong(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    /**
     * Escapes a string for safe inclusion inside a JSON string literal.
     *
     * @param s the raw string, or {@code null}
     * @return the escaped string ({@code ""} for null input)
     */
    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Raised when the real LLM backend cannot produce a usable response. */
    public static final class LlmCallException extends RuntimeException {
        /**
         * @param message the failure description
         */
        public LlmCallException(String message) {
            super(message);
        }

        /**
         * @param message the failure description
         * @param cause   the underlying cause
         */
        public LlmCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
