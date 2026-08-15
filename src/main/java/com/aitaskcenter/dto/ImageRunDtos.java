package com.aitaskcenter.dto;

import com.aitaskcenter.dto.StoryRunDtos.StoryWord;
import java.time.OffsetDateTime;
import java.util.List;

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
            List<StoryWord> words,
            String wordsError,
            String status,
            int expectedImageCount,
            int generatedImageCount,
            long totalTextTokens,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {
        public RunSummary(
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
            this(runId, storyRunId, stylePresetId, stylePresetName, targetGrade, List.of(), null, status,
                    expectedImageCount, generatedImageCount, totalTextTokens, null, createdAt, startedAt, finishedAt);
        }
    }

    public record SourceStoryView(
            String runId,
            List<StoryWord> words,
            String wordsError,
            String targetGrade,
            String status,
            String finalStory,
            OffsetDateTime createdAt,
            OffsetDateTime finishedAt) {
    }

    public record RunStepView(
            Long id,
            int sequence,
            String stageKey,
            String nodeKey,
            String nodeName,
            String nodeKind,
            Integer promptVersion,
            String providerId,
            String providerModel,
            String inputJson,
            String rawOutput,
            String parsedOutputJson,
            String errorMessage,
            String status,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long durationMs,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            OffsetDateTime createdAt) {
    }

    public record ShotView(
            Long id,
            String shotKey,
            int sceneIndex,
            int shotIndex,
            int sequence,
            String sourceExcerpt,
            String visualGoal,
            String speaker,
            String dialogue,
            String caption,
            String textAnchorJson,
            String prompt,
            String negativePrompt,
            String referenceAssetKeysJson,
            String status,
            OffsetDateTime createdAt) {
    }

    public record AssetView(
            Long id,
            String assetType,
            String assetKey,
            String shotKey,
            String mime,
            int width,
            int height,
            String sha256,
            String providerId,
            String providerModel,
            String providerRequestId,
            String prompt,
            String negativePrompt,
            String providerMetadataJson,
            String contentUrl,
            OffsetDateTime createdAt) {
    }

    public record RunDetail(
            String runId,
            String storyRunId,
            List<StoryWord> words,
            String wordsError,
            String targetGrade,
            String status,
            String storySnapshot,
            String stylePresetId,
            String stylePresetName,
            String styleSnapshotJson,
            String flowSnapshotJson,
            int expectedImageCount,
            int generatedImageCount,
            long totalTextTokens,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            List<RunStepView> steps,
            List<ShotView> shots,
            List<AssetView> assets) {
    }
}
