package com.aitaskcenter.dto;

import java.time.OffsetDateTime;

public final class ImageRunDtos {
    private ImageRunDtos() {
    }

    public record StartImageRunRequest(String storyRunId, Long stylePresetId) {
    }

    public record RunSummary(
            String runId,
            String storyRunId,
            Long stylePresetId,
            String stylePresetName,
            String targetGrade,
            String status,
            int expectedImageCount,
            int generatedImageCount,
            long totalTextTokens,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {
    }
}
