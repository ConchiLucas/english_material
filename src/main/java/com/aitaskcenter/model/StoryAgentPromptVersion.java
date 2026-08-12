package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "tb_story_agent_prompt_version",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_story_agent_prompt_version_agent_version",
                columnNames = {"agent_key", "version"}))
public class StoryAgentPromptVersion extends BaseEntity {
    @Column(name = "agent_key", nullable = false)
    private String agentKey;

    @Column(nullable = false)
    private int version;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "text")
    private String systemPrompt;

    @Column(name = "ai_provider_id")
    private String aiProviderId;

    @Column(nullable = false)
    private double temperature;

    @Column(nullable = false)
    private boolean enabled;

    public String getAgentKey() {
        return agentKey;
    }

    public void setAgentKey(String agentKey) {
        this.agentKey = agentKey;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getAiProviderId() {
        return aiProviderId;
    }

    public void setAiProviderId(String aiProviderId) {
        this.aiProviderId = aiProviderId;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
