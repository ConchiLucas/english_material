package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tb_story_run", uniqueConstraints = {
        @UniqueConstraint(name = "uk_story_run_run_id", columnNames = "run_id")
})
public class StoryRun extends BaseEntity {
    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "input_words_json", nullable = false, columnDefinition = "TEXT")
    private String inputWordsJson;

    @Column(name = "target_grade", nullable = false, length = 80)
    private String targetGrade;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "final_story", columnDefinition = "TEXT")
    private String finalStory;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getInputWordsJson() {
        return inputWordsJson;
    }

    public void setInputWordsJson(String inputWordsJson) {
        this.inputWordsJson = inputWordsJson;
    }

    public String getTargetGrade() {
        return targetGrade;
    }

    public void setTargetGrade(String targetGrade) {
        this.targetGrade = targetGrade;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFinalStory() {
        return finalStory;
    }

    public void setFinalStory(String finalStory) {
        this.finalStory = finalStory;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
