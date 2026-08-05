package com.aitaskcenter.dto;

public record WordCleanItem(
        long id,
        String word,
        String meaning,
        int difficulty,
        int frequency,
        String sentence,
        Integer pepDifficulty,
        String pepDifficultyLabel,
        Integer sourceDifficulty,
        String sourceLabel,
        Long bestSentenceId,
        Long bestSourceSentenceId,
        String bestSourceModelName,
        String bestSentence,
        String bestSentenceTranslation,
        Integer bestSentenceScore,
        String bestSentenceScoreReason,
        String bestSentenceScoreModelName,
        String bestSentenceScoredAt,
        String bestSentenceTtsStatus,
        String bestSentenceTtsObjectUrl,
        String wordTtsStatus,
        String wordTtsObjectUrl) {
}
