package com.aitaskcenter.dto;

import java.time.OffsetDateTime;
import java.util.List;

public final class StoryRunDtos {
    private StoryRunDtos() {
    }

    public record StoryWord(String word, String meaning) {
    }

    public record RandomWordsRequest(Long connectionId, Long libraryId, Integer count) {
    }

    public record StartRunRequest(List<StoryWord> words, String targetGrade) {
    }

    public record WordLibraryView(Long id, String name, String meaning, int wordCount) {
    }

    public record RunSummary(
            String runId,
            List<StoryWord> words,
            String targetGrade,
            String status,
            long totalTokens,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {
    }

    public record RunStepView(
            Long id,
            int sequence,
            int qualityRound,
            String agentKey,
            String agentName,
            int promptVersion,
            String providerId,
            String providerModel,
            String inputJson,
            String outputText,
            String status,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long durationMs,
            OffsetDateTime createdAt) {
    }

    public record RunDetail(
            String runId,
            List<StoryWord> words,
            String targetGrade,
            String status,
            String finalStory,
            String errorMessage,
            long totalTokens,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            List<RunStepView> steps) {
    }
}
