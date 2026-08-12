package com.aitaskcenter.dto;

import java.time.OffsetDateTime;
import java.util.List;

public final class StoryAgentDtos {
    private StoryAgentDtos() {
    }

    public record AgentUpdateRequest(
            String systemPrompt,
            String aiProviderId,
            Double temperature,
            Boolean enabled,
            OffsetDateTime updatedAt) {
    }

    public record BudgetUpdateRequest(
            Integer maxQualityRounds,
            Integer maxLocalRevisions,
            Integer maxWriterRewrites,
            Integer maxDirectorReturns,
            Integer maxPitchReturns,
            Integer maxPlanReturns,
            Integer maxTotalTokens) {
    }

    public record AgentView(
            String key,
            String name,
            String nodeKind,
            String roleType,
            String stageKey,
            int order,
            String parallelGroup,
            String description,
            List<String> variables,
            List<String> upstream,
            List<String> downstream,
            String systemPrompt,
            String aiProviderId,
            Double temperature,
            Boolean enabled,
            Integer promptVersion,
            OffsetDateTime updatedAt,
            boolean editable) {
    }

    public record StageView(
            String key,
            String name,
            String note,
            int order,
            List<AgentView> nodes) {
    }

    public record BudgetView(
            int maxQualityRounds,
            int maxLocalRevisions,
            int maxWriterRewrites,
            int maxDirectorReturns,
            int maxPitchReturns,
            int maxPlanReturns,
            int maxTotalTokens,
            OffsetDateTime updatedAt) {
    }

    public record FlowView(List<StageView> stages, BudgetView budget) {
    }

    public record PromptVersionView(
            int version,
            String systemPrompt,
            String aiProviderId,
            double temperature,
            boolean enabled,
            OffsetDateTime createdAt) {
    }
}
