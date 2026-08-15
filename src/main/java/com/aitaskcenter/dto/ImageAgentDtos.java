package com.aitaskcenter.dto;

import java.time.OffsetDateTime;
import java.util.List;

public final class ImageAgentDtos {
    private ImageAgentDtos() {
    }

    public record AgentUpdateRequest(String systemPrompt, String aiProviderId, Double temperature,
                                     Boolean enabled, OffsetDateTime updatedAt) {
    }

    public record RestoreVersionRequest(OffsetDateTime updatedAt) {
    }

    public record FlowUpdateRequest(String imageProviderId, Integer width, Integer height,
                                    Integer maxShotsPerScene, Integer maxShotsPerStory,
                                    OffsetDateTime updatedAt) {
    }

    public record StyleCreateRequest(String name, String positivePrompt, String negativePrompt,
                                     String description, Boolean enabled) {
    }

    public record StyleUpdateRequest(String name, String positivePrompt, String negativePrompt,
                                     String description, Boolean enabled, OffsetDateTime updatedAt) {
    }

    public record AgentView(String key, String name, String nodeKind, String roleType, String stageKey,
                            int order, String parallelGroup, String description, List<String> variables,
                            String systemPrompt, String aiProviderId, Double temperature, Boolean enabled,
                            Integer promptVersion, OffsetDateTime updatedAt, boolean editable) {
    }

    public record StageView(String key, String name, String note, int order, List<AgentView> nodes) {
    }

    public record FlowConfigView(String imageProviderId, int width, int height, int maxShotsPerScene,
                                 int maxShotsPerStory, OffsetDateTime updatedAt) {
    }

    public record StylePresetView(Long id, String key, String name, String positivePrompt,
                                  String negativePrompt, String description, boolean enabled,
                                  boolean builtIn, OffsetDateTime updatedAt) {
    }

    public record FlowView(List<StageView> stages, FlowConfigView config,
                           List<StylePresetView> stylePresets) {
    }

    public record PromptVersionView(int version, String systemPrompt, String aiProviderId,
                                    double temperature, boolean enabled, OffsetDateTime createdAt) {
    }
}
