package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "tb_image_asset", uniqueConstraints = {
        @UniqueConstraint(name = "uk_image_asset_run_type_key", columnNames = {"run_id", "asset_type", "asset_key"})
})
public class ImageAsset extends BaseEntity {
    @Column(name = "run_id", nullable = false, length = 64) private String runId;
    @Column(name = "asset_type", nullable = false, length = 40) private String assetType;
    @Column(name = "asset_key", nullable = false, length = 120) private String assetKey;
    @Column(name = "shot_key", length = 80) private String shotKey;
    @Column(name = "relative_path", nullable = false, length = 500) private String relativePath;
    @Column(name = "mime", nullable = false, length = 80) private String mime;
    @Column(name = "width", nullable = false) private int width;
    @Column(name = "height", nullable = false) private int height;
    @Column(name = "sha256", nullable = false, length = 64) private String sha256;
    @Column(name = "provider_id", length = 120) private String providerId;
    @Column(name = "provider_model", length = 180) private String providerModel;
    @Column(name = "provider_request_id", length = 180) private String providerRequestId;
    @Column(name = "prompt", columnDefinition = "TEXT") private String prompt;
    @Column(name = "negative_prompt", columnDefinition = "TEXT") private String negativePrompt;
    @Column(name = "metadata_json", columnDefinition = "TEXT") private String metadataJson;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getAssetType() { return assetType; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public String getAssetKey() { return assetKey; }
    public void setAssetKey(String assetKey) { this.assetKey = assetKey; }
    public String getShotKey() { return shotKey; }
    public void setShotKey(String shotKey) { this.shotKey = shotKey; }
    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }
    public String getMime() { return mime; }
    public void setMime(String mime) { this.mime = mime; }
    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }
    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getProviderModel() { return providerModel; }
    public void setProviderModel(String providerModel) { this.providerModel = providerModel; }
    public String getProviderRequestId() { return providerRequestId; }
    public void setProviderRequestId(String providerRequestId) { this.providerRequestId = providerRequestId; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getNegativePrompt() { return negativePrompt; }
    public void setNegativePrompt(String negativePrompt) { this.negativePrompt = negativePrompt; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
}
