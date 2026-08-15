package com.aitaskcenter.model;

import com.aitaskcenter.config.ImageAgentCatalog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "tb_image_flow_config")
public class ImageFlowConfig extends BaseEntity {
    public static final String DEFAULT_FLOW_KEY = "default";

    @Column(name = "flow_key", nullable = false, unique = true)
    private String flowKey;

    @Column(name = "image_provider_id")
    private String imageProviderId;

    @Column(name = "width", nullable = false)
    private int width;

    @Column(name = "height", nullable = false)
    private int height;

    @Column(name = "max_shots_per_scene", nullable = false)
    private int maxShotsPerScene;

    @Column(name = "max_shots_per_story", nullable = false)
    private int maxShotsPerStory;

    @Version
    private long lockVersion;

    public static ImageFlowConfig defaults() {
        ImageFlowConfig config = new ImageFlowConfig();
        config.setFlowKey(DEFAULT_FLOW_KEY);
        config.setWidth(ImageAgentCatalog.DEFAULT_WIDTH);
        config.setHeight(ImageAgentCatalog.DEFAULT_HEIGHT);
        config.setMaxShotsPerScene(ImageAgentCatalog.DEFAULT_MAX_SHOTS_PER_SCENE);
        config.setMaxShotsPerStory(ImageAgentCatalog.DEFAULT_MAX_SHOTS_PER_STORY);
        return config;
    }

    public String getFlowKey() { return flowKey; }
    public void setFlowKey(String flowKey) { this.flowKey = flowKey; }
    public String getImageProviderId() { return imageProviderId; }
    public void setImageProviderId(String imageProviderId) { this.imageProviderId = imageProviderId; }
    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }
    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
    public int getMaxShotsPerScene() { return maxShotsPerScene; }
    public void setMaxShotsPerScene(int maxShotsPerScene) { this.maxShotsPerScene = maxShotsPerScene; }
    public int getMaxShotsPerStory() { return maxShotsPerStory; }
    public void setMaxShotsPerStory(int maxShotsPerStory) { this.maxShotsPerStory = maxShotsPerStory; }
}
