package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "tb_image_shot", uniqueConstraints = {
        @UniqueConstraint(name = "uk_image_shot_run_key", columnNames = {"run_id", "shot_key"})
}, indexes = @Index(name = "idx_image_shot_run_sequence", columnList = "run_id,shot_sequence"))
public class ImageShot extends BaseEntity {
    @Column(name = "run_id", nullable = false, length = 64) private String runId;
    @Column(name = "shot_key", nullable = false, length = 80) private String shotKey;
    @Column(name = "scene_index", nullable = false) private int sceneIndex;
    @Column(name = "shot_index", nullable = false) private int shotIndex;
    @Column(name = "shot_sequence", nullable = false) private int sequence;
    @Column(name = "source_excerpt", columnDefinition = "TEXT") private String sourceExcerpt;
    @Column(name = "visual_goal", columnDefinition = "TEXT") private String visualGoal;
    @Column(name = "speaker", length = 120) private String speaker;
    @Column(name = "dialogue", columnDefinition = "TEXT") private String dialogue;
    @Column(name = "caption", columnDefinition = "TEXT") private String caption;
    @Column(name = "text_anchor_json", columnDefinition = "TEXT") private String textAnchorJson;
    @Column(name = "prompt", columnDefinition = "TEXT") private String prompt;
    @Column(name = "negative_prompt", columnDefinition = "TEXT") private String negativePrompt;
    @Column(name = "reference_asset_keys_json", columnDefinition = "TEXT") private String referenceAssetKeysJson;
    @Column(name = "status", nullable = false, length = 40) private String status;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getShotKey() { return shotKey; }
    public void setShotKey(String shotKey) { this.shotKey = shotKey; }
    public int getSceneIndex() { return sceneIndex; }
    public void setSceneIndex(int sceneIndex) { this.sceneIndex = sceneIndex; }
    public int getShotIndex() { return shotIndex; }
    public void setShotIndex(int shotIndex) { this.shotIndex = shotIndex; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public String getSourceExcerpt() { return sourceExcerpt; }
    public void setSourceExcerpt(String sourceExcerpt) { this.sourceExcerpt = sourceExcerpt; }
    public String getVisualGoal() { return visualGoal; }
    public void setVisualGoal(String visualGoal) { this.visualGoal = visualGoal; }
    public String getSpeaker() { return speaker; }
    public void setSpeaker(String speaker) { this.speaker = speaker; }
    public String getDialogue() { return dialogue; }
    public void setDialogue(String dialogue) { this.dialogue = dialogue; }
    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }
    public String getTextAnchorJson() { return textAnchorJson; }
    public void setTextAnchorJson(String textAnchorJson) { this.textAnchorJson = textAnchorJson; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getNegativePrompt() { return negativePrompt; }
    public void setNegativePrompt(String negativePrompt) { this.negativePrompt = negativePrompt; }
    public String getReferenceAssetKeysJson() { return referenceAssetKeysJson; }
    public void setReferenceAssetKeysJson(String referenceAssetKeysJson) { this.referenceAssetKeysJson = referenceAssetKeysJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
