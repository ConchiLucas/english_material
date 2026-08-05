package com.aitaskcenter.dto;

public record WordCleanSentenceItem(
        long id,
        long wordCleanId,
        String word,
        String modelName,
        String sentence,
        String sentenceTranslation,
        Integer score,
        String scoreReason,
        String scoreModelName,
        String scoredAt) {
}
