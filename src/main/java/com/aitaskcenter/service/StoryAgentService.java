package com.aitaskcenter.service;

import com.aitaskcenter.config.StoryAgentCatalog;
import com.aitaskcenter.config.StoryAgentCatalog.NodeDefinition;
import com.aitaskcenter.dto.AiConfigRequest;
import com.aitaskcenter.dto.AiProviderConfigItem;
import com.aitaskcenter.dto.StoryAgentDtos.AgentUpdateRequest;
import com.aitaskcenter.dto.StoryAgentDtos.AgentView;
import com.aitaskcenter.dto.StoryAgentDtos.BudgetUpdateRequest;
import com.aitaskcenter.dto.StoryAgentDtos.BudgetView;
import com.aitaskcenter.dto.StoryAgentDtos.FlowView;
import com.aitaskcenter.dto.StoryAgentDtos.PromptVersionView;
import com.aitaskcenter.dto.StoryAgentDtos.StageView;
import com.aitaskcenter.model.StoryAgentConfig;
import com.aitaskcenter.model.StoryAgentPromptVersion;
import com.aitaskcenter.model.StoryFlowConfig;
import com.aitaskcenter.repository.StoryAgentConfigRepository;
import com.aitaskcenter.repository.StoryAgentPromptVersionRepository;
import com.aitaskcenter.repository.StoryFlowConfigRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class StoryAgentService {
    private static final String TEXT_GENERATION = "TEXT_GENERATION";

    private final StoryAgentConfigRepository configRepository;
    private final StoryAgentPromptVersionRepository versionRepository;
    private final StoryFlowConfigRepository flowRepository;
    private final AiConfigService aiConfigService;

    public StoryAgentService(
            StoryAgentConfigRepository configRepository,
            StoryAgentPromptVersionRepository versionRepository,
            StoryFlowConfigRepository flowRepository,
            AiConfigService aiConfigService) {
        this.configRepository = configRepository;
        this.versionRepository = versionRepository;
        this.flowRepository = flowRepository;
        this.aiConfigService = aiConfigService;
    }

    @Transactional
    public void initializeDefaults() {
        List<NodeDefinition> missingAgents = StoryAgentCatalog.agents().stream()
                .filter(definition -> configRepository.findByAgentKey(definition.key()).isEmpty())
                .toList();
        if (!missingAgents.isEmpty()) {
            AiConfigRequest providerConfig = aiConfigService.getProviders();
            List<AiProviderConfigItem> providers = providers(providerConfig);
            String activeProviderId = providerConfig == null ? "" : clean(providerConfig.getActive());
            for (NodeDefinition definition : missingAgents) {
                StoryAgentConfig config = new StoryAgentConfig();
                config.setAgentKey(definition.key());
                config.setName(definition.name());
                config.setRoleType(definition.roleType());
                config.setDescription(definition.description());
                config.setSystemPrompt(definition.defaultPrompt());
                config.setAiProviderId(selectProviderId(
                        definition.modelPreference(), providers, activeProviderId));
                config.setTemperature(definition.defaultTemperature());
                config.setEnabled(true);
                config.setPromptVersion(1);
                configRepository.save(config);
                versionRepository.save(snapshot(config));
            }
        }
        if (flowRepository.findByConfigKey(StoryFlowConfig.DEFAULT_CONFIG_KEY).isEmpty()) {
            flowRepository.save(StoryFlowConfig.defaults());
        }
    }

    @Transactional
    public FlowView getFlow() {
        initializeDefaults();
        Map<String, StoryAgentConfig> configsByKey = configRepository.findAllByOrderByAgentKeyAsc().stream()
                .collect(Collectors.toMap(
                        StoryAgentConfig::getAgentKey,
                        config -> config,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<StageView> stages = StoryAgentCatalog.stages().stream()
                .sorted(Comparator.comparingInt(StoryAgentCatalog.StageDefinition::order))
                .map(stage -> new StageView(
                        stage.key(),
                        stage.name(),
                        stage.note(),
                        stage.order(),
                        StoryAgentCatalog.nodes().stream()
                                .filter(node -> stage.key().equals(node.stageKey()))
                                .sorted(Comparator.comparingInt(NodeDefinition::order))
                                .map(node -> toView(node, configsByKey.get(node.key())))
                                .toList()))
                .toList();
        return new FlowView(stages, getBudget());
    }

    @Transactional
    public AgentView update(String key, AgentUpdateRequest request) {
        NodeDefinition definition = requireEditable(key);
        NormalizedAgentUpdate normalized = normalize(request);
        StoryAgentConfig current = configRepository.findByAgentKey(definition.key())
                .orElseThrow(() -> new IllegalArgumentException(
                        "故事 Agent 配置「" + definition.key() + "」不存在，请刷新后重试"));
        requireCurrentTimestamp(current, request.updatedAt());
        if (sameValues(current, normalized)) {
            return toView(definition, current);
        }
        current.setSystemPrompt(normalized.systemPrompt());
        current.setAiProviderId(normalized.aiProviderId());
        current.setTemperature(normalized.temperature());
        current.setEnabled(normalized.enabled());
        current.setPromptVersion(current.getPromptVersion() + 1);
        saveCurrentConfig(current);
        versionRepository.save(snapshot(current));
        return toView(definition, current);
    }

    @Transactional(readOnly = true)
    public List<PromptVersionView> versions(String key) {
        NodeDefinition definition = requireEditable(key);
        return versionRepository.findByAgentKeyOrderByVersionDesc(definition.key()).stream()
                .map(version -> new PromptVersionView(
                        version.getVersion(),
                        version.getSystemPrompt(),
                        version.getAiProviderId(),
                        version.getTemperature(),
                        version.isEnabled(),
                        version.getCreatedAt()))
                .toList();
    }

    @Transactional
    public AgentView restore(String key, int version, OffsetDateTime expectedUpdatedAt) {
        NodeDefinition definition = requireEditable(key);
        StoryAgentPromptVersion historical = versionRepository
                .findByAgentKeyAndVersion(definition.key(), version)
                .orElseThrow(() -> new IllegalArgumentException(
                        "故事 Agent「" + definition.key() + "」的提示词版本 " + version + " 不存在"));
        requireTextProvider(historical.getAiProviderId());
        StoryAgentConfig current = configRepository.findByAgentKey(definition.key())
                .orElseThrow(() -> new IllegalArgumentException(
                        "故事 Agent 配置「" + definition.key() + "」不存在，请刷新后重试"));
        requireCurrentTimestamp(current, expectedUpdatedAt);
        current.setSystemPrompt(historical.getSystemPrompt());
        current.setAiProviderId(historical.getAiProviderId());
        current.setTemperature(historical.getTemperature());
        current.setEnabled(historical.isEnabled());
        current.setPromptVersion(current.getPromptVersion() + 1);
        saveCurrentConfig(current);
        versionRepository.save(snapshot(current));
        return toView(definition, current);
    }

    @Transactional(readOnly = true)
    public BudgetView getBudget() {
        StoryFlowConfig config = flowRepository.findByConfigKey(StoryFlowConfig.DEFAULT_CONFIG_KEY)
                .orElseGet(StoryFlowConfig::defaults);
        return toView(config);
    }

    @Transactional
    public BudgetView updateBudget(BudgetUpdateRequest request) {
        validateBudget(request);
        StoryFlowConfig config = flowRepository.findByConfigKey(StoryFlowConfig.DEFAULT_CONFIG_KEY)
                .orElseGet(StoryFlowConfig::defaults);
        config.setMaxQualityRounds(request.maxQualityRounds());
        config.setMaxLocalRevisions(request.maxLocalRevisions());
        config.setMaxWriterRewrites(request.maxWriterRewrites());
        config.setMaxDirectorReturns(request.maxDirectorReturns());
        config.setMaxPitchReturns(request.maxPitchReturns());
        config.setMaxPlanReturns(request.maxPlanReturns());
        config.setMaxTotalTokens(request.maxTotalTokens());
        flowRepository.save(config);
        flowRepository.flush();
        return toView(config);
    }

    private NormalizedAgentUpdate normalize(AgentUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请提交故事 Agent 配置");
        }
        String systemPrompt = clean(request.systemPrompt());
        if (!StringUtils.hasText(systemPrompt)) {
            throw new IllegalArgumentException("系统提示词不能为空");
        }
        String providerId = clean(request.aiProviderId());
        if (!StringUtils.hasText(providerId)) {
            throw new IllegalArgumentException("请选择 AI 配置");
        }
        requireTextProvider(providerId);
        Double temperature = request.temperature();
        if (temperature == null || !Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("温度必须在 0 到 2 之间");
        }
        if (request.enabled() == null) {
            throw new IllegalArgumentException("请选择是否启用故事 Agent");
        }
        return new NormalizedAgentUpdate(systemPrompt, providerId, temperature, request.enabled());
    }

    private AiProviderConfigItem requireTextProvider(String requestedProviderId) {
        String providerId = clean(requestedProviderId);
        if (!StringUtils.hasText(providerId)) {
            throw new IllegalArgumentException("请选择 AI 配置");
        }
        AiProviderConfigItem provider = providers(aiConfigService.getProviders()).stream()
                .filter(item -> providerId.equals(clean(item.getId())))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("AI 配置「" + providerId + "」不存在"));
        if (!provider.isEnabled()) {
            throw new IllegalArgumentException("AI 配置「" + providerId + "」未启用");
        }
        if (!supportsTextGeneration(provider)) {
            throw new IllegalArgumentException("AI 配置「" + providerId + "」不支持文本生成");
        }
        return provider;
    }

    private void saveCurrentConfig(StoryAgentConfig config) {
        try {
            configRepository.save(config);
            configRepository.flush();
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new IllegalArgumentException("故事 Agent 配置已被更新，请刷新页面后重试", ex);
        }
    }

    private void requireCurrentTimestamp(StoryAgentConfig current, java.time.OffsetDateTime requestedUpdatedAt) {
        if (current.getUpdatedAt() == null) {
            return;
        }
        if (requestedUpdatedAt == null
                || !current.getUpdatedAt().toInstant().equals(requestedUpdatedAt.toInstant())) {
            throw new IllegalArgumentException("故事 Agent 配置已被更新，请刷新页面后重试");
        }
    }

    private boolean sameValues(StoryAgentConfig current, NormalizedAgentUpdate update) {
        return Objects.equals(current.getSystemPrompt(), update.systemPrompt())
                && Objects.equals(clean(current.getAiProviderId()), update.aiProviderId())
                && Double.compare(current.getTemperature(), update.temperature()) == 0
                && current.isEnabled() == update.enabled();
    }

    private NodeDefinition requireEditable(String key) {
        String normalizedKey = clean(key);
        if (!StringUtils.hasText(normalizedKey)) {
            throw new IllegalArgumentException("故事 Agent key 不能为空");
        }
        NodeDefinition definition;
        try {
            definition = StoryAgentCatalog.require(normalizedKey);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("故事流程节点「" + normalizedKey + "」不存在", ex);
        }
        if (!definition.editable()) {
            throw new IllegalArgumentException("故事流程节点「" + normalizedKey + "」不可编辑");
        }
        return definition;
    }

    private AgentView toView(NodeDefinition definition, StoryAgentConfig config) {
        boolean editable = definition.editable();
        return new AgentView(
                definition.key(),
                definition.name(),
                definition.nodeKind(),
                definition.roleType(),
                definition.stageKey(),
                definition.order(),
                definition.parallelGroup(),
                definition.description(),
                definition.variables(),
                definition.upstream(),
                definition.downstream(),
                editable && config != null ? config.getSystemPrompt() : null,
                editable && config != null ? config.getAiProviderId() : null,
                editable && config != null ? config.getTemperature() : null,
                editable && config != null ? config.isEnabled() : null,
                editable && config != null ? config.getPromptVersion() : null,
                editable && config != null ? config.getUpdatedAt() : null,
                editable);
    }

    private BudgetView toView(StoryFlowConfig config) {
        return new BudgetView(
                config.getMaxQualityRounds(),
                config.getMaxLocalRevisions(),
                config.getMaxWriterRewrites(),
                config.getMaxDirectorReturns(),
                config.getMaxPitchReturns(),
                config.getMaxPlanReturns(),
                config.getMaxTotalTokens(),
                config.getUpdatedAt());
    }

    private StoryAgentPromptVersion snapshot(StoryAgentConfig config) {
        StoryAgentPromptVersion version = new StoryAgentPromptVersion();
        version.setAgentKey(config.getAgentKey());
        version.setVersion(config.getPromptVersion());
        version.setSystemPrompt(config.getSystemPrompt());
        version.setAiProviderId(config.getAiProviderId());
        version.setTemperature(config.getTemperature());
        version.setEnabled(config.isEnabled());
        return version;
    }

    private void validateBudget(BudgetUpdateRequest request) {
        if (request == null
                || request.maxQualityRounds() == null
                || request.maxLocalRevisions() == null
                || request.maxWriterRewrites() == null
                || request.maxDirectorReturns() == null
                || request.maxPitchReturns() == null
                || request.maxPlanReturns() == null
                || request.maxTotalTokens() == null) {
            throw new IllegalArgumentException("故事流程预算字段不能为空");
        }
        if (request.maxQualityRounds() < 1 || request.maxQualityRounds() > 20) {
            throw new IllegalArgumentException("质量轮次必须在 1 到 20 之间");
        }
        validateLocalBudget("定向修订次数", request.maxLocalRevisions());
        validateLocalBudget("作家重写次数", request.maxWriterRewrites());
        validateLocalBudget("导演回退次数", request.maxDirectorReturns());
        validateLocalBudget("创意回退次数", request.maxPitchReturns());
        validateLocalBudget("策划回退次数", request.maxPlanReturns());
        if (request.maxTotalTokens() < 1_000 || request.maxTotalTokens() > 10_000_000) {
            throw new IllegalArgumentException("总 Token 预算必须在 1000 到 10000000 之间");
        }
    }

    private void validateLocalBudget(String name, int value) {
        if (value < 0 || value > 20) {
            throw new IllegalArgumentException(name + "必须在 0 到 20 之间");
        }
    }

    private String selectProviderId(
            String modelPreference,
            List<AiProviderConfigItem> providers,
            String activeProviderId) {
        List<AiProviderConfigItem> validProviders = providers.stream()
                .filter(this::isValidTextProvider)
                .toList();
        Optional<AiProviderConfigItem> preferred = validProviders.stream()
                .filter(provider -> matchesPreference(provider, modelPreference))
                .findFirst();
        if (preferred.isPresent()) {
            return clean(preferred.get().getId());
        }
        Optional<AiProviderConfigItem> active = validProviders.stream()
                .filter(provider -> Objects.equals(clean(provider.getId()), activeProviderId))
                .findFirst();
        return active.or(() -> validProviders.stream().findFirst())
                .map(provider -> clean(provider.getId()))
                .orElse("");
    }

    private boolean matchesPreference(AiProviderConfigItem provider, String preference) {
        String normalizedPreference = normalizeCompact(preference);
        List<String> fields = List.of(
                clean(provider.getId()),
                clean(provider.getLabel()),
                clean(provider.getModel()));
        if (fields.stream().map(this::normalizeCompact).anyMatch(normalizedPreference::equals)) {
            return true;
        }
        String searchableText = String.join(" ", fields).toLowerCase(Locale.ROOT);
        Set<String> tokens = fields.stream()
                .flatMap(field -> tokenize(field).stream())
                .collect(Collectors.toSet());
        if ("flashmedium".equals(normalizedPreference)) {
            return searchableText.contains("flash") && searchableText.contains("medium");
        }
        if ("flashhigh".equals(normalizedPreference)) {
            return searchableText.contains("flash") && searchableText.contains("high");
        }
        if ("pro".equals(normalizedPreference)) {
            return tokens.contains("pro");
        }
        return tokenize(preference).stream().allMatch(tokens::contains);
    }

    private List<String> tokenize(String value) {
        String normalized = clean(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return normalized.isEmpty() ? List.of() : List.of(normalized.split("\\s+"));
    }

    private String normalizeCompact(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private List<AiProviderConfigItem> providers(AiConfigRequest request) {
        if (request == null || request.getProviders() == null) {
            return List.of();
        }
        return new ArrayList<>(request.getProviders());
    }

    private boolean isValidTextProvider(AiProviderConfigItem provider) {
        return provider != null
                && StringUtils.hasText(provider.getId())
                && provider.isEnabled()
                && supportsTextGeneration(provider);
    }

    private boolean supportsTextGeneration(AiProviderConfigItem provider) {
        return provider.getCapabilities() != null
                && provider.getCapabilities().stream()
                        .filter(Objects::nonNull)
                        .anyMatch(capability -> TEXT_GENERATION.equalsIgnoreCase(capability.trim()));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private record NormalizedAgentUpdate(
            String systemPrompt,
            String aiProviderId,
            double temperature,
            boolean enabled) {
    }
}
