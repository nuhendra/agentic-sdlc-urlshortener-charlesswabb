package com.schwab.agentic.domain;

import com.schwab.agentic.domain.Enums.ScenarioType;

/**
 * The input to a run: a (possibly ambiguous) engineering requirement.
 *
 * @param id           stable identifier
 * @param title        short human label
 * @param rawText      the requirement as received, verbatim
 * @param scenarioType greenfield / brownfield / ambiguous
 * @param targetModule the codebase area the work targets (brownfield reasoning); may be null
 */
public record Requirement(
        String id,
        String title,
        String rawText,
        ScenarioType scenarioType,
        String targetModule) {
}
