package com.aitaskcenter.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record AgentTestResult(
        Long runId,
        Long agentId,
        String agentKey,
        String agentName,
        String aiProviderId,
        String status,
        String inputJson,
        String outputText,
        boolean schemaValid,
        Integer overallScore,
        Map<String, Integer> dimensionScores,
        List<String> issues,
        long durationMs,
        String errorMessage,
        OffsetDateTime createdAt) {
}
