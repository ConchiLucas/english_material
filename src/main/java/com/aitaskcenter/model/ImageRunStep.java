package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tb_image_run_step", uniqueConstraints = {
        @UniqueConstraint(name = "uk_image_run_step_sequence", columnNames = {"run_id", "step_sequence"})
})
public class ImageRunStep extends BaseEntity {
    @Column(name = "run_id", nullable = false, length = 64) private String runId;
    @Column(name = "step_sequence", nullable = false) private int sequence;
    @Column(name = "stage_key", nullable = false, length = 80) private String stageKey;
    @Column(name = "node_key", nullable = false, length = 80) private String nodeKey;
    @Column(name = "node_name", nullable = false, length = 120) private String nodeName;
    @Column(name = "node_kind", nullable = false, length = 40) private String nodeKind;
    @Column(name = "prompt_version") private Integer promptVersion;
    @Column(name = "provider_id", length = 120) private String providerId;
    @Column(name = "provider_model", length = 180) private String providerModel;
    @Column(name = "input_json", columnDefinition = "TEXT") private String inputJson;
    @Column(name = "raw_output", columnDefinition = "TEXT") private String rawOutput;
    @Column(name = "parsed_output_json", columnDefinition = "TEXT") private String parsedOutputJson;
    @Column(name = "error_message", columnDefinition = "TEXT") private String errorMessage;
    @Column(name = "status", nullable = false, length = 40) private String status;
    @Column(name = "input_tokens", nullable = false) private long inputTokens;
    @Column(name = "output_tokens", nullable = false) private long outputTokens;
    @Column(name = "total_tokens", nullable = false) private long totalTokens;
    @Column(name = "duration_ms", nullable = false) private long durationMs;
    @Column(name = "started_at") private OffsetDateTime startedAt;
    @Column(name = "finished_at") private OffsetDateTime finishedAt;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public String getStageKey() { return stageKey; }
    public void setStageKey(String stageKey) { this.stageKey = stageKey; }
    public String getNodeKey() { return nodeKey; }
    public void setNodeKey(String nodeKey) { this.nodeKey = nodeKey; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getNodeKind() { return nodeKind; }
    public void setNodeKind(String nodeKind) { this.nodeKind = nodeKind; }
    public Integer getPromptVersion() { return promptVersion; }
    public void setPromptVersion(Integer promptVersion) { this.promptVersion = promptVersion; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getProviderModel() { return providerModel; }
    public void setProviderModel(String providerModel) { this.providerModel = providerModel; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }
    public String getRawOutput() { return rawOutput; }
    public void setRawOutput(String rawOutput) { this.rawOutput = rawOutput; }
    public String getParsedOutputJson() { return parsedOutputJson; }
    public void setParsedOutputJson(String parsedOutputJson) { this.parsedOutputJson = parsedOutputJson; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
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
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
}
