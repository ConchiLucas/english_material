package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aitaskcenter.config.StoryAgentCatalog;
import com.aitaskcenter.dto.AiConfigRequest;
import com.aitaskcenter.dto.AiProviderConfigItem;
import com.aitaskcenter.dto.StoryAgentDtos.AgentUpdateRequest;
import com.aitaskcenter.dto.StoryAgentDtos.AgentView;
import com.aitaskcenter.dto.StoryAgentDtos.BudgetUpdateRequest;
import com.aitaskcenter.dto.StoryAgentDtos.BudgetView;
import com.aitaskcenter.dto.StoryAgentDtos.FlowView;
import com.aitaskcenter.dto.StoryAgentDtos.PromptVersionView;
import com.aitaskcenter.model.BaseEntity;
import com.aitaskcenter.model.StoryAgentConfig;
import com.aitaskcenter.model.StoryAgentPromptVersion;
import com.aitaskcenter.model.StoryFlowConfig;
import com.aitaskcenter.repository.StoryAgentConfigRepository;
import com.aitaskcenter.repository.StoryAgentPromptVersionRepository;
import com.aitaskcenter.repository.StoryFlowConfigRepository;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StoryAgentServiceTest {
    @Mock
    private StoryAgentConfigRepository configRepository;

    @Mock
    private StoryAgentPromptVersionRepository versionRepository;

    @Mock
    private StoryFlowConfigRepository flowRepository;

    @Mock
    private AiConfigService aiConfigService;

    private StoryAgentService service;

    @BeforeEach
    void setUp() {
        service = new StoryAgentService(
                configRepository,
                versionRepository,
                flowRepository,
                aiConfigService);
    }

    @Test
    void storyAgentConfigUsesJpaOptimisticVersion() {
        assertTrue(Arrays.stream(StoryAgentConfig.class.getDeclaredFields())
                .anyMatch(field -> field.isAnnotationPresent(Version.class)));
    }

    @Test
    void initializeOnlyCreatesMissingAgentsAndInitialVersions() {
        OffsetDateTime savedAt = OffsetDateTime.parse("2026-08-12T10:15:30+08:00");
        StoryAgentConfig existingWriter = config(
                "story-writer", "An existing prompt", "custom-provider", 1.2, false, 7, savedAt);
        existingWriter.setName("Existing custom name");
        existingWriter.setRoleType("EXISTING_ROLE");
        existingWriter.setDescription("Existing custom description");

        Map<String, StoryAgentConfig> configs = new HashMap<>();
        configs.put(existingWriter.getAgentKey(), existingWriter);
        List<StoryAgentPromptVersion> snapshots = new ArrayList<>();
        AtomicReference<StoryFlowConfig> flow = new AtomicReference<>();
        when(aiConfigService.getProviders()).thenReturn(providerConfig(
                "fallback",
                provider("medium-provider", "Gemini regular", "gemini-flashmedium-preview", true,
                        "TEXT_GENERATION"),
                provider("flash-high", "Gemini Flash High", "gemini-2.5-flash", true, "TEXT_GENERATION"),
                provider("gemini-pro", "Gemini Pro", "gemini-2.5-pro", true, "TEXT_GENERATION"),
                provider("fallback", "General text", "general-model", true, "TEXT_GENERATION")));
        when(configRepository.findByAgentKey(any())).thenAnswer(invocation ->
                Optional.ofNullable(configs.get(invocation.getArgument(0, String.class))));
        when(configRepository.save(any())).thenAnswer(invocation -> {
            StoryAgentConfig saved = invocation.getArgument(0);
            configs.put(saved.getAgentKey(), saved);
            return saved;
        });
        when(configRepository.findAllByOrderByAgentKeyAsc()).thenAnswer(invocation ->
                configs.values().stream().toList());
        when(versionRepository.save(any())).thenAnswer(invocation -> {
            StoryAgentPromptVersion saved = invocation.getArgument(0);
            snapshots.add(saved);
            return saved;
        });
        when(flowRepository.findByConfigKey(StoryFlowConfig.DEFAULT_CONFIG_KEY)).thenAnswer(invocation ->
                Optional.ofNullable(flow.get()));
        when(flowRepository.save(any())).thenAnswer(invocation -> {
            StoryFlowConfig saved = invocation.getArgument(0);
            flow.set(saved);
            return saved;
        });

        service.initializeDefaults();
        FlowView view = service.getFlow();

        assertEquals(StoryAgentCatalog.agents().size(), configs.size());
        assertSame(existingWriter, configs.get("story-writer"));
        assertEquals("An existing prompt", existingWriter.getSystemPrompt());
        assertEquals(StoryAgentCatalog.agents().size() - 1, snapshots.size());
        assertTrue(snapshots.stream().allMatch(snapshot -> snapshot.getVersion() == 1));
        assertEquals(StoryAgentCatalog.agents().size() - 1,
                snapshots.stream().map(StoryAgentPromptVersion::getAgentKey).distinct().count());
        assertEquals("medium-provider", configs.get("vocabulary-planner").getAiProviderId());
        assertEquals("flash-high", configs.get("pitch-humor").getAiProviderId());
        assertEquals("gemini-pro", configs.get("story-director").getAiProviderId());
        assertEquals(1, configs.get("story-director").getPromptVersion());
        assertEquals(StoryAgentCatalog.require("story-director").defaultPrompt(),
                configs.get("story-director").getSystemPrompt());
        assertEquals(StoryFlowConfig.DEFAULT_CONFIG_KEY, flow.get().getConfigKey());
        assertEquals(3, flow.get().getMaxQualityRounds());

        assertEquals(StoryAgentCatalog.stages().size(), view.stages().size());
        assertEquals(StoryAgentCatalog.nodes().size(), view.stages().stream()
                .mapToInt(stage -> stage.nodes().size())
                .sum());
        AgentView writerView = findNode(view, "story-writer");
        assertEquals(StoryAgentCatalog.require("story-writer").name(), writerView.name());
        assertEquals("An existing prompt", writerView.systemPrompt());
        assertEquals(7, writerView.promptVersion());
        AgentView programView = findNode(view, "hard-rule-check");
        assertFalse(programView.editable());
        assertNull(programView.systemPrompt());
        assertNull(programView.aiProviderId());
        assertNull(programView.temperature());
        assertNull(programView.enabled());
        assertNull(programView.promptVersion());
        assertNull(programView.updatedAt());

        verify(configRepository, times(StoryAgentCatalog.agents().size() - 1)).save(any());
        verify(versionRepository, times(StoryAgentCatalog.agents().size() - 1)).save(any());
        verify(flowRepository, times(1)).save(any());
        verify(aiConfigService, times(1)).getProviders();
    }

    @Test
    void saveChangedPromptCreatesNextVersion() {
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-08-12T10:15:30+08:00");
        OffsetDateTime flushedAt = updatedAt.plusSeconds(1);
        StoryAgentConfig current = config(
                "story-writer", "Current prompt", "text-provider", 0.7, true, 3, updatedAt);
        when(configRepository.findByAgentKey("story-writer")).thenReturn(Optional.of(current));
        when(aiConfigService.getProviders()).thenReturn(providerConfig(
                "text-provider",
                provider("text-provider", "Text", "text-model", true, "TEXT_GENERATION")));
        when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(versionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            ReflectionTestUtils.setField(current, "updatedAt", flushedAt);
            return null;
        }).when(configRepository).flush();

        AgentView result = service.update("story-writer", new AgentUpdateRequest(
                "  Changed prompt  ", "text-provider", 0.5, false,
                updatedAt.withOffsetSameInstant(ZoneOffset.UTC)));

        ArgumentCaptor<StoryAgentConfig> configCaptor = ArgumentCaptor.forClass(StoryAgentConfig.class);
        ArgumentCaptor<StoryAgentPromptVersion> versionCaptor =
                ArgumentCaptor.forClass(StoryAgentPromptVersion.class);
        InOrder writeOrder = inOrder(configRepository, versionRepository);
        writeOrder.verify(configRepository).save(configCaptor.capture());
        writeOrder.verify(configRepository).flush();
        writeOrder.verify(versionRepository).save(versionCaptor.capture());
        StoryAgentConfig saved = configCaptor.getValue();
        StoryAgentPromptVersion snapshot = versionCaptor.getValue();
        assertEquals("Changed prompt", saved.getSystemPrompt());
        assertEquals("text-provider", saved.getAiProviderId());
        assertEquals(0.5, saved.getTemperature());
        assertFalse(saved.isEnabled());
        assertEquals(4, saved.getPromptVersion());
        assertEquals(saved.getAgentKey(), snapshot.getAgentKey());
        assertEquals(saved.getPromptVersion(), snapshot.getVersion());
        assertEquals(saved.getSystemPrompt(), snapshot.getSystemPrompt());
        assertEquals(saved.getAiProviderId(), snapshot.getAiProviderId());
        assertEquals(saved.getTemperature(), snapshot.getTemperature());
        assertEquals(saved.isEnabled(), snapshot.isEnabled());
        assertEquals(4, result.promptVersion());
        assertEquals("Changed prompt", result.systemPrompt());
        assertEquals(flushedAt, result.updatedAt());
    }

    @Test
    void updateMapsOptimisticConflictBeforeSavingSnapshot() {
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-08-12T10:15:30+08:00");
        StoryAgentConfig current = config(
                "story-writer", "Current prompt", "text-provider", 0.7, true, 3, updatedAt);
        current.setId(99L);
        when(configRepository.findByAgentKey("story-writer")).thenReturn(Optional.of(current));
        when(aiConfigService.getProviders()).thenReturn(providerConfig(
                "text-provider",
                provider("text-provider", "Text", "text-model", true, "TEXT_GENERATION")));
        doThrow(new ObjectOptimisticLockingFailureException(StoryAgentConfig.class, current.getId()))
                .when(configRepository).flush();

        IllegalArgumentException conflict = assertThrows(IllegalArgumentException.class, () ->
                service.update("story-writer", new AgentUpdateRequest(
                        "Changed prompt", "text-provider", 0.5, false, updatedAt)));

        assertEquals("故事 Agent 配置已被更新，请刷新页面后重试", conflict.getMessage());
        verify(versionRepository, never()).save(any());
    }

    @Test
    void saveIdenticalValuesDoesNotCreateVersion() {
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-08-12T10:15:30+08:00");
        StoryAgentConfig current = config(
                "story-writer", "Current prompt", "text-provider", 0.7, true, 3, updatedAt);
        when(configRepository.findByAgentKey("story-writer")).thenReturn(Optional.of(current));
        when(aiConfigService.getProviders()).thenReturn(providerConfig(
                "text-provider",
                provider("text-provider", "Text", "text-model", true, "TEXT_GENERATION")));

        AgentView result = service.update("story-writer", new AgentUpdateRequest(
                "  Current prompt  ", " text-provider ", 0.7, true, updatedAt));

        assertEquals(3, result.promptVersion());
        assertEquals("Current prompt", result.systemPrompt());
        verify(configRepository, never()).save(any());
        verify(configRepository, never()).flush();
        verify(versionRepository, never()).save(any());
    }

    @Test
    void rejectsBlankPromptUnknownKeyAndNonTextProvider() {
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-08-12T10:15:30+08:00");
        when(aiConfigService.getProviders()).thenReturn(providerConfig(
                "text-provider",
                provider("text-provider", "Text", "text-model", true, "TEXT_GENERATION"),
                provider("audio-provider", "Audio", "tts-model", true, "AUDIO_TTS"),
                provider("disabled-provider", "Disabled", "text-model", false, "TEXT_GENERATION")));

        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class, () ->
                service.update("story-writer", new AgentUpdateRequest(
                        "   ", "text-provider", 0.7, true, updatedAt)));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () ->
                service.update("missing-agent", new AgentUpdateRequest(
                        "Prompt", "text-provider", 0.7, true, updatedAt)));
        IllegalArgumentException fixedNode = assertThrows(IllegalArgumentException.class, () ->
                service.update("hard-rule-check", new AgentUpdateRequest(
                        "Prompt", "text-provider", 0.7, true, updatedAt)));
        IllegalArgumentException nonText = assertThrows(IllegalArgumentException.class, () ->
                service.update("story-writer", new AgentUpdateRequest(
                        "Prompt", "audio-provider", 0.7, true, updatedAt)));
        IllegalArgumentException disabled = assertThrows(IllegalArgumentException.class, () ->
                service.update("story-writer", new AgentUpdateRequest(
                        "Prompt", "disabled-provider", 0.7, true, updatedAt)));

        assertTrue(blank.getMessage().contains("提示词"));
        assertTrue(unknown.getMessage().contains("missing-agent"));
        assertTrue(fixedNode.getMessage().contains("编辑"));
        assertTrue(nonText.getMessage().contains("文本生成"));
        assertTrue(disabled.getMessage().contains("启用"));
        verify(configRepository, never()).save(any());
        verify(versionRepository, never()).save(any());
    }

    @Test
    void rejectsStaleUpdatedAt() {
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-08-12T10:15:30+08:00");
        StoryAgentConfig current = config(
                "story-writer", "Current prompt", "text-provider", 0.7, true, 3, updatedAt);
        when(configRepository.findByAgentKey("story-writer")).thenReturn(Optional.of(current));
        when(aiConfigService.getProviders()).thenReturn(providerConfig(
                "text-provider",
                provider("text-provider", "Text", "text-model", true, "TEXT_GENERATION")));

        IllegalArgumentException stale = assertThrows(IllegalArgumentException.class, () ->
                service.update("story-writer", new AgentUpdateRequest(
                        "Changed", "text-provider", 0.7, true, updatedAt.minusSeconds(1))));
        IllegalArgumentException absent = assertThrows(IllegalArgumentException.class, () ->
                service.update("story-writer", new AgentUpdateRequest(
                        "Changed", "text-provider", 0.7, true, null)));

        assertTrue(stale.getMessage().contains("刷新"));
        assertTrue(absent.getMessage().contains("刷新"));
        verify(configRepository, never()).save(any());
        verify(versionRepository, never()).save(any());
    }

    @Test
    void restoreCreatesNewLatestVersionWithoutChangingHistory() {
        OffsetDateTime currentUpdatedAt = OffsetDateTime.parse("2026-08-12T10:15:30+08:00");
        OffsetDateTime flushedAt = currentUpdatedAt.plusSeconds(1);
        OffsetDateTime historicalCreatedAt = OffsetDateTime.parse("2026-08-10T09:00:00+08:00");
        StoryAgentConfig current = config(
                "story-writer", "Current prompt", "current-provider", 0.7, true, 5, currentUpdatedAt);
        StoryAgentPromptVersion historical = version(
                "story-writer", 2, "Historical prompt", "historical-provider", 0.2, false,
                historicalCreatedAt);
        when(configRepository.findByAgentKey("story-writer")).thenReturn(Optional.of(current));
        when(aiConfigService.getProviders()).thenReturn(providerConfig(
                "historical-provider",
                provider("historical-provider", "Historical", "text-model", true, "TEXT_GENERATION")));
        when(versionRepository.findByAgentKeyAndVersion("story-writer", 2))
                .thenReturn(Optional.of(historical));
        when(versionRepository.findByAgentKeyOrderByVersionDesc("story-writer"))
                .thenReturn(List.of(historical));
        when(configRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(versionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            ReflectionTestUtils.setField(current, "updatedAt", flushedAt);
            return null;
        }).when(configRepository).flush();

        List<PromptVersionView> historyBeforeRestore = service.versions("story-writer");
        AgentView restored = service.restore("story-writer", 2);

        assertEquals(1, historyBeforeRestore.size());
        assertEquals(2, historyBeforeRestore.get(0).version());
        assertEquals(historicalCreatedAt, historyBeforeRestore.get(0).createdAt());
        assertEquals("Historical prompt", historical.getSystemPrompt());
        assertEquals(2, historical.getVersion());
        assertEquals("historical-provider", historical.getAiProviderId());
        assertEquals(0.2, historical.getTemperature());
        assertFalse(historical.isEnabled());

        ArgumentCaptor<StoryAgentPromptVersion> snapshotCaptor =
                ArgumentCaptor.forClass(StoryAgentPromptVersion.class);
        InOrder writeOrder = inOrder(configRepository, versionRepository);
        writeOrder.verify(configRepository).save(current);
        writeOrder.verify(configRepository).flush();
        writeOrder.verify(versionRepository).save(snapshotCaptor.capture());
        StoryAgentPromptVersion newSnapshot = snapshotCaptor.getValue();
        assertEquals(6, current.getPromptVersion());
        assertEquals("Historical prompt", current.getSystemPrompt());
        assertEquals("historical-provider", current.getAiProviderId());
        assertEquals(0.2, current.getTemperature());
        assertFalse(current.isEnabled());
        assertEquals(6, newSnapshot.getVersion());
        assertEquals("Historical prompt", newSnapshot.getSystemPrompt());
        assertEquals(6, restored.promptVersion());
        assertEquals("Historical prompt", restored.systemPrompt());
        assertEquals(flushedAt, restored.updatedAt());
    }

    @Test
    void restoreMapsOptimisticConflictBeforeSavingSnapshot() {
        OffsetDateTime updatedAt = OffsetDateTime.parse("2026-08-12T10:15:30+08:00");
        StoryAgentConfig current = config(
                "story-writer", "Current prompt", "text-provider", 0.7, true, 3, updatedAt);
        current.setId(99L);
        StoryAgentPromptVersion historical = version(
                "story-writer", 2, "Historical prompt", "text-provider", 0.2, false, updatedAt);
        when(versionRepository.findByAgentKeyAndVersion("story-writer", 2))
                .thenReturn(Optional.of(historical));
        when(configRepository.findByAgentKey("story-writer")).thenReturn(Optional.of(current));
        when(aiConfigService.getProviders()).thenReturn(providerConfig(
                "text-provider",
                provider("text-provider", "Text", "text-model", true, "TEXT_GENERATION")));
        doThrow(new ObjectOptimisticLockingFailureException(StoryAgentConfig.class, current.getId()))
                .when(configRepository).flush();

        IllegalArgumentException conflict = assertThrows(IllegalArgumentException.class, () ->
                service.restore("story-writer", 2));

        assertEquals("故事 Agent 配置已被更新，请刷新页面后重试", conflict.getMessage());
        verify(versionRepository, never()).save(any());
    }

    @Test
    void restoreRejectsMissingDisabledAndNonTextProvidersWithoutWrites() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-10T09:00:00+08:00");
        when(versionRepository.findByAgentKeyAndVersion("story-writer", 1)).thenReturn(Optional.of(version(
                "story-writer", 1, "Missing", "missing-provider", 0.2, true, createdAt)));
        when(versionRepository.findByAgentKeyAndVersion("story-writer", 2)).thenReturn(Optional.of(version(
                "story-writer", 2, "Disabled", "disabled-provider", 0.2, true, createdAt)));
        when(versionRepository.findByAgentKeyAndVersion("story-writer", 3)).thenReturn(Optional.of(version(
                "story-writer", 3, "Audio", "audio-provider", 0.2, true, createdAt)));
        when(aiConfigService.getProviders()).thenReturn(providerConfig(
                "disabled-provider",
                provider("disabled-provider", "Disabled", "text-model", false, "TEXT_GENERATION"),
                provider("audio-provider", "Audio", "tts-model", true, "AUDIO_TTS")));

        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class, () -> service.restore("story-writer", 1));
        IllegalArgumentException disabled = assertThrows(
                IllegalArgumentException.class, () -> service.restore("story-writer", 2));
        IllegalArgumentException nonText = assertThrows(
                IllegalArgumentException.class, () -> service.restore("story-writer", 3));

        assertEquals("AI 配置「missing-provider」不存在", missing.getMessage());
        assertEquals("AI 配置「disabled-provider」未启用", disabled.getMessage());
        assertEquals("AI 配置「audio-provider」不支持文本生成", nonText.getMessage());
        verify(configRepository, never()).findByAgentKey(any());
        verify(configRepository, never()).save(any());
        verify(configRepository, never()).flush();
        verify(versionRepository, never()).save(any());
    }

    @Test
    void validatesAndSavesBudgetBounds() {
        AtomicReference<StoryFlowConfig> savedConfig = new AtomicReference<>();
        OffsetDateTime flushedAt = OffsetDateTime.parse("2026-08-12T10:15:31+08:00");
        when(flowRepository.findByConfigKey(StoryFlowConfig.DEFAULT_CONFIG_KEY)).thenAnswer(invocation ->
                Optional.ofNullable(savedConfig.get()));
        when(flowRepository.save(any())).thenAnswer(invocation -> {
            StoryFlowConfig saved = invocation.getArgument(0);
            savedConfig.set(saved);
            return saved;
        });
        doAnswer(invocation -> {
            setTimestamps(savedConfig.get(), flushedAt, flushedAt);
            return null;
        }).when(flowRepository).flush();

        BudgetView defaults = service.getBudget();
        BudgetView saved = service.updateBudget(budget(5, 4, 3, 2, 1, 0, 500_000));

        assertEquals(3, defaults.maxQualityRounds());
        assertEquals(120_000, defaults.maxTotalTokens());
        assertEquals(5, saved.maxQualityRounds());
        assertEquals(4, saved.maxLocalRevisions());
        assertEquals(3, saved.maxWriterRewrites());
        assertEquals(2, saved.maxDirectorReturns());
        assertEquals(1, saved.maxPitchReturns());
        assertEquals(0, saved.maxPlanReturns());
        assertEquals(500_000, saved.maxTotalTokens());
        assertEquals(flushedAt, saved.updatedAt());
        assertEquals(500_000, savedConfig.get().getMaxTotalTokens());

        assertThrows(IllegalArgumentException.class, () ->
                service.updateBudget(budget(0, 1, 1, 1, 1, 1, 1_000)));
        assertThrows(IllegalArgumentException.class, () ->
                service.updateBudget(budget(21, 1, 1, 1, 1, 1, 1_000)));
        assertThrows(IllegalArgumentException.class, () ->
                service.updateBudget(budget(1, -1, 1, 1, 1, 1, 1_000)));
        assertThrows(IllegalArgumentException.class, () ->
                service.updateBudget(budget(1, 1, 21, 1, 1, 1, 1_000)));
        assertThrows(IllegalArgumentException.class, () ->
                service.updateBudget(budget(1, 1, 1, 1, 1, 1, 999)));
        assertThrows(IllegalArgumentException.class, () ->
                service.updateBudget(budget(1, 1, 1, 1, 1, 1, 10_000_001)));
        assertThrows(IllegalArgumentException.class, () ->
                service.updateBudget(new BudgetUpdateRequest(null, 1, 1, 1, 1, 1, 1_000)));

        verify(flowRepository, times(1)).save(any());
        verify(flowRepository, times(1)).flush();
    }

    private AgentView findNode(FlowView flow, String key) {
        return flow.stages().stream()
                .flatMap(stage -> stage.nodes().stream())
                .filter(node -> key.equals(node.key()))
                .findFirst()
                .orElseThrow();
    }

    private StoryAgentConfig config(
            String key,
            String prompt,
            String providerId,
            double temperature,
            boolean enabled,
            int version,
            OffsetDateTime updatedAt) {
        StoryAgentCatalog.NodeDefinition definition = StoryAgentCatalog.require(key);
        StoryAgentConfig config = new StoryAgentConfig();
        config.setAgentKey(key);
        config.setName(definition.name());
        config.setRoleType(definition.roleType());
        config.setDescription(definition.description());
        config.setSystemPrompt(prompt);
        config.setAiProviderId(providerId);
        config.setTemperature(temperature);
        config.setEnabled(enabled);
        config.setPromptVersion(version);
        setTimestamps(config, updatedAt, updatedAt);
        return config;
    }

    private StoryAgentPromptVersion version(
            String key,
            int version,
            String prompt,
            String providerId,
            double temperature,
            boolean enabled,
            OffsetDateTime createdAt) {
        StoryAgentPromptVersion snapshot = new StoryAgentPromptVersion();
        snapshot.setAgentKey(key);
        snapshot.setVersion(version);
        snapshot.setSystemPrompt(prompt);
        snapshot.setAiProviderId(providerId);
        snapshot.setTemperature(temperature);
        snapshot.setEnabled(enabled);
        setTimestamps(snapshot, createdAt, createdAt);
        return snapshot;
    }

    private void setTimestamps(BaseEntity entity, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        ReflectionTestUtils.setField(entity, "createdAt", createdAt);
        ReflectionTestUtils.setField(entity, "updatedAt", updatedAt);
    }

    private AiConfigRequest providerConfig(String active, AiProviderConfigItem... providers) {
        AiConfigRequest request = new AiConfigRequest();
        request.setActive(active);
        request.setProviders(List.of(providers));
        return request;
    }

    private AiProviderConfigItem provider(
            String id,
            String label,
            String model,
            boolean enabled,
            String... capabilities) {
        AiProviderConfigItem provider = new AiProviderConfigItem();
        provider.setId(id);
        provider.setLabel(label);
        provider.setModel(model);
        provider.setEnabled(enabled);
        provider.setCapabilities(List.of(capabilities));
        return provider;
    }

    private BudgetUpdateRequest budget(
            Integer quality,
            Integer local,
            Integer writer,
            Integer director,
            Integer pitch,
            Integer plan,
            Integer tokens) {
        return new BudgetUpdateRequest(quality, local, writer, director, pitch, plan, tokens);
    }
}
