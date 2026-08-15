package com.aitaskcenter.config;

import java.util.List;

/** Frozen compatibility contract for persisted image Agent run snapshots. */
public final class ImageAgentSnapshotContract {
    public static final int V1_SCHEMA_VERSION = 1;

    private static final List<AgentDefinition> V1_AGENTS = List.of(
            new AgentDefinition(1, "understanding", "image-story-analyst"),
            new AgentDefinition(2, "understanding", "image-continuity-designer"),
            new AgentDefinition(3, "understanding", "image-art-director"),
            new AgentDefinition(4, "storyboarding", "image-action-storyboarder"),
            new AgentDefinition(5, "storyboarding", "image-learning-storyboarder"),
            new AgentDefinition(6, "storyboarding", "image-storyboard-director"),
            new AgentDefinition(7, "prompting", "image-reference-planner"),
            new AgentDefinition(8, "prompting", "image-shot-prompt-engineer"),
            new AgentDefinition(9, "prompting", "image-prompt-preflight"));

    private ImageAgentSnapshotContract() {
    }

    public static List<AgentDefinition> v1Agents() {
        return V1_AGENTS;
    }

    public static <T> SnapshotEnvelope<T> v1Envelope(List<T> agents) {
        return new SnapshotEnvelope<>(V1_SCHEMA_VERSION, agents);
    }

    public record AgentDefinition(int sequence, String stageKey, String key) {
    }

    public record SnapshotEnvelope<T>(int schemaVersion, List<T> agents) {
        public SnapshotEnvelope {
            agents = List.copyOf(agents);
        }
    }
}
