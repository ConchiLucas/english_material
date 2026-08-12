package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tb_story_flow_config")
public class StoryFlowConfig extends BaseEntity {
    public static final String DEFAULT_CONFIG_KEY = "default-story-flow";

    @Column(name = "config_key", nullable = false, unique = true)
    private String configKey;

    @Column(name = "max_quality_rounds", nullable = false)
    private int maxQualityRounds;

    @Column(name = "max_local_revisions", nullable = false)
    private int maxLocalRevisions;

    @Column(name = "max_writer_rewrites", nullable = false)
    private int maxWriterRewrites;

    @Column(name = "max_director_returns", nullable = false)
    private int maxDirectorReturns;

    @Column(name = "max_pitch_returns", nullable = false)
    private int maxPitchReturns;

    @Column(name = "max_plan_returns", nullable = false)
    private int maxPlanReturns;

    @Column(name = "max_total_tokens", nullable = false)
    private int maxTotalTokens;

    public static StoryFlowConfig defaults() {
        StoryFlowConfig config = new StoryFlowConfig();
        config.setConfigKey(DEFAULT_CONFIG_KEY);
        config.setMaxQualityRounds(3);
        config.setMaxLocalRevisions(2);
        config.setMaxWriterRewrites(1);
        config.setMaxDirectorReturns(1);
        config.setMaxPitchReturns(1);
        config.setMaxPlanReturns(1);
        config.setMaxTotalTokens(120_000);
        return config;
    }

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public int getMaxQualityRounds() {
        return maxQualityRounds;
    }

    public void setMaxQualityRounds(int maxQualityRounds) {
        this.maxQualityRounds = maxQualityRounds;
    }

    public int getMaxLocalRevisions() {
        return maxLocalRevisions;
    }

    public void setMaxLocalRevisions(int maxLocalRevisions) {
        this.maxLocalRevisions = maxLocalRevisions;
    }

    public int getMaxWriterRewrites() {
        return maxWriterRewrites;
    }

    public void setMaxWriterRewrites(int maxWriterRewrites) {
        this.maxWriterRewrites = maxWriterRewrites;
    }

    public int getMaxDirectorReturns() {
        return maxDirectorReturns;
    }

    public void setMaxDirectorReturns(int maxDirectorReturns) {
        this.maxDirectorReturns = maxDirectorReturns;
    }

    public int getMaxPitchReturns() {
        return maxPitchReturns;
    }

    public void setMaxPitchReturns(int maxPitchReturns) {
        this.maxPitchReturns = maxPitchReturns;
    }

    public int getMaxPlanReturns() {
        return maxPlanReturns;
    }

    public void setMaxPlanReturns(int maxPlanReturns) {
        this.maxPlanReturns = maxPlanReturns;
    }

    public int getMaxTotalTokens() {
        return maxTotalTokens;
    }

    public void setMaxTotalTokens(int maxTotalTokens) {
        this.maxTotalTokens = maxTotalTokens;
    }
}
