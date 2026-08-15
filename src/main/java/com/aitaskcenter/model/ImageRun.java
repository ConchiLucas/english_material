package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tb_image_run", uniqueConstraints = {
        @UniqueConstraint(name = "uk_image_run_run_id", columnNames = "run_id")
}, indexes = {
        @Index(name = "idx_image_run_created", columnList = "created_at"),
        @Index(name = "idx_image_run_status_created", columnList = "status,created_at")
})
public class ImageRun extends BaseEntity {
    @Column(name = "run_id", nullable = false, length = 64) private String runId;
    @Column(name = "story_run_id", nullable = false, length = 64) private String storyRunId;
    @Column(name = "story_snapshot", nullable = false, columnDefinition = "TEXT") private String storySnapshot;
    @Column(name = "input_words_json", nullable = false, columnDefinition = "TEXT") private String inputWordsJson;
    @Column(name = "target_grade", nullable = false, length = 80) private String targetGrade;
    @Column(name = "style_preset_id", length = 80) private String stylePresetId;
    @Column(name = "style_snapshot_json", nullable = false, columnDefinition = "TEXT") private String styleSnapshotJson;
    @Column(name = "flow_snapshot_json", nullable = false, columnDefinition = "TEXT") private String flowSnapshotJson;
    @Column(name = "agent_snapshot_json", nullable = false, columnDefinition = "TEXT") private String agentSnapshotJson;
    @Column(name = "status", nullable = false, length = 40) private String status;
    @Column(name = "expected_image_count", nullable = false) private int expectedImageCount;
    @Column(name = "generated_image_count", nullable = false) private int generatedImageCount;
    @Column(name = "total_text_tokens", nullable = false) private long totalTextTokens;
    @Column(name = "error_message", columnDefinition = "TEXT") private String errorMessage;
    @Column(name = "started_at") private OffsetDateTime startedAt;
    @Column(name = "finished_at") private OffsetDateTime finishedAt;
    @Version @Column(name = "version", nullable = false) private long version;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getStoryRunId() { return storyRunId; }
    public void setStoryRunId(String storyRunId) { this.storyRunId = storyRunId; }
    public String getStorySnapshot() { return storySnapshot; }
    public void setStorySnapshot(String storySnapshot) { this.storySnapshot = storySnapshot; }
    public String getInputWordsJson() { return inputWordsJson; }
    public void setInputWordsJson(String inputWordsJson) { this.inputWordsJson = inputWordsJson; }
    public String getTargetGrade() { return targetGrade; }
    public void setTargetGrade(String targetGrade) { this.targetGrade = targetGrade; }
    public String getStylePresetId() { return stylePresetId; }
    public void setStylePresetId(String stylePresetId) { this.stylePresetId = stylePresetId; }
    public String getStyleSnapshotJson() { return styleSnapshotJson; }
    public void setStyleSnapshotJson(String styleSnapshotJson) { this.styleSnapshotJson = styleSnapshotJson; }
    public String getFlowSnapshotJson() { return flowSnapshotJson; }
    public void setFlowSnapshotJson(String flowSnapshotJson) { this.flowSnapshotJson = flowSnapshotJson; }
    public String getAgentSnapshotJson() { return agentSnapshotJson; }
    public void setAgentSnapshotJson(String agentSnapshotJson) { this.agentSnapshotJson = agentSnapshotJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getExpectedImageCount() { return expectedImageCount; }
    public void setExpectedImageCount(int expectedImageCount) { this.expectedImageCount = expectedImageCount; }
    public int getGeneratedImageCount() { return generatedImageCount; }
    public void setGeneratedImageCount(int generatedImageCount) { this.generatedImageCount = generatedImageCount; }
    public long getTotalTextTokens() { return totalTextTokens; }
    public void setTotalTextTokens(long totalTextTokens) { this.totalTextTokens = totalTextTokens; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
