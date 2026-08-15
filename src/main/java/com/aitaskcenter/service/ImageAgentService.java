package com.aitaskcenter.service;

import com.aitaskcenter.config.ImageAgentCatalog;
import com.aitaskcenter.config.ImageAgentCatalog.NodeDefinition;
import com.aitaskcenter.dto.AiConfigRequest;
import com.aitaskcenter.dto.AiProviderConfigItem;
import com.aitaskcenter.dto.ImageAgentDtos.AgentUpdateRequest;
import com.aitaskcenter.dto.ImageAgentDtos.AgentView;
import com.aitaskcenter.dto.ImageAgentDtos.FlowConfigView;
import com.aitaskcenter.dto.ImageAgentDtos.FlowUpdateRequest;
import com.aitaskcenter.dto.ImageAgentDtos.FlowView;
import com.aitaskcenter.dto.ImageAgentDtos.PromptVersionView;
import com.aitaskcenter.dto.ImageAgentDtos.StageView;
import com.aitaskcenter.dto.ImageAgentDtos.StyleCreateRequest;
import com.aitaskcenter.dto.ImageAgentDtos.StylePresetView;
import com.aitaskcenter.dto.ImageAgentDtos.StyleUpdateRequest;
import com.aitaskcenter.model.ImageAgentConfig;
import com.aitaskcenter.model.ImageAgentPromptVersion;
import com.aitaskcenter.model.ImageFlowConfig;
import com.aitaskcenter.model.ImageStylePreset;
import com.aitaskcenter.repository.ImageAgentConfigRepository;
import com.aitaskcenter.repository.ImageAgentPromptVersionRepository;
import com.aitaskcenter.repository.ImageFlowConfigRepository;
import com.aitaskcenter.repository.ImageStylePresetRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ImageAgentService {
    private static final int MAX_AGENT_PROMPT_LENGTH = 20_000;
    private static final int MAX_STYLE_NAME_LENGTH = 120;
    private static final int MAX_STYLE_PROMPT_LENGTH = 20_000;
    private static final int MAX_STYLE_DESCRIPTION_LENGTH = 2_000;
    private static final String STALE_MESSAGE = "图片 Agent 配置已被更新，请刷新页面后重试";
    private static final String IMAGE_PROVIDER_MESSAGE = "请选择支持图片生成和多参考图的 OpenAI-compatible Provider";

    private final ImageAgentConfigRepository agentRepository;
    private final ImageAgentPromptVersionRepository versionRepository;
    private final ImageFlowConfigRepository flowRepository;
    private final ImageStylePresetRepository styleRepository;
    private final AiConfigService aiConfigService;

    public ImageAgentService(ImageAgentConfigRepository agentRepository,
                             ImageAgentPromptVersionRepository versionRepository,
                             ImageFlowConfigRepository flowRepository,
                             ImageStylePresetRepository styleRepository,
                             AiConfigService aiConfigService) {
        this.agentRepository = agentRepository;
        this.versionRepository = versionRepository;
        this.flowRepository = flowRepository;
        this.styleRepository = styleRepository;
        this.aiConfigService = aiConfigService;
    }

    @Transactional(readOnly = true)
    public FlowView getFlow() {
        Map<String, ImageAgentConfig> configured = agentRepository.findAllByOrderByAgentKeyAsc().stream()
                .collect(Collectors.toMap(ImageAgentConfig::getAgentKey, value -> value, (first, ignored) -> first));
        List<StageView> stages = ImageAgentCatalog.stages().stream()
                .sorted(Comparator.comparingInt(ImageAgentCatalog.StageDefinition::order))
                .map(stage -> new StageView(stage.key(), stage.name(), stage.note(), stage.order(), stage.nodes().stream()
                        .sorted(Comparator.comparingInt(NodeDefinition::order))
                        .map(node -> toView(stage.key(), node, configured.get(node.key())))
                        .toList()))
                .toList();
        ImageFlowConfig flow = flowRepository.findByFlowKey(ImageFlowConfig.DEFAULT_FLOW_KEY)
                .orElseGet(ImageFlowConfig::defaults);
        return new FlowView(stages, toView(flow), styles());
    }

    @Transactional
    public AgentView updateAgent(String key, AgentUpdateRequest request) {
        NodeDefinition definition = requireEditable(key);
        ImageAgentConfig current = agentRepository.findByAgentKey(definition.key())
                .orElseThrow(() -> new IllegalArgumentException("图片 Agent 配置「" + definition.key() + "」不存在，请刷新后重试"));
        requireCurrentTimestamp(current.getUpdatedAt(), request == null ? null : request.updatedAt(), STALE_MESSAGE);
        AgentUpdate normalized = normalizeAgent(request);
        if (sameAgent(current, normalized)) return toView(stageKey(definition.key()), definition, current);
        current.setSystemPrompt(normalized.prompt());
        current.setAiProviderId(normalized.providerId());
        current.setTemperature(normalized.temperature());
        current.setEnabled(normalized.enabled());
        current.setPromptVersion(current.getPromptVersion() + 1);
        saveAndFlush(agentRepository, current, STALE_MESSAGE);
        versionRepository.save(snapshot(current));
        return toView(stageKey(definition.key()), definition, current);
    }

    @Transactional(readOnly = true)
    public List<PromptVersionView> versions(String key) {
        NodeDefinition definition = requireEditable(key);
        return versionRepository.findByAgentKeyOrderByPromptVersionDesc(definition.key()).stream()
                .map(value -> new PromptVersionView(value.getPromptVersion(), value.getSystemPrompt(),
                        value.getAiProviderId(), value.getTemperature(), value.isEnabled(), value.getCreatedAt()))
                .toList();
    }

    @Transactional
    public AgentView restoreVersion(String key, int version, OffsetDateTime updatedAt) {
        NodeDefinition definition = requireEditable(key);
        ImageAgentConfig current = agentRepository.findByAgentKey(definition.key())
                .orElseThrow(() -> new IllegalArgumentException("图片 Agent 配置「" + definition.key() + "」不存在，请刷新后重试"));
        requireCurrentTimestamp(current.getUpdatedAt(), updatedAt, STALE_MESSAGE);
        ImageAgentPromptVersion historical = versionRepository.findByAgentKeyAndPromptVersion(definition.key(), version)
                .orElseThrow(() -> new IllegalArgumentException("图片 Agent「" + definition.key() + "」的提示词版本 " + version + " 不存在"));
        requireTextProvider(historical.getAiProviderId());
        current.setSystemPrompt(historical.getSystemPrompt());
        current.setAiProviderId(historical.getAiProviderId());
        current.setTemperature(historical.getTemperature());
        current.setEnabled(historical.isEnabled());
        current.setPromptVersion(current.getPromptVersion() + 1);
        saveAndFlush(agentRepository, current, STALE_MESSAGE);
        versionRepository.save(snapshot(current));
        return toView(stageKey(definition.key()), definition, current);
    }

    @Transactional
    public FlowConfigView updateFlow(FlowUpdateRequest request) {
        ImageFlowConfig current = flowRepository.findByFlowKey(ImageFlowConfig.DEFAULT_FLOW_KEY)
                .orElseThrow(() -> new IllegalArgumentException("图片流程配置不存在，请刷新后重试"));
        requireCurrentTimestamp(current.getUpdatedAt(), request == null ? null : request.updatedAt(), STALE_MESSAGE);
        validateFixedFlow(request);
        requireImageProvider(request.imageProviderId());
        current.setImageProviderId(clean(request.imageProviderId()));
        current.setWidth(request.width());
        current.setHeight(request.height());
        current.setMaxShotsPerScene(request.maxShotsPerScene());
        current.setMaxShotsPerStory(request.maxShotsPerStory());
        saveAndFlush(flowRepository, current, STALE_MESSAGE);
        return toView(current);
    }

    @Transactional(readOnly = true)
    public List<StylePresetView> styles() {
        return styleRepository.findAllByOrderByBuiltInDescNameAsc().stream().map(this::toView).toList();
    }

    @Transactional
    public StylePresetView createStyle(StyleCreateRequest request) {
        NormalizedStyle normalized = normalizeCreateStyle(request);
        ImageStylePreset preset = new ImageStylePreset();
        preset.setPresetKey(nextStyleKey());
        apply(preset, normalized);
        preset.setBuiltIn(false);
        styleRepository.save(preset);
        styleRepository.flush();
        return toView(preset);
    }

    @Transactional
    public StylePresetView updateStyle(long id, StyleUpdateRequest request) {
        ImageStylePreset preset = styleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("画风预设不存在，请刷新后重试"));
        requireCurrentTimestamp(preset.getUpdatedAt(), request == null ? null : request.updatedAt(), "画风预设已被更新，请刷新页面后重试");
        NormalizedStyle normalized = normalizeUpdateStyle(request);
        apply(preset, normalized);
        saveAndFlush(styleRepository, preset, "画风预设已被更新，请刷新页面后重试");
        return toView(preset);
    }

    @Transactional
    public void initializeDefaults() {
        List<NodeDefinition> missing = ImageAgentCatalog.agents().stream()
                .filter(definition -> agentRepository.findByAgentKey(definition.key()).isEmpty()).toList();
        if (!missing.isEmpty()) {
            AiConfigRequest providerConfig = aiConfigService.getProviders();
            List<AiProviderConfigItem> available = providers(providerConfig);
            String active = providerConfig == null ? "" : clean(providerConfig.getActive());
            for (NodeDefinition definition : missing) {
                ImageAgentConfig config = new ImageAgentConfig();
                config.setAgentKey(definition.key()); config.setName(definition.name()); config.setRoleType(definition.roleType());
                config.setDescription(definition.description()); config.setSystemPrompt(definition.defaultPrompt());
                config.setAiProviderId(selectTextProvider(definition.modelPreference(), available, active));
                config.setTemperature(definition.defaultTemperature()); config.setEnabled(true); config.setPromptVersion(1);
                agentRepository.save(config); versionRepository.save(snapshot(config));
            }
        }
        if (flowRepository.findByFlowKey(ImageFlowConfig.DEFAULT_FLOW_KEY).isEmpty()) {
            ImageFlowConfig flow = ImageFlowConfig.defaults();
            flow.setImageProviderId(selectImageProvider(providers(aiConfigService.getProviders())));
            flowRepository.save(flow);
        }
        addBuiltInStyle("watercolor-storybook", "水彩绘本", "soft watercolor children's storybook, warm natural light, expressive characters", "text, letters, subtitles, watermark, logo", "温暖柔和的水彩儿童绘本画风");
        addBuiltInStyle("paper-cut-collage", "纸艺拼贴", "layered paper-cut collage, bright friendly shapes, tactile paper texture", "text, letters, subtitles, watermark, logo", "明快有层次的纸艺拼贴画风");
    }

    private void addBuiltInStyle(String key, String name, String positive, String negative, String description) {
        if (styleRepository.findByPresetKey(key).isPresent()) return;
        ImageStylePreset preset = new ImageStylePreset(); preset.setPresetKey(key); preset.setName(name);
        preset.setPositivePrompt(positive); preset.setNegativePrompt(negative); preset.setDescription(description);
        preset.setEnabled(true); preset.setBuiltIn(true); styleRepository.save(preset);
    }

    private AgentUpdate normalizeAgent(AgentUpdateRequest request) {
        if (request == null) throw new IllegalArgumentException("请提交图片 Agent 配置");
        String prompt = limited(request.systemPrompt(), "系统提示词不能为空", MAX_AGENT_PROMPT_LENGTH);
        String provider = clean(request.aiProviderId());
        if (!StringUtils.hasText(provider)) throw new IllegalArgumentException("请选择 AI 配置");
        requireTextProvider(provider);
        if (request.temperature() == null || !Double.isFinite(request.temperature()) || request.temperature() < 0 || request.temperature() > 2)
            throw new IllegalArgumentException("温度必须在 0 到 2 之间");
        if (request.enabled() == null) throw new IllegalArgumentException("请选择是否启用图片 Agent");
        return new AgentUpdate(prompt, provider, request.temperature(), request.enabled());
    }

    private void validateFixedFlow(FlowUpdateRequest request) {
        if (request == null || request.width() == null || request.height() == null || request.maxShotsPerScene() == null || request.maxShotsPerStory() == null)
            throw new IllegalArgumentException("图片流程配置字段不能为空");
        if (request.width() != ImageAgentCatalog.DEFAULT_WIDTH || request.height() != ImageAgentCatalog.DEFAULT_HEIGHT
                || request.maxShotsPerScene() != ImageAgentCatalog.DEFAULT_MAX_SHOTS_PER_SCENE
                || request.maxShotsPerStory() != ImageAgentCatalog.DEFAULT_MAX_SHOTS_PER_STORY)
            throw new IllegalArgumentException("图片规格固定为 1536×864、每场景 5 镜、全篇 20 镜，不允许修改");
    }

    private NormalizedStyle normalizeCreateStyle(StyleCreateRequest request) {
        if (request == null) throw new IllegalArgumentException("请提交画风预设");
        if (request.enabled() == null) throw new IllegalArgumentException("请选择是否启用画风预设");
        return normalizedStyle(request.name(), request.positivePrompt(), request.negativePrompt(), request.description(), request.enabled());
    }
    private NormalizedStyle normalizeUpdateStyle(StyleUpdateRequest request) {
        if (request == null) throw new IllegalArgumentException("请提交画风预设");
        if (request.enabled() == null) throw new IllegalArgumentException("请选择是否启用画风预设");
        return normalizedStyle(request.name(), request.positivePrompt(), request.negativePrompt(), request.description(), request.enabled());
    }
    private NormalizedStyle normalizedStyle(String name, String positive, String negative, String description, boolean enabled) {
        return new NormalizedStyle(limited(name, "画风名称不能为空", MAX_STYLE_NAME_LENGTH),
                limited(positive, "正向提示词不能为空", MAX_STYLE_PROMPT_LENGTH),
                limited(negative, "负向提示词不能为空", MAX_STYLE_PROMPT_LENGTH),
                limited(description, "画风说明不能为空", MAX_STYLE_DESCRIPTION_LENGTH), enabled);
    }
    private String nextStyleKey() { return "custom-" + UUID.randomUUID(); }
    private void apply(ImageStylePreset preset, NormalizedStyle style) { preset.setName(style.name()); preset.setPositivePrompt(style.positive()); preset.setNegativePrompt(style.negative()); preset.setDescription(style.description()); preset.setEnabled(style.enabled()); }

    private NodeDefinition requireEditable(String key) {
        String normalized = clean(key); if (!StringUtils.hasText(normalized)) throw new IllegalArgumentException("图片 Agent key 不能为空");
        NodeDefinition definition;
        try { definition = ImageAgentCatalog.require(normalized); } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("图片流程节点「" + normalized + "」不存在", ex); }
        if (!definition.editable()) throw new IllegalArgumentException("图片流程节点「" + normalized + "」不可编辑"); return definition;
    }
    private void requireTextProvider(String id) {
        AiProviderConfigItem provider = findProvider(id).orElseThrow(() -> new IllegalArgumentException("AI 配置「" + clean(id) + "」不存在"));
        if (!provider.isEnabled()) throw new IllegalArgumentException("AI 配置「" + clean(id) + "」未启用");
        if (!supports(provider, "TEXT_GENERATION")) throw new IllegalArgumentException("AI 配置「" + clean(id) + "」不支持文本生成");
    }
    private void requireImageProvider(String id) {
        AiProviderConfigItem provider = findProvider(id).filter(this::isExecutableImageProvider)
                .orElseThrow(() -> new IllegalArgumentException(IMAGE_PROVIDER_MESSAGE));
    }
    private Optional<AiProviderConfigItem> findProvider(String id) { String expected = clean(id); return providers(aiConfigService.getProviders()).stream().filter(value -> expected.equals(clean(value.getId()))).findFirst(); }
    private boolean supports(AiProviderConfigItem provider, String capability) { return provider != null && provider.getCapabilities() != null && provider.getCapabilities().stream().filter(Objects::nonNull).anyMatch(value -> capability.equalsIgnoreCase(value.trim())); }
    private List<AiProviderConfigItem> providers(AiConfigRequest request) { return request == null || request.getProviders() == null ? List.of() : new ArrayList<>(request.getProviders()); }
    private String selectTextProvider(String preference, List<AiProviderConfigItem> values, String active) { List<AiProviderConfigItem> valid = values.stream().filter(p -> p != null && p.isEnabled() && supports(p, "TEXT_GENERATION") && StringUtils.hasText(p.getId())).toList(); return valid.stream().filter(p -> matchesPreference(p, preference)).findFirst().or(() -> valid.stream().filter(p -> clean(p.getId()).equals(active)).findFirst()).or(() -> valid.stream().findFirst()).map(p -> clean(p.getId())).orElse(""); }
    private String selectImageProvider(List<AiProviderConfigItem> values) { return values.stream().filter(this::isExecutableImageProvider).filter(p -> StringUtils.hasText(p.getId())).findFirst().map(p -> clean(p.getId())).orElse(null); }
    private boolean isExecutableImageProvider(AiProviderConfigItem provider) {
        return provider != null
                && "openai-compatible".equals(clean(provider.getType()).toLowerCase(Locale.ROOT))
                && provider.isEnabled()
                && supports(provider, "IMAGE_GENERATION")
                && supports(provider, "IMAGE_REFERENCE");
    }
    private boolean matchesPreference(AiProviderConfigItem provider, String preference) {
        String normalizedPreference = normalizeCompact(preference);
        List<String> fields = List.of(clean(provider.getId()), clean(provider.getLabel()), clean(provider.getModel()));
        if (fields.stream().map(this::normalizeCompact).anyMatch(normalizedPreference::equals)) return true;
        Set<String> tokens = fields.stream().flatMap(value -> tokenize(value).stream()).collect(Collectors.toSet());
        if ("pro".equals(normalizedPreference)) return tokens.contains("pro");
        return tokenize(preference).stream().allMatch(tokens::contains);
    }
    private List<String> tokenize(String value) {
        String normalized = clean(value).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return normalized.isEmpty() ? List.of() : List.of(normalized.split("\\s+"));
    }
    private String normalizeCompact(String value) { return clean(value).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }
    private boolean sameAgent(ImageAgentConfig current, AgentUpdate update) { return Objects.equals(current.getSystemPrompt(), update.prompt()) && Objects.equals(clean(current.getAiProviderId()), update.providerId()) && Double.compare(current.getTemperature(), update.temperature()) == 0 && current.isEnabled() == update.enabled(); }
    private ImageAgentPromptVersion snapshot(ImageAgentConfig config) { ImageAgentPromptVersion snapshot = new ImageAgentPromptVersion(); snapshot.setAgentKey(config.getAgentKey()); snapshot.setPromptVersion(config.getPromptVersion()); snapshot.setSystemPrompt(config.getSystemPrompt()); snapshot.setAiProviderId(config.getAiProviderId()); snapshot.setTemperature(config.getTemperature()); snapshot.setEnabled(config.isEnabled()); return snapshot; }
    private AgentView toView(String stageKey, NodeDefinition node, ImageAgentConfig config) { boolean editable = node.editable(); return new AgentView(node.key(), node.name(), node.nodeKind(), node.roleType(), stageKey, node.order(), node.parallelGroup(), node.description(), node.variables(), editable && config != null ? config.getSystemPrompt() : null, editable && config != null ? config.getAiProviderId() : null, editable && config != null ? config.getTemperature() : null, editable && config != null ? config.isEnabled() : null, editable && config != null ? config.getPromptVersion() : null, editable && config != null ? config.getUpdatedAt() : null, editable); }
    private String stageKey(String nodeKey) { return ImageAgentCatalog.stages().stream().filter(stage -> stage.nodes().stream().anyMatch(node -> node.key().equals(nodeKey))).findFirst().map(ImageAgentCatalog.StageDefinition::key).orElse(""); }
    private FlowConfigView toView(ImageFlowConfig value) { return new FlowConfigView(value.getImageProviderId(), value.getWidth(), value.getHeight(), value.getMaxShotsPerScene(), value.getMaxShotsPerStory(), value.getUpdatedAt()); }
    private StylePresetView toView(ImageStylePreset value) { return new StylePresetView(value.getId(), value.getPresetKey(), value.getName(), value.getPositivePrompt(), value.getNegativePrompt(), value.getDescription(), value.isEnabled(), value.isBuiltIn(), value.getUpdatedAt()); }
    private <T> void saveAndFlush(org.springframework.data.jpa.repository.JpaRepository<T, ?> repository, T entity, String message) { try { repository.save(entity); repository.flush(); } catch (ObjectOptimisticLockingFailureException ex) { throw new IllegalArgumentException(message, ex); } }
    private void requireCurrentTimestamp(OffsetDateTime actual, OffsetDateTime expected, String message) { if (actual != null && (expected == null || !actual.toInstant().equals(expected.toInstant()))) throw new IllegalArgumentException(message); }
    private static String limited(String input, String blankMessage, int limit) { String value = clean(input); if (!StringUtils.hasText(value)) throw new IllegalArgumentException(blankMessage); if (value.length() > limit) throw new IllegalArgumentException("内容长度不能超过 " + limit + " 个字符"); return value; }
    private static String clean(String input) { return input == null ? "" : input.trim(); }
    private record AgentUpdate(String prompt, String providerId, double temperature, boolean enabled) { }
    private record NormalizedStyle(String name, String positive, String negative, String description, boolean enabled) { }
}
