package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aitaskcenter.config.ImageAgentCatalog;
import com.aitaskcenter.dto.AiConfigRequest;
import com.aitaskcenter.dto.AiProviderConfigItem;
import com.aitaskcenter.dto.ImageRunDtos.StartImageRunRequest;
import com.aitaskcenter.model.ImageAgentConfig;
import com.aitaskcenter.model.ImageAsset;
import com.aitaskcenter.model.ImageFlowConfig;
import com.aitaskcenter.model.ImageRun;
import com.aitaskcenter.model.ImageRunStep;
import com.aitaskcenter.model.ImageShot;
import com.aitaskcenter.model.ImageStylePreset;
import com.aitaskcenter.model.StoryRun;
import com.aitaskcenter.repository.ImageAgentConfigRepository;
import com.aitaskcenter.repository.ImageAssetRepository;
import com.aitaskcenter.repository.ImageFlowConfigRepository;
import com.aitaskcenter.repository.ImageRunRepository;
import com.aitaskcenter.repository.ImageRunStepRepository;
import com.aitaskcenter.repository.ImageShotRepository;
import com.aitaskcenter.repository.ImageStylePresetRepository;
import com.aitaskcenter.repository.StoryRunRepository;
import com.aitaskcenter.service.AiImageGenerationService.ImageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

class ImageRunExecutionServiceTest {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    @TempDir
    Path tempDir;
    private StoryRunRepository stories;
    private ImageRunRepository runs;
    private ImageRunStepRepository steps;
    private ImageShotRepository shots;
    private ImageAssetRepository assets;
    private ImageAgentConfigRepository agents;
    private ImageStylePresetRepository styles;
    private ImageFlowConfigRepository flows;
    private AiConfigService aiConfigs;
    private AiTextGenerationService textGeneration;
    private AiImageGenerationService imageGeneration;
    private ImageAssetStore assetStore;
    private ObjectMapper mapper;
    private StoryRun story;
    private ImageStylePreset style;
    private ImageFlowConfig flow;
    private AiProviderConfigItem textProvider;
    private AiProviderConfigItem imageProvider;
    private Map<String, String> generationOutputs;
    private List<ImageRunStep> savedSteps;
    private List<ImageShot> savedShots;
    private List<ImageAsset> savedAssets;
    private List<String> savedRunStatuses;
    private Map<String, ImageRunStep> startedSteps;
    private AtomicReference<ImageRun> currentRun;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        stories = mock(StoryRunRepository.class);
        runs = mock(ImageRunRepository.class);
        steps = mock(ImageRunStepRepository.class);
        shots = mock(ImageShotRepository.class);
        assets = mock(ImageAssetRepository.class);
        agents = mock(ImageAgentConfigRepository.class);
        styles = mock(ImageStylePresetRepository.class);
        flows = mock(ImageFlowConfigRepository.class);
        aiConfigs = mock(AiConfigService.class);
        textGeneration = mock(AiTextGenerationService.class);
        imageGeneration = mock(AiImageGenerationService.class);
        assetStore = mock(ImageAssetStore.class);
        mapper = new ObjectMapper().findAndRegisterModules();
        savedSteps = Collections.synchronizedList(new ArrayList<>());
        savedShots = Collections.synchronizedList(new ArrayList<>());
        savedAssets = Collections.synchronizedList(new ArrayList<>());
        savedRunStatuses = Collections.synchronizedList(new ArrayList<>());
        startedSteps = new ConcurrentHashMap<>();
        currentRun = new AtomicReference<>();

        story = story("A child walks in the park.");
        style = style();
        flow = flow();
        textProvider = provider("text-provider", "text-model", List.of("TEXT_GENERATION"));
        imageProvider = provider("image-provider", "image-model", List.of("IMAGE_GENERATION", "IMAGE_REFERENCE"));
        imageProvider.setType("  OPENAI-COMPATIBLE  ");
        imageProvider.setOptions(Map.of(
                "responseFormat", "  B64_JSON  ",
                "quality", "  HIGH  ",
                "size", "1536x864"));
        generationOutputs = validOutputs();

        when(stories.findByRunId("story-run-1")).thenReturn(java.util.Optional.of(story));
        when(styles.findById(7L)).thenReturn(java.util.Optional.of(style));
        when(flows.findByFlowKey(ImageFlowConfig.DEFAULT_FLOW_KEY)).thenReturn(java.util.Optional.of(flow));
        when(agents.findAllByOrderByAgentKeyAsc()).thenReturn(agentConfigs());
        when(aiConfigs.getProviderForExecution(anyString())).thenReturn(textProvider);
        AiConfigRequest allProviders = new AiConfigRequest();
        allProviders.setActive(textProvider.getId());
        allProviders.setProviders(List.of(textProvider, imageProvider));
        when(aiConfigs.getConfig()).thenReturn(allProviders);
        when(runs.saveAndFlush(any(ImageRun.class))).thenAnswer(invocation -> {
            ImageRun run = invocation.getArgument(0);
            savedRunStatuses.add(run.getStatus());
            currentRun.set(run);
            return run;
        });
        when(runs.findByRunId(anyString())).thenAnswer(invocation -> java.util.Optional.ofNullable(currentRun.get()));
        when(runs.save(any(ImageRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(steps.saveAndFlush(any(ImageRunStep.class))).thenAnswer(invocation -> {
            ImageRunStep step = invocation.getArgument(0);
            if ("RUNNING".equals(step.getStatus())) startedSteps.put(step.getNodeKey(), step);
            if (!savedSteps.contains(step)) savedSteps.add(step);
            return step;
        });
        when(steps.save(any(ImageRunStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(shots.saveAndFlush(any(ImageShot.class))).thenAnswer(invocation -> {
            ImageShot shot = invocation.getArgument(0);
            if (!savedShots.contains(shot)) savedShots.add(shot);
            return shot;
        });
        when(assets.saveAndFlush(any(ImageAsset.class))).thenAnswer(invocation -> {
            ImageAsset asset = invocation.getArgument(0);
            if (!savedAssets.contains(asset)) savedAssets.add(asset);
            return asset;
        });
        when(textGeneration.generateWithUsage(any(), anyString(), anyString(), anyDouble(), anyInt()))
                .thenAnswer(invocation -> generationResult(invocation.getArgument(1)));
        try {
            byte[] image = png(1536, 864);
            when(imageGeneration.generate(any(), anyString(), anyString(), anyInt(), anyInt(), any()))
                    .thenReturn(new ImageResult(image, "image/png", 1536, 864,
                            "request-1", Map.of("quality", "high")));
            when(assetStore.store(anyString(), anyString(), anyString(), any())).thenAnswer(invocation ->
                    new ImageAssetStore.StoredAsset(invocation.getArgument(0) + "/" + invocation.getArgument(1) + ".png",
                            "image/png", 1536, 864, "a".repeat(64)));
            when(assetStore.read(anyString(), anyString())).thenReturn(image);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (pool != null) {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void snapshotsValidatedInputsBeforeEnqueueing() {
        AtomicBoolean persistedBeforeEnqueue = new AtomicBoolean();
        TaskExecutor queueOnly = command -> persistedBeforeEnqueue.set(
                org.mockito.Mockito.mockingDetails(runs).getInvocations().stream()
                        .anyMatch(invocation -> invocation.getMethod().getName().equals("saveAndFlush")));
        ImageRunExecutionService service = service(queueOnly, new SyncTaskExecutor());

        var summary = service.createRun(new StartImageRunRequest("story-run-1", 7L));

        assertEquals("QUEUED", summary.status());
        assertTrue(persistedBeforeEnqueue.get());
        var captor = org.mockito.ArgumentCaptor.forClass(ImageRun.class);
        verify(runs).saveAndFlush(captor.capture());
        ImageRun run = captor.getValue();
        assertEquals(story.getFinalStory(), run.getStorySnapshot());
        assertEquals(story.getInputWordsJson(), run.getInputWordsJson());
        assertEquals(story.getTargetGrade(), run.getTargetGrade());
        assertFalse(run.getStyleSnapshotJson().isBlank());
        assertFalse(run.getFlowSnapshotJson().isBlank());
        assertFalse(run.getAgentSnapshotJson().isBlank());
        assertFalse(run.getAgentSnapshotJson().contains("secret-key"));
        assertTrue(run.getAgentSnapshotJson().contains("text-model"));
        assertTrue(run.getFlowSnapshotJson().contains("image-model"));
        assertTrue(run.getFlowSnapshotJson().contains("\"responseFormat\":\"b64_json\""));
        assertTrue(run.getFlowSnapshotJson().contains("\"quality\":\"high\""));
    }

    @Test
    void neverPersistsTextProviderOptionsInAgentSnapshots() {
        textProvider.setOptions(Map.of("quality", "text-option-secret"));

        service(command -> { }, new SyncTaskExecutor())
                .createRun(new StartImageRunRequest("story-run-1", 7L));

        var captor = org.mockito.ArgumentCaptor.forClass(ImageRun.class);
        verify(runs).saveAndFlush(captor.capture());
        assertFalse(captor.getValue().getAgentSnapshotJson().contains("text-option-secret"));
        assertTrue(captor.getValue().getAgentSnapshotJson().contains("\"options\":{}"));
    }

    @Test
    void rejectsInvalidSourceBeforePersistence() {
        story.setFinalStory("  ");
        assertThrows(IllegalArgumentException.class,
                () -> service(command -> { }, new SyncTaskExecutor())
                        .createRun(new StartImageRunRequest("story-run-1", 7L)));

        verify(runs, never()).saveAndFlush(any());
        verifyNoInteractions(textGeneration, imageGeneration);
    }

    @Test
    void rejectsCredentialBearingImageProviderUrlBeforePersistence() {
        imageProvider.setBaseUrl("https://provider.invalid/v1?token=hidden-query-secret");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(command -> { }, new SyncTaskExecutor())
                        .createRun(new StartImageRunRequest("story-run-1", 7L)));

        assertFalse(error.getMessage().contains("hidden-query-secret"));
        verify(runs, never()).saveAndFlush(any());
    }

    @Test
    void rejectsNonOpenAiCompatibleImageProviderProtocol() {
        imageProvider.setType("  anthropic-compatible  ");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(command -> { }, new SyncTaskExecutor())
                        .createRun(new StartImageRunRequest("story-run-1", 7L)));

        assertTrue(error.getMessage().contains("OpenAI"));
        verifyNoInteractions(runs, steps, textGeneration, imageGeneration);
    }

    @Test
    void rejectsNullImageProviderOptionValues() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("quality", null);
        imageProvider.setOptions(options);

        assertThrows(IllegalArgumentException.class,
                () -> service(command -> { }, new SyncTaskExecutor())
                        .createRun(new StartImageRunRequest("story-run-1", 7L)));

        verify(runs, never()).saveAndFlush(any(ImageRun.class));
    }

    @Test
    void rejectsNestedImageOptionsWithoutPersistingTheirSecret() {
        String nestedSecret = "nested-image-secret";
        imageProvider.setOptions(Map.of("quality", Map.of("secret", nestedSecret)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service(command -> { }, new SyncTaskExecutor())
                        .createRun(new StartImageRunRequest("story-run-1", 7L)));

        assertFalse(error.getMessage().contains(nestedSecret));
        verify(runs, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUnknownOrInvalidImageOptionValues() {
        List<Map<String, Object>> invalidOptions = List.of(
                Map.of("unknown", "secret-value"),
                Map.of("responseFormat", "url"),
                Map.of("quality", "ultra"),
                Map.of("size", "1024x1024"),
                Map.of("quality", "x".repeat(200)));

        for (Map<String, Object> invalid : invalidOptions) {
            imageProvider.setOptions(invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> service(command -> { }, new SyncTaskExecutor())
                            .createRun(new StartImageRunRequest("story-run-1", 7L)));
        }
        verify(runs, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUnwritableStorageBeforePersistingOrCallingAnyModel() {
        org.mockito.Mockito.doThrow(new IllegalStateException("not writable"))
                .when(assetStore).assertWritable();

        assertThrows(IllegalStateException.class,
                () -> service(command -> { }, new SyncTaskExecutor())
                        .createRun(new StartImageRunRequest("story-run-1", 7L)));

        verify(assetStore).assertWritable();
        verifyNoInteractions(runs, steps, textGeneration, imageGeneration);
    }

    @Test
    void persistsFailedWhenTheRunQueueRejectsWork() {
        TaskExecutor rejecting = command -> { throw new java.util.concurrent.RejectedExecutionException("full"); };
        ImageRunExecutionService service = service(rejecting, new SyncTaskExecutor());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createRun(new StartImageRunRequest("story-run-1", 7L)));

        assertTrue(error.getMessage().contains("队列"));
        var captor = org.mockito.ArgumentCaptor.forClass(ImageRun.class);
        verify(runs, org.mockito.Mockito.times(2)).saveAndFlush(captor.capture());
        ImageRun failed = captor.getAllValues().get(1);
        assertEquals("FAILED", failed.getStatus());
        assertNotNull(failed.getFinishedAt());
    }

    @Test
    void plansWithNineAgentsInFixedDependencyOrderAndPersistsAuditData() {
        ImageRunExecutionService service = service(new SyncTaskExecutor(), new SyncTaskExecutor());

        service.createRun(new StartImageRunRequest("story-run-1", 7L));

        List<ImageRunStep> ordered = savedSteps.stream()
                .filter(step -> "AGENT".equals(step.getNodeKind()))
                .sorted(Comparator.comparingInt(ImageRunStep::getSequence)).toList();
        assertEquals(ImageAgentCatalog.agents().stream().map(ImageAgentCatalog.NodeDefinition::key).toList(),
                ordered.stream().map(ImageRunStep::getNodeKey).toList());
        assertTrue(inputFor("image-storyboard-director").contains("actionStoryboardProposal"));
        assertTrue(inputFor("image-storyboard-director").contains("learningStoryboardProposal"));
        assertTrue(inputFor("image-storyboard-director").contains("Amy walks before lunch"));
        assertFalse(inputFor("image-storyboard-director").contains("STORYBOARD_PROPOSAL_JSON_BEGIN"));
        for (ImageRunStep step : ordered) {
            assertEquals("COMPLETED", step.getStatus());
            assertFalse(step.getInputJson().isBlank());
            assertFalse(step.getRawOutput().isBlank());
            assertFalse(step.getParsedOutputJson().isBlank());
            assertEquals(11, step.getInputTokens());
            assertEquals(7, step.getOutputTokens());
            assertEquals(18, step.getTotalTokens());
            assertEquals("text-provider", step.getProviderId());
            assertEquals("text-model", step.getProviderModel());
            assertTrue(step.getPromptVersion() > 0);
        }
        var runCaptor = org.mockito.ArgumentCaptor.forClass(ImageRun.class);
        verify(runs, org.mockito.Mockito.atLeast(2)).saveAndFlush(runCaptor.capture());
        ImageRun planned = runCaptor.getAllValues().get(runCaptor.getAllValues().size() - 1);
        assertEquals(9L * 18L, planned.getTotalTextTokens());
        assertEquals(3, currentRun.get().getExpectedImageCount());
    }

    @Test
    void generatesCharacterThenLocationReferencesAndExactlyOneBaseImagePerShot() {
        ImageRunExecutionService service = service(new SyncTaskExecutor(), new SyncTaskExecutor());

        service.createRun(new StartImageRunRequest("story-run-1", 7L));

        InOrder order = org.mockito.Mockito.inOrder(imageGeneration);
        order.verify(imageGeneration).generate(any(), org.mockito.ArgumentMatchers.contains("Amy portrait"),
                anyString(), org.mockito.ArgumentMatchers.eq(1536), org.mockito.ArgumentMatchers.eq(864),
                org.mockito.ArgumentMatchers.eq(List.of()));
        order.verify(imageGeneration).generate(any(), org.mockito.ArgumentMatchers.contains("Green park"),
                anyString(), org.mockito.ArgumentMatchers.eq(1536), org.mockito.ArgumentMatchers.eq(864),
                org.mockito.ArgumentMatchers.eq(List.of()));
        order.verify(imageGeneration).generate(any(), org.mockito.ArgumentMatchers.contains("Amy walks"),
                anyString(), org.mockito.ArgumentMatchers.eq(1536), org.mockito.ArgumentMatchers.eq(864),
                org.mockito.ArgumentMatchers.argThat(references -> references.size() == 2));
        verify(imageGeneration, org.mockito.Mockito.times(3))
                .generate(any(), anyString(), anyString(), anyInt(), anyInt(), any());

        assertEquals(List.of("QUEUED", "PLANNING", "GENERATING_REFERENCES", "GENERATING_SHOTS",
                "COMPOSITING", "COMPLETED"), distinctConsecutive(savedRunStatuses));
        assertEquals(1, savedShots.size());
        ImageShot shot = savedShots.get(0);
        assertEquals("shot-1", shot.getShotKey());
        assertEquals(1, shot.getSceneIndex());
        assertEquals(1, shot.getShotIndex());
        assertEquals(1, shot.getSequence());
        assertEquals("Amy walks", shot.getSourceExcerpt());
        assertEquals("show Amy", shot.getVisualGoal());
        assertEquals("amy", shot.getSpeaker());
        assertEquals("Hello!", shot.getDialogue());
        assertEquals("a short narration", shot.getCaption());
        assertEquals("COMPLETED", shot.getStatus());
        assertTrue(shot.getTextAnchorJson().contains("0.2"));
        assertTrue(shot.getReferenceAssetKeysJson().contains("asset-park"));
        assertEquals(List.of("REFERENCE", "REFERENCE", "BASE", "FINAL"),
                savedAssets.stream().map(ImageAsset::getAssetType).toList());
        assertTrue(savedAssets.stream().noneMatch(asset -> asset.getMetadataJson().contains("secret-key")));
        assertEquals(3, currentRun.get().getExpectedImageCount());
        assertEquals(3, currentRun.get().getGeneratedImageCount());
        assertEquals("COMPLETED", currentRun.get().getStatus());
        assertNotNull(currentRun.get().getFinishedAt());
    }

    @Test
    void persistsTheThreeGenerationProgramsAfterTheNineAgentsInActualOrder() {
        service(new SyncTaskExecutor(), new SyncTaskExecutor())
                .createRun(new StartImageRunRequest("story-run-1", 7L));

        List<ImageRunStep> ordered = savedSteps.stream()
                .sorted(Comparator.comparingInt(ImageRunStep::getSequence)).toList();
        assertEquals(List.of("reference-image-generator", "shot-image-generator", "text-compositor"),
                ordered.stream().filter(step -> "PROGRAM".equals(step.getNodeKind()))
                        .map(ImageRunStep::getNodeKey).toList());
        assertEquals(List.of(10, 11, 12), ordered.stream()
                .filter(step -> "PROGRAM".equals(step.getNodeKind())).map(ImageRunStep::getSequence).toList());
        for (ImageRunStep step : ordered.stream().filter(value -> "PROGRAM".equals(value.getNodeKind())).toList()) {
            assertEquals("generation", step.getStageKey());
            assertEquals("COMPLETED", step.getStatus());
            assertFalse(step.getInputJson().isBlank());
            assertFalse(step.getRawOutput().isBlank());
            assertFalse(step.getParsedOutputJson().isBlank());
            assertEquals(0, step.getTotalTokens());
        }
        assertEquals("image-provider", ordered.get(9).getProviderId());
        assertEquals("image-model", ordered.get(10).getProviderModel());
        assertTrue(ordered.get(11).getProviderId() == null || ordered.get(11).getProviderId().isBlank());
    }

    @Test
    void failsBeforeTheShotCallWhenADeclaredStoredReferenceCannotBeRead() {
        when(assetStore.read(org.mockito.ArgumentMatchers.contains("reference-asset-park"), anyString()))
                .thenThrow(new IllegalArgumentException("图片资产不存在"));

        service(new SyncTaskExecutor(), new SyncTaskExecutor())
                .createRun(new StartImageRunRequest("story-run-1", 7L));

        verify(imageGeneration, org.mockito.Mockito.times(2))
                .generate(any(), anyString(), anyString(), anyInt(), anyInt(), any());
        assertEquals(List.of("REFERENCE", "REFERENCE"),
                savedAssets.stream().map(ImageAsset::getAssetType).toList());
        assertEquals("FAILED", currentRun.get().getStatus());
        assertTrue(currentRun.get().getErrorMessage().contains("图片资产不存在"));
        assertNotNull(currentRun.get().getFinishedAt());
    }

    @Test
    void rejectsTwentyOneReferencesBeforeAnyImageOrProgramStep() {
        assertPreflightFailsBeforeImages(preflightWithReferences(21, 0));
    }

    @Test
    void rejectsNineReferencesOnOneShotBeforeAnyImageOrProgramStep() {
        assertPreflightFailsBeforeImages(preflightWithReferences(9, 9));
    }

    @Test
    void rejectsDuplicateNormalizedReferenceTargetsBeforeAnyImageOrProgramStep() {
        String duplicate = "{\"referenceAssets\":["
                + "{\"assetKey\":\"asset-amy-1\",\"type\":\"CHARACTER\",\"target\":\"Amy\",\"prompt\":\"Amy portrait, no text\",\"negativePrompt\":\"text\"},"
                + "{\"assetKey\":\"asset-amy-2\",\"type\":\"character\",\"target\":\" amy \",\"prompt\":\"Amy portrait, no text\",\"negativePrompt\":\"text\"}],"
                + "\"shots\":[],\"auditSummary\":\"checked\"}";
        assertPreflightFailsBeforeImages(duplicate);
    }

    @Test
    void rejectsUnsafeOrOverlongStorageKeysBeforeAnyImageOrProgramStep() {
        assertPreflightFailsBeforeImages(preflightJson().replace("asset-amy", "asset/amy"));

        resetExecutionObservations();
        assertPreflightFailsBeforeImages(preflightJson().replace("asset-amy", "a".repeat(101)));

        resetExecutionObservations();
        assertPreflightFailsBeforeImages(preflightJson().replace("shot-1", "shot/1"));

        resetExecutionObservations();
        assertPreflightFailsBeforeImages(preflightJson().replace("shot-1", "s".repeat(81)));
    }

    @Test
    void rejectsTextThatCannotBeComposedBeforeAnyImageOrProgramStep() {
        String oversizedDialogue = "Amy explains every tiny detail without stopping. ".repeat(200);
        assertPreflightFailsBeforeImages(preflightJson().replace("Hello!", oversizedDialogue));
    }

    @Test
    void sanitizesAndBoundsProviderRequestIdsBeforeAssetPersistence() {
        try {
            byte[] image = png(1536, 864);
            when(imageGeneration.generate(any(), anyString(), anyString(), anyInt(), anyInt(), any()))
                    .thenReturn(new ImageResult(image, "image/png", 1536, 864,
                            "request\r\n" + "x".repeat(220), Map.of()));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }

        service(new SyncTaskExecutor(), new SyncTaskExecutor())
                .createRun(new StartImageRunRequest("story-run-1", 7L));

        assertTrue(savedAssets.stream().filter(asset -> "REFERENCE".equals(asset.getAssetType()))
                .allMatch(asset -> asset.getProviderRequestId().length() <= 180
                        && !asset.getProviderRequestId().contains("\r")
                        && !asset.getProviderRequestId().contains("\n")));
        assertEquals("COMPLETED", currentRun.get().getStatus());
    }

    @Test
    void keepsCompletedReferencesAndNeverRetriesWhenShotGenerationFails() {
        try {
            byte[] image = png(1536, 864);
            org.mockito.Mockito.doReturn(new ImageResult(image, "image/png", 1536, 864,
                            "reference-1", Map.of()))
                    .doReturn(new ImageResult(image, "image/png", 1536, 864,
                            "reference-2", Map.of()))
                    .doThrow(new IllegalArgumentException("provider-shot-failure"))
                    .when(imageGeneration).generate(any(), anyString(), anyString(), anyInt(), anyInt(), any());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }

        service(new SyncTaskExecutor(), new SyncTaskExecutor())
                .createRun(new StartImageRunRequest("story-run-1", 7L));

        verify(imageGeneration, org.mockito.Mockito.times(3))
                .generate(any(), anyString(), anyString(), anyInt(), anyInt(), any());
        assertEquals(List.of("REFERENCE", "REFERENCE"),
                savedAssets.stream().map(ImageAsset::getAssetType).toList());
        assertEquals("FAILED", currentRun.get().getStatus());
        assertEquals(2, currentRun.get().getGeneratedImageCount());
        assertTrue(currentRun.get().getErrorMessage().contains("provider-shot-failure"));
        ImageRunStep program = savedSteps.stream()
                .filter(step -> "shot-image-generator".equals(step.getNodeKey())).findFirst().orElseThrow();
        assertEquals("FAILED", program.getStatus());
        assertFalse(savedSteps.stream().anyMatch(step -> "text-compositor".equals(step.getNodeKey())));
    }

    @Test
    void removesJustWrittenFileAndPreservesOriginalErrorWhenAssetDatabaseSaveFails() throws Exception {
        Path storageRoot = Files.createDirectory(tempDir.toRealPath().resolve("image-assets"));
        assetStore = new ImageAssetStore(storageRoot.toString());
        when(assets.saveAndFlush(any(ImageAsset.class)))
                .thenThrow(new IllegalStateException("db-write-original"));

        service(new SyncTaskExecutor(), new SyncTaskExecutor())
                .createRun(new StartImageRunRequest("story-run-1", 7L));

        assertEquals("FAILED", currentRun.get().getStatus());
        assertTrue(currentRun.get().getErrorMessage().contains("db-write-original"));
        verify(imageGeneration, org.mockito.Mockito.times(1))
                .generate(any(), anyString(), anyString(), anyInt(), anyInt(), any());
        try (var files = Files.walk(storageRoot)) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void continuesWithMergedPlanningEntityReturnedBySaveAndFlush() {
        RunMergeTracker tracker = simulateMergedPlanningEntity();

        service(new SyncTaskExecutor(), new SyncTaskExecutor())
                .createRun(new StartImageRunRequest("story-run-1", 7L));

        assertNotNull(tracker.queued().get());
        assertNotNull(tracker.merged().get());
        assertNotNull(tracker.finalSave().get());
        assertNotSame(tracker.queued().get(), tracker.merged().get());
        assertSame(tracker.merged().get(), tracker.finalSave().get());
    }

    @Test
    void runsBothDeclaredParallelLayersConcurrentlyAndKeepsLaterAgentsSequential() throws Exception {
        CountDownLatch foundation = new CountDownLatch(3);
        CountDownLatch storyboards = new CountDownLatch(2);
        List<String> callStarts = Collections.synchronizedList(new ArrayList<>());
        org.mockito.Mockito.doAnswer(invocation -> {
                    String key = keyFromPrompt(invocation.getArgument(1));
                    callStarts.add(key);
                    if (List.of("image-story-analyst", "image-continuity-designer", "image-art-director").contains(key)) {
                        foundation.countDown();
                        assertTrue(foundation.await(2, TimeUnit.SECONDS), "Agents 1-3 did not overlap");
                    }
                    if (List.of("image-action-storyboarder", "image-learning-storyboarder").contains(key)) {
                        storyboards.countDown();
                        assertTrue(storyboards.await(2, TimeUnit.SECONDS), "Agents 4-5 did not overlap");
                    }
                    return generationResult(invocation.getArgument(1));
                }).when(textGeneration).generateWithUsage(any(), anyString(), anyString(), anyDouble(), anyInt());
        pool = Executors.newFixedThreadPool(5);
        TaskExecutor realPool = command -> pool.execute(command);

        service(new SyncTaskExecutor(), realPool)
                .createRun(new StartImageRunRequest("story-run-1", 7L));

        assertEquals(0, foundation.getCount());
        assertEquals(0, storyboards.getCount());
        List<String> tail = callStarts.subList(callStarts.size() - 4, callStarts.size());
        assertEquals(List.of("image-storyboard-director", "image-reference-planner",
                "image-shot-prompt-engineer", "image-prompt-preflight"), tail);
    }

    @Test
    void failsTheStructuredStepAndRunWithoutCallingImagesOrLaterAgents() {
        generationOutputs.put("image-storyboard-director", "not-json");
        ImageRunExecutionService service = service(new SyncTaskExecutor(), new SyncTaskExecutor());

        service.createRun(new StartImageRunRequest("story-run-1", 7L));

        ImageRunStep failedStep = savedSteps.stream()
                .filter(step -> step.getNodeKey().equals("image-storyboard-director"))
                .findFirst().orElseThrow();
        assertEquals("FAILED", failedStep.getStatus());
        assertEquals("not-json", failedStep.getRawOutput());
        assertTrue(failedStep.getParsedOutputJson() == null || failedStep.getParsedOutputJson().isBlank());
        assertFalse(savedSteps.stream().anyMatch(step -> step.getNodeKey().equals("image-reference-planner")));
        var runCaptor = org.mockito.ArgumentCaptor.forClass(ImageRun.class);
        verify(runs, org.mockito.Mockito.atLeast(2)).saveAndFlush(runCaptor.capture());
        ImageRun failedRun = runCaptor.getAllValues().get(runCaptor.getAllValues().size() - 1);
        assertEquals("FAILED", failedRun.getStatus());
        assertNotNull(failedRun.getFinishedAt());
        assertEquals(6L * 18L, failedRun.getTotalTextTokens());
        verifyNoInteractions(imageGeneration);
    }

    @Test
    void neverPersistsOversizedAgentRawOutput() {
        String secretTail = "secret-tail-must-not-be-stored";
        generationOutputs.put("image-story-analyst", "x".repeat(600_000) + secretTail);

        service(new SyncTaskExecutor(), new SyncTaskExecutor())
                .createRun(new StartImageRunRequest("story-run-1", 7L));

        ImageRunStep failed = savedSteps.stream()
                .filter(step -> step.getNodeKey().equals("image-story-analyst"))
                .findFirst().orElseThrow();
        assertEquals("FAILED", failed.getStatus());
        assertNotNull(failed.getRawOutput());
        assertTrue(failed.getRawOutput().length() <= 512_000);
        assertFalse(failed.getRawOutput().contains(secretTail));
    }

    @Test
    void estimatesNegativeProviderTokenUsage() {
        stubUsage(-1, -2, -3);

        service(new SyncTaskExecutor(), new SyncTaskExecutor())
                .createRun(new StartImageRunRequest("story-run-1", 7L));

        for (ImageRunStep step : savedSteps.stream().filter(value -> "AGENT".equals(value.getNodeKind())).toList()) {
            assertTrue(step.getInputTokens() > 0);
            assertTrue(step.getOutputTokens() > 0);
            assertEquals(step.getInputTokens() + step.getOutputTokens(), step.getTotalTokens());
        }
    }

    @Test
    void replacesUnderreportedProviderTotalWithInputAndOutputSum() {
        stubUsage(10, 20, 5);

        service(new SyncTaskExecutor(), new SyncTaskExecutor())
                .createRun(new StartImageRunRequest("story-run-1", 7L));

        assertTrue(savedSteps.stream().filter(step -> "AGENT".equals(step.getNodeKind()))
                .allMatch(step -> step.getTotalTokens() == 30));
        assertEquals(270, currentRun.get().getTotalTextTokens());
    }

    @Test
    void saturatesStepAndRunTokenAdditionInsteadOfOverflowing() {
        stubUsage(Long.MAX_VALUE, Long.MAX_VALUE, 0);

        service(new SyncTaskExecutor(), new SyncTaskExecutor())
                .createRun(new StartImageRunRequest("story-run-1", 7L));

        assertTrue(savedSteps.stream().filter(step -> "AGENT".equals(step.getNodeKind()))
                .allMatch(step -> step.getTotalTokens() == Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, currentRun.get().getTotalTextTokens());
    }

    @Test
    void reloadsTheLatestRunBeforePersistingStructuredFailure() {
        generationOutputs.put("image-storyboard-director", "not-json");
        RunMergeTracker tracker = simulateMergedPlanningEntity();

        service(new SyncTaskExecutor(), new SyncTaskExecutor())
                .createRun(new StartImageRunRequest("story-run-1", 7L));

        assertNotNull(tracker.merged().get());
        assertNotNull(tracker.failedSave().get());
        assertSame(tracker.merged().get(), tracker.failedSave().get());
        assertEquals("FAILED", tracker.failedSave().get().getStatus());
        verify(runs, org.mockito.Mockito.atLeast(2)).findByRunId(anyString());
    }

    private ImageRunExecutionService service(TaskExecutor runExecutor, TaskExecutor planningExecutor) {
        return new ImageRunExecutionService(stories, runs, steps, shots, assets, agents, styles, flows, aiConfigs,
                textGeneration, imageGeneration, assetStore, new ImageTextCompositor(), mapper,
                runExecutor, planningExecutor);
    }

    private static List<String> distinctConsecutive(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (result.isEmpty() || !result.get(result.size() - 1).equals(value)) result.add(value);
        }
        return result;
    }

    private void assertPreflightFailsBeforeImages(String preflightJson) {
        generationOutputs.put("image-prompt-preflight", wrap("PREFLIGHT_PLAN", preflightJson));

        service(new SyncTaskExecutor(), new SyncTaskExecutor())
                .createRun(new StartImageRunRequest("story-run-1", 7L));

        verifyNoInteractions(imageGeneration);
        assertFalse(savedSteps.stream().anyMatch(step -> "PROGRAM".equals(step.getNodeKind())));
        assertEquals("FAILED", currentRun.get().getStatus());
    }

    private void resetExecutionObservations() {
        org.mockito.Mockito.clearInvocations(imageGeneration);
        savedSteps.clear();
        savedShots.clear();
        savedAssets.clear();
        savedRunStatuses.clear();
        startedSteps.clear();
    }

    private RunMergeTracker simulateMergedPlanningEntity() {
        RunMergeTracker tracker = new RunMergeTracker(new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>(), new AtomicReference<>());
        org.mockito.Mockito.doAnswer(invocation -> {
            ImageRun run = invocation.getArgument(0);
            if ("QUEUED".equals(run.getStatus())) {
                tracker.queued().set(run);
                currentRun.set(run);
                return run;
            }
            if ("PLANNING".equals(run.getStatus()) && run.getExpectedImageCount() == 0
                    && tracker.merged().get() == null) {
                ImageRun merged = mergedRun(run);
                tracker.merged().set(merged);
                currentRun.set(merged);
                return merged;
            }
            if ("FAILED".equals(run.getStatus())) tracker.failedSave().set(run);
            else tracker.finalSave().set(run);
            currentRun.set(run);
            return run;
        }).when(runs).saveAndFlush(any(ImageRun.class));
        return tracker;
    }

    private static ImageRun mergedRun(ImageRun source) {
        ImageRun merged = new ImageRun();
        merged.setRunId(source.getRunId());
        merged.setStoryRunId(source.getStoryRunId());
        merged.setStorySnapshot(source.getStorySnapshot());
        merged.setInputWordsJson(source.getInputWordsJson());
        merged.setTargetGrade(source.getTargetGrade());
        merged.setStylePresetId(source.getStylePresetId());
        merged.setStyleSnapshotJson(source.getStyleSnapshotJson());
        merged.setFlowSnapshotJson(source.getFlowSnapshotJson());
        merged.setAgentSnapshotJson(source.getAgentSnapshotJson());
        merged.setStatus(source.getStatus());
        merged.setExpectedImageCount(source.getExpectedImageCount());
        merged.setGeneratedImageCount(source.getGeneratedImageCount());
        merged.setTotalTextTokens(source.getTotalTextTokens());
        merged.setStartedAt(source.getStartedAt());
        merged.setVersion(source.getVersion() + 1);
        return merged;
    }

    private AiTextGenerationService.GenerationResult generationResult(String systemPrompt) {
        String key = keyFromPrompt(systemPrompt);
        ImageRunStep started = startedSteps.get(key);
        assertNotNull(started, "step must be persisted before provider invocation");
        assertEquals("RUNNING", started.getStatus());
        assertFalse(started.getInputJson().isBlank());
        return new AiTextGenerationService.GenerationResult(generationOutputs.get(key), 11, 7, 18);
    }

    private void stubUsage(long inputTokens, long outputTokens, long totalTokens) {
        org.mockito.Mockito.doAnswer(invocation -> {
            String key = keyFromPrompt(invocation.getArgument(1));
            assertNotNull(startedSteps.get(key));
            return new AiTextGenerationService.GenerationResult(
                    generationOutputs.get(key), inputTokens, outputTokens, totalTokens);
        }).when(textGeneration).generateWithUsage(any(), anyString(), anyString(), anyDouble(), anyInt());
    }

    private String inputFor(String key) {
        return savedSteps.stream().filter(step -> key.equals(step.getNodeKey()))
                .findFirst().orElseThrow().getInputJson();
    }

    private static String keyFromPrompt(String prompt) {
        return prompt.substring("PROMPT:".length());
    }

    private static StoryRun story(String finalStory) {
        StoryRun value = new StoryRun();
        value.setRunId("story-run-1");
        value.setInputWordsJson("[{\"word\":\"park\",\"meaning\":\"公园\"}]");
        value.setTargetGrade("三年级上册");
        value.setStatus("COMPLETED");
        value.setFinalStory(finalStory);
        return value;
    }

    private static ImageStylePreset style() {
        ImageStylePreset value = new ImageStylePreset();
        value.setId(7L);
        value.setPresetKey("watercolor-storybook");
        value.setName("水彩绘本");
        value.setPositivePrompt("warm watercolor");
        value.setNegativePrompt("text, watermark");
        value.setDescription("soft and warm");
        value.setEnabled(true);
        value.setBuiltIn(true);
        return value;
    }

    private static ImageFlowConfig flow() {
        ImageFlowConfig value = ImageFlowConfig.defaults();
        value.setImageProviderId("image-provider");
        return value;
    }

    private static AiProviderConfigItem provider(String id, String model, List<String> capabilities) {
        AiProviderConfigItem value = new AiProviderConfigItem();
        value.setId(id);
        value.setLabel(id);
        value.setType("openai-compatible");
        value.setBaseUrl("https://provider.invalid/v1");
        value.setApiKey("secret-key");
        value.setModel(model);
        value.setMaxTokens(4096);
        value.setCapabilities(capabilities);
        value.setEnabled(true);
        return value;
    }

    private static List<ImageAgentConfig> agentConfigs() {
        return ImageAgentCatalog.agents().stream().map(definition -> {
            ImageAgentConfig config = new ImageAgentConfig();
            config.setAgentKey(definition.key());
            config.setName(definition.name());
            config.setRoleType(definition.roleType());
            config.setDescription(definition.description());
            config.setSystemPrompt("PROMPT:" + definition.key());
            config.setAiProviderId("text-provider");
            config.setTemperature(0.2);
            config.setEnabled(true);
            config.setPromptVersion(3);
            return config;
        }).toList();
    }

    private static Map<String, String> validOutputs() {
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("image-story-analyst", wrap("STORY_ANALYSIS", storyAnalysisJson()));
        outputs.put("image-continuity-designer", wrap("CONTINUITY_BIBLE", continuityBibleJson()));
        outputs.put("image-art-director", wrap("STYLE_BIBLE", styleBibleJson()));
        outputs.put("image-action-storyboarder", wrap("STORYBOARD_PROPOSAL", storyboardProposalJson()));
        outputs.put("image-learning-storyboarder", wrap("STORYBOARD_PROPOSAL", storyboardProposalJson()));
        outputs.put("image-storyboard-director", wrap("FINAL_STORYBOARD", finalStoryboardJson()));
        outputs.put("image-reference-planner", wrap("REFERENCE_PLAN", referencePlanJson()));
        outputs.put("image-shot-prompt-engineer", wrap("SHOT_PROMPT_PLAN", shotPromptPlanJson()));
        outputs.put("image-prompt-preflight", wrap("PREFLIGHT_PLAN", preflightJson()));
        return outputs;
    }

    private static String wrap(String schema, String json) {
        return "<" + schema + "_JSON_BEGIN>\n" + json + "\n<" + schema + "_JSON_END>";
    }

    private static String storyAnalysisJson() {
        return "{\"scenes\":[{\"sceneIndex\":1,\"title\":\"Park visit\",\"sourceExcerpt\":\"Amy walks\",\"summary\":\"Amy visits the park\"}],"
                + "\"beats\":[{\"beatKey\":\"beat-1\",\"sceneIndex\":1,\"order\":1,\"action\":\"Amy walks before lunch\",\"temporalMoment\":\"before lunch\"}],"
                + "\"characters\":[{\"characterKey\":\"amy\",\"name\":\"Amy\",\"description\":\"A child\"}],"
                + "\"locations\":[{\"locationKey\":\"park\",\"name\":\"Park\",\"description\":\"Green park\"}],"
                + "\"props\":[{\"propKey\":\"ball\",\"name\":\"Ball\",\"description\":\"Red ball\"}],"
                + "\"dialogues\":[{\"sceneIndex\":1,\"speaker\":\"amy\",\"text\":\"Hello!\"}],\"narration\":[{\"sceneIndex\":1,\"text\":\"A short narration\"}]}";
    }

    private static String continuityBibleJson() {
        return "{\"characters\":[{\"characterKey\":\"amy\",\"name\":\"Amy\",\"visualDescription\":\"brown hair\",\"clothing\":\"blue coat\",\"colors\":\"blue\",\"proportions\":\"child\",\"expressionRules\":\"kind\"}],"
                + "\"props\":[{\"propKey\":\"ball\",\"visualDescription\":\"round\",\"colors\":\"red\",\"invariants\":\"round\"}],\"invariants\":[\"same coat\"],\"forbiddenChanges\":[\"no age change\"]}";
    }

    private static String styleBibleJson() {
        return "{\"palette\":\"blue\",\"renderingStyle\":\"warm watercolor\",\"lighting\":\"soft\",\"cameraRules\":\"eye level\",\"environmentRules\":\"calm\",\"negativeRules\":[\"no text\"]}";
    }

    private static String storyboardProposalJson() {
        return "{\"shots\":[{\"sceneIndex\":1,\"beat\":\"beat-1\",\"action\":\"Amy walks before lunch\",\"characters\":[\"amy\"],\"location\":\"park\",\"dialogue\":\"Hello!\",\"narration\":\"A short narration\",\"splitReason\":\"opening\"}]}";
    }

    private static String finalStoryboardJson() {
        return "{\"shots\":[{\"shotKey\":\"shot-1\",\"sceneIndex\":1,\"shotIndex\":1,\"sourceExcerpt\":\"Amy walks\",\"visualGoal\":\"show Amy\",\"dialogue\":\"Hello!\",\"narration\":\"a short narration\",\"speaker\":\"amy\",\"textAnchor\":{\"x\":0.2,\"y\":0.3}}]}";
    }

    private static String referencePlanJson() {
        return "{\"referenceAssets\":[{\"assetKey\":\"asset-park\",\"type\":\"LOCATION\",\"target\":\"park\",\"prompt\":\"Green park, no text\",\"negativePrompt\":\"text\"},"
                + "{\"assetKey\":\"asset-amy\",\"type\":\"CHARACTER\",\"target\":\"amy\",\"prompt\":\"Amy portrait, no text\",\"negativePrompt\":\"text, watermark\"}]}";
    }

    private static String shotPromptPlanJson() {
        return "{\"shots\":[{\"shotKey\":\"shot-1\",\"prompt\":\"Amy walks, no text\",\"negativePrompt\":\"text, words\",\"referenceAssetKeys\":[\"asset-amy\",\"asset-park\"]}]}";
    }

    private static String preflightJson() {
        return "{\"referenceAssets\":[{\"assetKey\":\"asset-park\",\"type\":\"LOCATION\",\"target\":\"park\",\"prompt\":\"Green park, no text\",\"negativePrompt\":\"text\"},"
                + "{\"assetKey\":\"asset-amy\",\"type\":\"CHARACTER\",\"target\":\"amy\",\"prompt\":\"Amy portrait, no text\",\"negativePrompt\":\"text\"}],"
                + "\"shots\":[{\"shotKey\":\"shot-1\",\"sceneIndex\":1,\"shotIndex\":1,\"prompt\":\"Amy walks, no text\",\"negativePrompt\":\"text\",\"referenceAssetKeys\":[\"asset-amy\",\"asset-park\"],\"speaker\":\"amy\",\"dialogue\":\"Hello!\",\"narration\":\"a short narration\",\"textAnchor\":{\"x\":0.2,\"y\":0.3}}],\"auditSummary\":\"checked\"}";
    }

    private static String preflightWithReferences(int referenceCount, int keysOnShot) {
        StringBuilder assets = new StringBuilder("[");
        StringBuilder keys = new StringBuilder("[");
        for (int index = 1; index <= referenceCount; index++) {
            if (index > 1) assets.append(',');
            assets.append("{\"assetKey\":\"asset-").append(index)
                    .append("\",\"type\":\"CHARACTER\",\"target\":\"character-").append(index)
                    .append("\",\"prompt\":\"Character portrait, no text\",\"negativePrompt\":\"text\"}");
        }
        for (int index = 1; index <= keysOnShot; index++) {
            if (index > 1) keys.append(',');
            keys.append("\"asset-").append(index).append("\"");
        }
        assets.append(']');
        keys.append(']');
        String shots = keysOnShot == 0 ? "[]" : "[{\"shotKey\":\"shot-1\",\"sceneIndex\":1,\"shotIndex\":1,"
                + "\"prompt\":\"Amy walks, no text\",\"negativePrompt\":\"text\",\"referenceAssetKeys\":" + keys
                + ",\"speaker\":\"amy\",\"dialogue\":\"Hello!\",\"narration\":\"a short narration\",\"textAnchor\":{\"x\":0.2,\"y\":0.3}}]";
        return "{\"referenceAssets\":" + assets + ",\"shots\":" + shots + ",\"auditSummary\":\"checked\"}";
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(72, 112, 160));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!javax.imageio.ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG unavailable");
        return output.toByteArray();
    }

    private record RunMergeTracker(
            AtomicReference<ImageRun> queued,
            AtomicReference<ImageRun> merged,
            AtomicReference<ImageRun> finalSave,
            AtomicReference<ImageRun> failedSave) {
    }
}
