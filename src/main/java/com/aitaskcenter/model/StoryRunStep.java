package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "tb_story_run_step", uniqueConstraints = {
        @UniqueConstraint(name = "uk_story_run_step_sequence", columnNames = {"run_id", "step_sequence"})
})
public class StoryRunStep extends BaseEntity {
    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "step_sequence", nullable = false)
    private int sequence;

    @Column(name = "quality_round", nullable = false)
    private int qualityRound;

    @Column(name = "agent_key", nullable = false, length = 80)
    private String agentKey;

    @Column(name = "agent_name", nullable = false, length = 120)
    private String agentName;

    @Column(name = "prompt_version", nullable = false)
    private int promptVersion;

    @Column(name = "provider_id", length = 120)
    private String providerId;

    @Column(name = "provider_model", length = 180)
    private String providerModel;

    @Column(name = "input_json", nullable = false, columnDefinition = "TEXT")
    private String inputJson;

    @Column(name = "output_text", columnDefinition = "TEXT")
    private String outputText;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public int getQualityRound() { return qualityRound; }
    public void setQualityRound(int qualityRound) { this.qualityRound = qualityRound; }
    public String getAgentKey() { return agentKey; }
    public void setAgentKey(String agentKey) { this.agentKey = agentKey; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public int getPromptVersion() { return promptVersion; }
    public void setPromptVersion(int promptVersion) { this.promptVersion = promptVersion; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getProviderModel() { return providerModel; }
    public void setProviderModel(String providerModel) { this.providerModel = providerModel; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }
    public String getOutputText() { return outputText; }
    public void setOutputText(String outputText) { this.outputText = outputText; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
}
