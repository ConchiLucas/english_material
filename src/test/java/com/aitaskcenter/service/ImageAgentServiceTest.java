package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aitaskcenter.config.ImageAgentCatalog;
import com.aitaskcenter.config.ImageAgentInitializer;
import com.aitaskcenter.dto.AiConfigRequest;
import com.aitaskcenter.dto.AiProviderConfigItem;
import com.aitaskcenter.dto.ImageAgentDtos.AgentUpdateRequest;
import com.aitaskcenter.dto.ImageAgentDtos.FlowUpdateRequest;
import com.aitaskcenter.dto.ImageAgentDtos.StyleCreateRequest;
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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ImageAgentServiceTest {
    private static final OffsetDateTime UPDATED = OffsetDateTime.parse("2026-08-15T10:00:00+08:00");

    @Mock private ImageAgentConfigRepository agents;
    @Mock private ImageAgentPromptVersionRepository versions;
    @Mock private ImageFlowConfigRepository flows;
    @Mock private ImageStylePresetRepository styles;
    @Mock private AiConfigService providers;
    private ImageAgentService service;

    @BeforeEach void setUp() { service = new ImageAgentService(agents, versions, flows, styles, providers); }

    @Test
    void initializeCreatesOnlyMissingNineAgentsVersionsFlowAndBuiltInStyles() {
        Map<String, ImageAgentConfig> saved = new HashMap<>();
        ImageAgentConfig existing = agent("image-story-analyst", "custom", "text", 7, UPDATED);
        saved.put(existing.getAgentKey(), existing);
        List<ImageAgentPromptVersion> snapshots = new ArrayList<>();
        when(agents.findByAgentKey(any())).thenAnswer(i -> Optional.ofNullable(saved.get(i.getArgument(0))));
        when(agents.save(any())).thenAnswer(i -> { ImageAgentConfig value = i.getArgument(0); saved.put(value.getAgentKey(), value); return value; });
        when(versions.save(any())).thenAnswer(i -> { snapshots.add(i.getArgument(0)); return i.getArgument(0); });
        when(flows.findByFlowKey("default")).thenReturn(Optional.empty());
        when(styles.findByPresetKey(any())).thenReturn(Optional.empty());
        when(providers.getProviders()).thenReturn(providerConfig("gpt-pro",
                provider("reproduction", true, "TEXT_GENERATION"),
                provider("gpt-pro", true, "TEXT_GENERATION")));

        service.initializeDefaults();

        assertEquals(9, saved.size());
        assertEquals("custom", saved.get(existing.getAgentKey()).getSystemPrompt());
        assertEquals(8, snapshots.size());
        assertTrue(snapshots.stream().allMatch(v -> v.getPromptVersion() == 1));
        assertTrue(saved.values().stream().filter(value -> value != existing)
                .allMatch(value -> "gpt-pro".equals(value.getAiProviderId())));
        verify(flows).save(any(ImageFlowConfig.class));
        verify(styles, org.mockito.Mockito.atLeast(2)).save(any(ImageStylePreset.class));
    }

    @Test
    void updateRejectsStaleTimestampBeforeProviderOrSave() {
        ImageAgentConfig current = agent("image-story-analyst", "old", "text", 1, UPDATED);
        when(agents.findByAgentKey(current.getAgentKey())).thenReturn(Optional.of(current));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.updateAgent(
                current.getAgentKey(), new AgentUpdateRequest("changed", "missing", .4, true, UPDATED.minusSeconds(1))));

        assertEquals("图片 Agent 配置已被更新，请刷新页面后重试", error.getMessage());
        verify(providers, never()).getProviders();
        verify(agents, never()).save(any());
    }

    @Test
    void changedPromptFlushesBeforeAppendingSnapshotAndReturnsFlushedTimestamp() {
        ImageAgentConfig current = agent("image-story-analyst", "old", "text", 1, UPDATED);
        OffsetDateTime flushed = UPDATED.plusSeconds(1);
        when(agents.findByAgentKey(current.getAgentKey())).thenReturn(Optional.of(current));
        when(providers.getProviders()).thenReturn(providerConfig("text", provider("text", true, "TEXT_GENERATION")));
        doAnswer(i -> { ReflectionTestUtils.setField(current, "updatedAt", flushed); return null; }).when(agents).flush();

        var result = service.updateAgent(current.getAgentKey(), new AgentUpdateRequest("  new  ", "text", .4, false, UPDATED));

        InOrder writes = inOrder(agents, versions);
        writes.verify(agents).save(current);
        writes.verify(agents).flush();
        ArgumentCaptor<ImageAgentPromptVersion> capture = ArgumentCaptor.forClass(ImageAgentPromptVersion.class);
        writes.verify(versions).save(capture.capture());
        assertEquals("new", capture.getValue().getSystemPrompt());
        assertEquals(2, capture.getValue().getPromptVersion());
        assertEquals(flushed, result.updatedAt());
    }

    @Test
    void restoreRevalidatesHistoricalProviderAndCreatesNewLatestSnapshot() {
        ImageAgentConfig current = agent("image-story-analyst", "now", "text", 2, UPDATED);
        ImageAgentPromptVersion old = version(current.getAgentKey(), 1, "old", "text", .2, true);
        when(agents.findByAgentKey(current.getAgentKey())).thenReturn(Optional.of(current));
        when(versions.findByAgentKeyAndPromptVersion(current.getAgentKey(), 1)).thenReturn(Optional.of(old));
        when(providers.getProviders()).thenReturn(providerConfig("text", provider("text", true, "TEXT_GENERATION")));

        service.restoreVersion(current.getAgentKey(), 1, UPDATED);

        assertEquals("old", current.getSystemPrompt());
        assertEquals(3, current.getPromptVersion());
        verify(versions).save(any(ImageAgentPromptVersion.class));
    }

    @Test
    void flowRequiresEnabledDualCapabilityProviderAndFixedLimits() {
        ImageFlowConfig flow = ImageFlowConfig.defaults();
        ReflectionTestUtils.setField(flow, "updatedAt", UPDATED);
        when(flows.findByFlowKey("default")).thenReturn(Optional.of(flow));
        when(providers.getProviders()).thenReturn(providerConfig("text", provider("text", true, "TEXT_GENERATION")));

        IllegalArgumentException providerError = assertThrows(IllegalArgumentException.class, () -> service.updateFlow(
                new FlowUpdateRequest("text", 1536, 864, 5, 20, UPDATED)));
        assertEquals("请选择支持图片生成和多参考图的 OpenAI-compatible Provider", providerError.getMessage());
        IllegalArgumentException fixedError = assertThrows(IllegalArgumentException.class, () -> service.updateFlow(
                new FlowUpdateRequest("text", 1024, 864, 5, 20, UPDATED)));
        assertTrue(fixedError.getMessage().contains("1536"));
    }

    @Test
    void flowRejectsNonOpenAiCompatibleProviderEvenWithBothImageCapabilities() {
        ImageFlowConfig flow = ImageFlowConfig.defaults();
        ReflectionTestUtils.setField(flow, "updatedAt", UPDATED);
        AiProviderConfigItem unsupported = provider("anthropic-image", true,
                "IMAGE_GENERATION", "IMAGE_REFERENCE");
        unsupported.setType("  anthropic-compatible  ");
        when(flows.findByFlowKey("default")).thenReturn(Optional.of(flow));
        when(providers.getProviders()).thenReturn(providerConfig("", unsupported));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.updateFlow(
                new FlowUpdateRequest("anthropic-image", 1536, 864, 5, 20, UPDATED)));

        assertEquals("请选择支持图片生成和多参考图的 OpenAI-compatible Provider", error.getMessage());
        verify(flows, never()).save(any());
    }

    @Test
    void initializationSelectsOnlyOpenAiCompatibleImageProvider() {
        when(agents.findByAgentKey(any())).thenReturn(Optional.of(agent(
                "image-story-analyst", "existing", "text", 1, UPDATED)));
        when(flows.findByFlowKey("default")).thenReturn(Optional.empty());
        when(styles.findByPresetKey(any())).thenReturn(Optional.of(style(1L, "existing", true, UPDATED)));
        AiProviderConfigItem unsupported = provider("anthropic-image", true,
                "IMAGE_GENERATION", "IMAGE_REFERENCE");
        unsupported.setType("anthropic-compatible");
        AiProviderConfigItem supported = provider("openai-image", true,
                "IMAGE_GENERATION", "IMAGE_REFERENCE");
        supported.setType("  OPENAI-COMPATIBLE  ");
        when(providers.getProviders()).thenReturn(providerConfig("", unsupported, supported));

        service.initializeDefaults();

        ArgumentCaptor<ImageFlowConfig> capture = ArgumentCaptor.forClass(ImageFlowConfig.class);
        verify(flows).save(capture.capture());
        assertEquals("openai-image", capture.getValue().getImageProviderId());
    }

    @Test
    void flowRejectsImageProvidersThatTheExecutionAdapterCannotUse() {
        ImageFlowConfig flow = ImageFlowConfig.defaults();
        ReflectionTestUtils.setField(flow, "updatedAt", UPDATED);
        AiProviderConfigItem image = provider("image-provider", true,
                "IMAGE_GENERATION", "IMAGE_REFERENCE");
        image.setApiKey("hidden-provider-api-key");
        when(flows.findByFlowKey("default")).thenReturn(Optional.of(flow));
        when(providers.getProviders()).thenReturn(providerConfig("", image));

        List<java.util.function.Consumer<AiProviderConfigItem>> invalid = List.of(
                value -> value.setBaseUrl("https://provider.invalid/v1?token=hidden-provider-secret"),
                value -> value.setBaseUrl("https://user:hidden-provider-secret@provider.invalid/v1"),
                value -> value.setBaseUrl("https://provider.invalid/v1/images/generations"),
                value -> value.setModel("   "),
                value -> value.setOptions(Map.of("quality", Map.of("secret", "hidden-provider-secret"))),
                value -> value.setOptions(Map.of("size", "1024x1024")),
                value -> value.setOptions(Map.of("responseFormat", "url")),
                value -> value.setOptions(Map.of("quality", "ultra")));

        for (java.util.function.Consumer<AiProviderConfigItem> mutation : invalid) {
            image.setBaseUrl("https://provider.invalid/v1");
            image.setModel("image-model");
            image.setOptions(Map.of());
            mutation.accept(image);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.updateFlow(new FlowUpdateRequest(
                            "image-provider", 1536, 864, 5, 20, UPDATED)));
            assertTrue(!error.getMessage().contains("hidden-provider-secret"));
            assertTrue(!error.getMessage().contains("hidden-provider-api-key"));
        }
        verify(flows, never()).save(any());
    }

    @Test
    void stylesAreBuiltInFirstAndCreationUsesServerKeyAndTrimmedValues() {
        when(styles.save(any())).thenAnswer(i -> { ImageStylePreset preset = i.getArgument(0); preset.setId(3L); return preset; });

        var result = service.createStyle(new StyleCreateRequest("  My Style  ", " positive ", " negative ", " desc ", true));

        ArgumentCaptor<ImageStylePreset> capture = ArgumentCaptor.forClass(ImageStylePreset.class);
        verify(styles).save(capture.capture());
        assertEquals("My Style", capture.getValue().getName());
        assertTrue(capture.getValue().getPresetKey().startsWith("custom-"));
        assertTrue(!capture.getValue().isBuiltIn());
        assertEquals(capture.getValue().getPresetKey(), result.key());
    }

    @Test
    void updateStyleRejectsStaleBeforeMutationAndNeverChangesBuiltInFlag() {
        ImageStylePreset preset = style(1L, "builtin-watercolor", true, UPDATED);
        when(styles.findById(1L)).thenReturn(Optional.of(preset));
        assertThrows(IllegalArgumentException.class, () -> service.updateStyle(1L,
                new StyleUpdateRequest("n", "p", "x", "d", false, UPDATED.minusSeconds(1))));
        verify(styles, never()).save(any());
        assertTrue(preset.isBuiltIn());
    }

    @Test
    void getFlowMergesAllFourStagesAndTwelveCatalogNodesWithConfiguredAgent() {
        ImageAgentConfig configured = agent("image-story-analyst", "configured", "text", 4, UPDATED);
        when(agents.findAllByOrderByAgentKeyAsc()).thenReturn(List.of(configured));
        ImageFlowConfig flow = ImageFlowConfig.defaults();
        when(flows.findByFlowKey("default")).thenReturn(Optional.of(flow));
        when(styles.findAllByOrderByBuiltInDescNameAsc()).thenReturn(List.of());

        var result = service.getFlow();

        assertEquals(4, result.stages().size());
        assertEquals(12, result.stages().stream().mapToInt(stage -> stage.nodes().size()).sum());
        assertEquals("configured", result.stages().get(0).nodes().get(0).systemPrompt());
        assertTrue(!result.stages().get(3).nodes().get(0).editable());
    }

    @Test
    void initializerReturnsNormallyAfterThreeRecognizableConcurrentInsertConflicts() throws Exception {
        ImageAgentService retrying = org.mockito.Mockito.mock(ImageAgentService.class);
        org.mockito.Mockito.doThrow(uniqueConflict()).doThrow(uniqueConflict()).doThrow(uniqueConflict())
                .when(retrying).initializeDefaults();

        new ImageAgentInitializer(retrying).run(null);

        verify(retrying, org.mockito.Mockito.times(3)).initializeDefaults();
    }

    @Test
    void initializerImmediatelyRethrowsNonUniqueDataIntegrityFailure() throws Exception {
        ImageAgentService retrying = org.mockito.Mockito.mock(ImageAgentService.class);
        DataIntegrityViolationException requiredColumn = new DataIntegrityViolationException(
                "not null", new SQLException("not null", "23502", 0));
        org.mockito.Mockito.doThrow(requiredColumn).when(retrying).initializeDefaults();

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
                () -> new ImageAgentInitializer(retrying).run(null));

        assertEquals(requiredColumn, thrown);
        verify(retrying, org.mockito.Mockito.times(1)).initializeDefaults();
    }

    @Test
    void initializerRetriesOnceThenCompletesAfterRecognizableUniqueConflict() throws Exception {
        ImageAgentService retrying = org.mockito.Mockito.mock(ImageAgentService.class);
        org.mockito.Mockito.doThrow(uniqueConflict()).doNothing().when(retrying).initializeDefaults();

        new ImageAgentInitializer(retrying).run(null);

        verify(retrying, org.mockito.Mockito.times(2)).initializeDefaults();
    }

    private static DataIntegrityViolationException uniqueConflict() {
        return new DataIntegrityViolationException("duplicate", new SQLException("duplicate", "23505", 0));
    }

    private static ImageAgentConfig agent(String key, String prompt, String provider, int version, OffsetDateTime updated) {
        ImageAgentConfig value = new ImageAgentConfig(); value.setAgentKey(key); value.setName(key); value.setRoleType("ROLE"); value.setDescription("desc"); value.setSystemPrompt(prompt); value.setAiProviderId(provider); value.setTemperature(.2); value.setEnabled(true); value.setPromptVersion(version); ReflectionTestUtils.setField(value, "updatedAt", updated); return value;
    }
    private static ImageAgentPromptVersion version(String key, int number, String prompt, String provider, double temperature, boolean enabled) {
        ImageAgentPromptVersion value = new ImageAgentPromptVersion(); value.setAgentKey(key); value.setPromptVersion(number); value.setSystemPrompt(prompt); value.setAiProviderId(provider); value.setTemperature(temperature); value.setEnabled(enabled); return value;
    }
    private static ImageStylePreset style(long id, String key, boolean builtIn, OffsetDateTime updated) {
        ImageStylePreset value = new ImageStylePreset(); value.setId(id); value.setPresetKey(key); value.setName("n"); value.setPositivePrompt("p"); value.setNegativePrompt("x"); value.setDescription("d"); value.setEnabled(true); value.setBuiltIn(builtIn); ReflectionTestUtils.setField(value, "updatedAt", updated); return value;
    }
    private static AiConfigRequest providerConfig(String active, AiProviderConfigItem... values) { AiConfigRequest request = new AiConfigRequest(); request.setActive(active); request.setProviders(List.of(values)); return request; }
    private static AiProviderConfigItem provider(String id, boolean enabled, String... caps) { AiProviderConfigItem value = new AiProviderConfigItem(); value.setId(id); value.setLabel(id); value.setType("openai-compatible"); value.setBaseUrl("https://provider.invalid/v1"); value.setModel(id); value.setEnabled(enabled); value.setCapabilities(List.of(caps)); return value; }
}
