package com.schwab.agentic.agents;

import com.schwab.agentic.domain.Artifact;
import com.schwab.agentic.domain.Enums.Stage;
import com.schwab.agentic.domain.RunContext;
import com.schwab.agentic.llm.LlmClient;
import com.schwab.agentic.llm.LlmClient.LlmRequest;
import com.schwab.agentic.llm.LlmClient.LlmResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared machinery for agents: prompt assembly (including the deterministic-client control
 * header), the LLM call, and artifact construction with decision lineage.
 */
abstract class BaseAgent implements Agent {

    protected final LlmClient llm;

    /**
     * @param llm the LLM backend this agent calls
     */
    protected BaseAgent(LlmClient llm) {
        this.llm = llm;
    }

    /** @return the upstream stages whose artifacts this agent consumes (for lineage + prompt) */
    protected abstract List<Stage> consumes();

    /** @return the stage-specific system prompt describing the role and output format */
    protected abstract String systemPrompt();

    /**
     * Builds the prompt, calls the LLM, and wraps the response in an {@link Artifact} with
     * lineage pointing at the consumed upstream artifacts.
     *
     * @param context the requirement, upstream context, attempt, and failure feedback
     * @return the produced artifact plus token usage (no decisions by default)
     */
    @Override
    public AgentResult execute(AgentContext context) {
        String userPrompt = buildUserPrompt(context);
        LlmResponse response = llm.complete(new LlmRequest(systemPrompt(), userPrompt, 1024));

        List<String> lineage = new ArrayList<>();
        for (Stage s : consumes()) {
            context.context().artifact(s).ifPresent(a -> lineage.add(a.id()));
        }
        Artifact artifact = Artifact.of(
                stage() + "-" + UUID.randomUUID().toString().substring(0, 8),
                stage(),
                stage().name().toLowerCase() + "-output",
                response.text(),
                context.context().nextVersion(),
                lineage);
        return new AgentResult(artifact, List.of(), response.totalTokens(), response.text());
    }

    /**
     * Assembles the user prompt: the deterministic-client control header, the requirement,
     * upstream artifacts, and (on retries) the prior failure reason for self-correction.
     *
     * @param ctx the agent context
     * @return the composed user prompt
     */
    private String buildUserPrompt(AgentContext ctx) {
        StringBuilder sb = new StringBuilder();
        // Control header consumed by the deterministic backend; harmless to the real model.
        sb.append("#CTRL stage=").append(stage())
                .append(" scenario=").append(ctx.requirement().scenarioType())
                .append(" attempt=").append(ctx.attempt())
                .append(" fault=").append(ctx.faultMode() == null ? "NONE" : ctx.faultMode())
                .append('\n');
        sb.append("REQUIREMENT: ").append(ctx.requirement().rawText()).append('\n');
        if (ctx.requirement().targetModule() != null) {
            sb.append("TARGET_MODULE: ").append(ctx.requirement().targetModule()).append('\n');
        }
        for (Stage s : consumes()) {
            ctx.context().artifact(s).ifPresent(a ->
                    sb.append("UPSTREAM[").append(s).append("]:\n").append(a.content()).append('\n'));
        }
        if (ctx.attempt() > 1 && ctx.failureReason() != null) {
            sb.append("PREVIOUS_ATTEMPT_FAILED_BECAUSE: ").append(ctx.failureReason()).append('\n');
            sb.append("Please correct the above and regenerate.\n");
        }
        return sb.toString();
    }

    /**
     * Pulls a {@code KEY: value} field out of an LLM text block.
     *
     * @param text the LLM output
     * @param key  the field name
     * @return the trimmed value, or an empty string if absent
     */
    protected static String field(String text, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*:\\s*(.*)$").matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }
}
