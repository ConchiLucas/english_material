package com.aitaskcenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "tb_image_style_preset")
public class ImageStylePreset extends BaseEntity {
    @Column(name = "preset_key", nullable = false, unique = true)
    private String presetKey;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "positive_prompt", nullable = false, columnDefinition = "text")
    private String positivePrompt;

    @Column(name = "negative_prompt", nullable = false, columnDefinition = "text")
    private String negativePrompt;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    @Version
    private long lockVersion;

    public String getPresetKey() { return presetKey; }
    public void setPresetKey(String presetKey) { this.presetKey = presetKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPositivePrompt() { return positivePrompt; }
    public void setPositivePrompt(String positivePrompt) { this.positivePrompt = positivePrompt; }
    public String getNegativePrompt() { return negativePrompt; }
    public void setNegativePrompt(String negativePrompt) { this.negativePrompt = negativePrompt; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isBuiltIn() { return builtIn; }
    public void setBuiltIn(boolean builtIn) { this.builtIn = builtIn; }
}
