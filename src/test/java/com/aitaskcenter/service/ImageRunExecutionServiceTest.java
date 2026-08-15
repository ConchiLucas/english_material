package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.aitaskcenter.model.ImageFlowConfig;
import com.aitaskcenter.model.ImageRun;
import com.aitaskcenter.model.ImageRunStep;
import com.aitaskcenter.model.ImageStylePreset;
import com.aitaskcenter.model.StoryRun;
import com.aitaskcenter.repository.ImageAgentConfigRepository;
import com.aitaskcenter.repository.ImageFlowConfigRepository;
import com.aitaskcenter.repository.ImageRunRepository;
import com.aitaskcenter.repository.ImageRunStepRepository;
import com.aitaskcenter.repository.ImageStylePresetRepository;
import com.aitaskcenter.repository.StoryRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

class ImageRunExecutionServiceTest {
    private StoryRunRepository stories;
    private ImageRunRepository runs;
    private ImageRunStepRepository steps;
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
    private Map<String, ImageRunStep> startedSteps;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        stories = mock(StoryRunRepository.class);
        runs = mock(ImageRunRepository.class);
        steps = mock(ImageRunStepRepository.class);
        agents = mock(ImageAgentConfigRepository.class);
        styles = mock(ImageStylePresetRepository.class);
        flows = mock(ImageFlowConfigRepository.class);
        aiConfigs = mock(AiConfigService.class);
        textGeneration = mock(AiTextGenerationService.class);
        imageGeneration = mock(AiImageGenerationService.class);
        assetStore = mock(ImageAssetStore.class);
        mapper = new ObjectMapper().findAndRegisterModules();
        savedSteps = Collections.synchronizedList(new ArrayList<>());
        startedSteps = new ConcurrentHashMap<>();

        story = story("A child walks in the park.");
        style = style();
        flow = flow();
        textProvider = provider("text-provider", "text-model", List.of("TEXT_GENERATION"));
        imageProvider = provider("image-provider", "image-model", List.of("IMAGE_GENERATION", "IMAGE_REFERENCE"));
        imageProvider.setType("  OPENAI-COMPATIBLE  ");
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
        when(runs.saveAndFlush(any(ImageRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(runs.save(any(ImageRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(steps.saveAndFlush(any(ImageRunStep.class))).thenAnswer(invocation -> {
            ImageRunStep step = invocation.getArgument(0);
            if ("RUNNING".equals(step.getStatus())) startedSteps.put(step.getNodeKey(), step);
            if (!savedSteps.contains(step)) savedSteps.add(step);
            return step;
        });
        when(steps.save(any(ImageRunStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(textGeneration.generateWithUsage(any(), anyString(), anyString(), anyDouble(), anyInt()))
                .thenAnswer(invocation -> generationResult(invocation.getArgument(1)));
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
    void acceptsNullOptionalImageProviderOptionsAsAdapterDefaults() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("quality", null);
        imageProvider.setOptions(options);

        var summary = service(command -> { }, new SyncTaskExecutor())
                .createRun(new StartImageRunRequest("story-run-1", 7L));

        assertEquals("QUEUED", summary.status());
        verify(runs).saveAndFlush(any(ImageRun.class));
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
        verifyNoInteractions(imageGeneration);
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

    private ImageRunExecutionService service(TaskExecutor runExecutor, TaskExecutor planningExecutor) {
        return new ImageRunExecutionService(stories, runs, steps, agents, styles, flows, aiConfigs,
                textGeneration, imageGeneration, assetStore, mapper, runExecutor, planningExecutor);
    }

    private AiTextGenerationService.GenerationResult generationResult(String systemPrompt) {
        String key = keyFromPrompt(systemPrompt);
        ImageRunStep started = startedSteps.get(key);
        assertNotNull(started, "step must be persisted before provider invocation");
        assertEquals("RUNNING", started.getStatus());
        assertFalse(started.getInputJson().isBlank());
        return new AiTextGenerationService.GenerationResult(generationOutputs.get(key), 11, 7, 18);
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
        return "{\"referenceAssets\":[{\"assetKey\":\"asset-amy\",\"type\":\"CHARACTER\",\"target\":\"amy\",\"prompt\":\"Amy portrait, no text\",\"negativePrompt\":\"text, watermark\"}]}";
    }

    private static String shotPromptPlanJson() {
        return "{\"shots\":[{\"shotKey\":\"shot-1\",\"prompt\":\"Amy walks, no text\",\"negativePrompt\":\"text, words\",\"referenceAssetKeys\":[\"asset-amy\"]}]}";
    }

    private static String preflightJson() {
        return "{\"referenceAssets\":[{\"assetKey\":\"asset-amy\",\"type\":\"CHARACTER\",\"target\":\"amy\",\"prompt\":\"Amy portrait, no text\",\"negativePrompt\":\"text\"}],"
                + "\"shots\":[{\"shotKey\":\"shot-1\",\"sceneIndex\":1,\"shotIndex\":1,\"prompt\":\"Amy walks, no text\",\"negativePrompt\":\"text\",\"referenceAssetKeys\":[\"asset-amy\"],\"speaker\":\"amy\",\"dialogue\":\"Hello!\",\"narration\":\"a short narration\",\"textAnchor\":{\"x\":0.2,\"y\":0.3}}],\"auditSummary\":\"checked\"}";
    }
}
