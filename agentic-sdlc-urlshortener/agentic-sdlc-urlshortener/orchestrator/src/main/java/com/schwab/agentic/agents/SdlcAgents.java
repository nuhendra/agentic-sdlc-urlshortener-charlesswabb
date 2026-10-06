package com.schwab.agentic.agents;

import com.schwab.agentic.domain.Decision;
import com.schwab.agentic.domain.Enums.Stage;
import com.schwab.agentic.llm.LlmClient;

import java.util.List;

/**
 * The six SDLC agents, one per stage. Each declares what it consumes (for lineage and prompt
 * context) and a role/system prompt specifying the structured output the engine's gates parse.
 */
public final class SdlcAgents {

    private SdlcAgents() {
    }

    /** Interprets intent, surfaces ambiguity, and normalizes the requirement. */
    public static final class RequirementsAnalystAgent extends BaseAgent {
        /** @param llm the LLM backend */
        public RequirementsAnalystAgent(LlmClient llm) {
            super(llm);
        }

        /** {@inheritDoc} */
        @Override
        public Stage stage() {
            return Stage.REQUIREMENTS;
        }

        /** {@inheritDoc} */
        @Override
        protected List<Stage> consumes() {
            return List.of();
        }

        /** {@inheritDoc} */
        @Override
        protected String systemPrompt() {
            return """
                   You are a senior requirements analyst. Interpret the intent, surface
                   ambiguity, and normalize the requirement into a clear engineering problem.
                   Respond with these fields:
                   AMBIGUITY_SCORE: <0.0-1.0>
                   NORMALIZED_SPEC: <one paragraph>
                   ACCEPTANCE_CRITERIA: <bullet list>
                   OPEN_QUESTIONS: <bullet list, only if ambiguous>
                   """;
        }

        /**
         * Runs the base LLM call, then attaches a decision recording the assessed ambiguity
         * (which drives whether a human clarification checkpoint is required).
         *
         * @param context the agent context
         * @return the requirements artifact plus the ambiguity decision
         */
        @Override
        public AgentResult execute(AgentContext context) {
            AgentResult base = super.execute(context);
            String score = field(base.rawText(), "AMBIGUITY_SCORE");
            Decision d = Decision.of(
                    "dec-req-" + context.requirement().id(),
                    Stage.REQUIREMENTS,
                    "Ambiguity assessed at " + score,
                    "Normalized the raw requirement; ambiguity score drives whether a human "
                            + "clarification checkpoint is required before design proceeds.");
            return new AgentResult(base.artifact(), List.of(d), base.tokensUsed(), base.rawText());
        }
    }

    /** Produces the design and, for brownfield work, the impacted modules and data flows. */
    public static final class ArchitectAgent extends BaseAgent {
        /** @param llm the LLM backend */
        public ArchitectAgent(LlmClient llm) {
            super(llm);
        }

        /** {@inheritDoc} */
        @Override
        public Stage stage() {
            return Stage.DESIGN;
        }

        /** {@inheritDoc} */
        @Override
        protected List<Stage> consumes() {
            return List.of(Stage.REQUIREMENTS);
        }

        /** {@inheritDoc} */
        @Override
        protected String systemPrompt() {
            return """
                   You are a senior software architect. Produce a design for the normalized
                   requirement and, for brownfield work, identify impacted modules and data flows.
                   Respond with these fields:
                   DESIGN_SUMMARY: <text>
                   IMPACTED_MODULES: <comma list>
                   API_CHANGES: <text>
                   DATA_CHANGES: <text>
                   RISKS: <text>
                   """;
        }
    }

    /** Produces production-quality code for the design. */
    public static final class ImplementerAgent extends BaseAgent {
        /** @param llm the LLM backend */
        public ImplementerAgent(LlmClient llm) {
            super(llm);
        }

        /** {@inheritDoc} */
        @Override
        public Stage stage() {
            return Stage.IMPLEMENTATION;
        }

        /** {@inheritDoc} */
        @Override
        protected List<Stage> consumes() {
            return List.of(Stage.DESIGN);
        }

        /** {@inheritDoc} */
        @Override
        protected String systemPrompt() {
            return """
                   You are a senior engineer. Implement the design with production-quality code.
                   Respond with these fields:
                   DIFF_SUMMARY: <text>
                   LINES_CHANGED: <integer>
                   CODE: <the code>
                   SELF_CHECK: <complete|incomplete>
                   Never hardcode secrets; always use parameterized queries.
                   """;
        }
    }

    /** Writes and runs unit and integration tests for the change. */
    public static final class TesterAgent extends BaseAgent {
        /** @param llm the LLM backend */
        public TesterAgent(LlmClient llm) {
            super(llm);
        }

        /** {@inheritDoc} */
        @Override
        public Stage stage() {
            return Stage.TESTING;
        }

        /** {@inheritDoc} */
        @Override
        protected List<Stage> consumes() {
            return List.of(Stage.IMPLEMENTATION);
        }

        /** {@inheritDoc} */
        @Override
        protected String systemPrompt() {
            return """
                   You are a senior SDET. Write and run unit and integration tests for the change.
                   Respond with these fields:
                   TESTS_PASSED: <true|false>
                   COVERAGE: <0.0-1.0>
                   SUMMARY: <text>
                   """;
        }
    }

    /** Updates user- and developer-facing documentation for the change. */
    public static final class DocWriterAgent extends BaseAgent {
        /** @param llm the LLM backend */
        public DocWriterAgent(LlmClient llm) {
            super(llm);
        }

        /** {@inheritDoc} */
        @Override
        public Stage stage() {
            return Stage.DOCUMENTATION;
        }

        /** {@inheritDoc} */
        @Override
        protected List<Stage> consumes() {
            return List.of(Stage.IMPLEMENTATION);
        }

        /** {@inheritDoc} */
        @Override
        protected String systemPrompt() {
            return """
                   You are a technical writer. Update user- and developer-facing documentation
                   for the change. Respond with these fields:
                   DOC_SUMMARY: <text>
                   CHANGELOG: <entry>
                   """;
        }
    }

    /** Assesses release readiness from the test and documentation outputs. */
    public static final class ReleaseGateAgent extends BaseAgent {
        /** @param llm the LLM backend */
        public ReleaseGateAgent(LlmClient llm) {
            super(llm);
        }

        /** {@inheritDoc} */
        @Override
        public Stage stage() {
            return Stage.RELEASE_READINESS;
        }

        /** {@inheritDoc} */
        @Override
        protected List<Stage> consumes() {
            return List.of(Stage.TESTING, Stage.DOCUMENTATION);
        }

        /** {@inheritDoc} */
        @Override
        protected String systemPrompt() {
            return """
                   You are a release manager. Assess release readiness from the test and
                   documentation outputs. Respond with these fields:
                   GO: <true|false>
                   CHECKLIST: <text>
                   RESIDUAL_RISK: <low|medium|high>
                   """;
        }
    }
}
