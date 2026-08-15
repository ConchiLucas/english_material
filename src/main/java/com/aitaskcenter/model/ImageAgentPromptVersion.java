package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(
        name = "tb_image_agent_prompt_version",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_image_agent_prompt_version_agent_prompt_version",
                columnNames = {"agent_key", "prompt_version"}))
public class ImageAgentPromptVersion extends BaseEntity {
    @Column(name = "agent_key", nullable = false, updatable = false)
    private String agentKey;

    @Column(name = "prompt_version", nullable = false, updatable = false)
    private int promptVersion;

    @Column(name = "system_prompt", nullable = false, updatable = false, columnDefinition = "text")
    private String systemPrompt;

    @Column(name = "ai_provider_id", updatable = false)
    private String aiProviderId;

    @Column(name = "temperature", nullable = false, updatable = false)
    private double temperature;

    @Column(name = "enabled", nullable = false, updatable = false)
    private boolean enabled;

    public String getAgentKey() { return agentKey; }
    public void setAgentKey(String agentKey) { this.agentKey = agentKey; }
    public int getPromptVersion() { return promptVersion; }
    public void setPromptVersion(int promptVersion) { this.promptVersion = promptVersion; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getAiProviderId() { return aiProviderId; }
    public void setAiProviderId(String aiProviderId) { this.aiProviderId = aiProviderId; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
