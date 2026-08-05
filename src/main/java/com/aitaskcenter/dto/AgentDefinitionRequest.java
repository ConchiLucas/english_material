package com.aitaskcenter.dto;

public record AgentDefinitionRequest(
        String agentKey,
        String name,
        String category,
        String description,
        String aiProviderId,
        String systemPrompt,
        String promptTemplate,
        String inputSchema,
        String outputSchema,
        String hardRules,
        String evaluationRubric,
        Double temperature,
        Integer maxTokens,
        Integer retryLimit,
        Integer sortOrder) {
}
