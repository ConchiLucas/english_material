package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tb_agent_test_run")
public class AgentTestRun extends BaseEntity {
    @Column(nullable = false)
    private Long agentId;

    @Column(nullable = false, length = 80)
    private String agentKey;

    @Column(nullable = false, length = 120)
    private String agentName;

    @Column(length = 120)
    private String aiProviderId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false, columnDefinition = "text")
    private String inputJson;

    @Column(columnDefinition = "text")
    private String outputText;

    @Column(nullable = false)
    private boolean schemaValid;

    private Integer overallScore;

    @Column(columnDefinition = "text")
    private String dimensionScores;

    @Column(columnDefinition = "text")
    private String issues;

    @Column(nullable = false)
    private Long durationMs;

    @Column(columnDefinition = "text")
    private String errorMessage;

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public String getAgentKey() { return agentKey; }
    public void setAgentKey(String agentKey) { this.agentKey = agentKey; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getAiProviderId() { return aiProviderId; }
    public void setAiProviderId(String aiProviderId) { this.aiProviderId = aiProviderId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }
    public String getOutputText() { return outputText; }
    public void setOutputText(String outputText) { this.outputText = outputText; }
    public boolean isSchemaValid() { return schemaValid; }
    public void setSchemaValid(boolean schemaValid) { this.schemaValid = schemaValid; }
    public Integer getOverallScore() { return overallScore; }
    public void setOverallScore(Integer overallScore) { this.overallScore = overallScore; }
    public String getDimensionScores() { return dimensionScores; }
    public void setDimensionScores(String dimensionScores) { this.dimensionScores = dimensionScores; }
    public String getIssues() { return issues; }
    public void setIssues(String issues) { this.issues = issues; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
