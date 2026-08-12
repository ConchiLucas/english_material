package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tb_story_agent_config")
public class StoryAgentConfig extends BaseEntity {
    @Column(name = "agent_key", nullable = false, unique = true)
    private String agentKey;

    @Column(nullable = false)
    private String name;

    @Column(name = "role_type", nullable = false)
    private String roleType;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "text")
    private String systemPrompt;

    @Column(name = "ai_provider_id")
    private String aiProviderId;

    @Column(nullable = false)
    private double temperature;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "prompt_version", nullable = false)
    private int promptVersion;

    public String getAgentKey() {
        return agentKey;
    }

    public void setAgentKey(String agentKey) {
        this.agentKey = agentKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRoleType() {
        return roleType;
    }

    public void setRoleType(String roleType) {
        this.roleType = roleType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public int getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(int promptVersion) {
        this.promptVersion = promptVersion;
    }
}
