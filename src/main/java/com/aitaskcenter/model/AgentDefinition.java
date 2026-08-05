package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tb_agent_definition")
public class AgentDefinition extends BaseEntity {
    @Column(nullable = false, unique = true, length = 80)
    private String agentKey;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 120)
    private String aiProviderId;

    @Column(nullable = false, columnDefinition = "text")
    private String systemPrompt;

    @Column(nullable = false, columnDefinition = "text")
    private String promptTemplate;

    @Column(nullable = false, columnDefinition = "text")
    private String inputSchema;

    @Column(nullable = false, columnDefinition = "text")
    private String outputSchema;

    @Column(columnDefinition = "text")
    private String hardRules;

    @Column(columnDefinition = "text")
    private String evaluationRubric;

    @Column(nullable = false)
    private Double temperature;

    @Column(nullable = false)
    private Integer maxTokens;

    @Column(nullable = false)
    private Integer retryLimit;

    @Column(nullable = false)
    private Integer sortOrder;

    public String getAgentKey() { return agentKey; }
    public void setAgentKey(String agentKey) { this.agentKey = agentKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAiProviderId() { return aiProviderId; }
    public void setAiProviderId(String aiProviderId) { this.aiProviderId = aiProviderId; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getPromptTemplate() { return promptTemplate; }
    public void setPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; }
    public String getInputSchema() { return inputSchema; }
    public void setInputSchema(String inputSchema) { this.inputSchema = inputSchema; }
    public String getOutputSchema() { return outputSchema; }
    public void setOutputSchema(String outputSchema) { this.outputSchema = outputSchema; }
    public String getHardRules() { return hardRules; }
    public void setHardRules(String hardRules) { this.hardRules = hardRules; }
    public String getEvaluationRubric() { return evaluationRubric; }
    public void setEvaluationRubric(String evaluationRubric) { this.evaluationRubric = evaluationRubric; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public Integer getRetryLimit() { return retryLimit; }
    public void setRetryLimit(Integer retryLimit) { this.retryLimit = retryLimit; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
