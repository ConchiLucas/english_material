package com.aitaskcenter.service;

import com.aitaskcenter.config.ImageAgentCatalog;
import com.aitaskcenter.config.ImageAgentCatalog.NodeDefinition;
import com.aitaskcenter.dto.AiConfigRequest;
import com.aitaskcenter.dto.AiProviderConfigItem;
import com.aitaskcenter.dto.ImageRunDtos.RunSummary;
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
import com.aitaskcenter.service.ImageStructuredOutputParser.ContinuityBible;
import com.aitaskcenter.service.ImageStructuredOutputParser.FinalStoryboard;
import com.aitaskcenter.service.ImageStructuredOutputParser.PreflightPlan;
import com.aitaskcenter.service.ImageStructuredOutputParser.ReferencePlan;
import com.aitaskcenter.service.ImageStructuredOutputParser.ShotPromptPlan;
import com.aitaskcenter.service.ImageStructuredOutputParser.StoryAnalysis;
import com.aitaskcenter.service.ImageStructuredOutputParser.StoryboardProposal;
import com.aitaskcenter.service.ImageStructuredOutputParser.StyleBible;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ImageRunExecutionService {
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final int MAX_ERROR_LENGTH = 600;
    private static final List<String> FOUNDATION_KEYS = List.of(
            "image-story-analyst", "image-continuity-designer", "image-art-director");
    private static final List<String> STORYBOARD_KEYS = List.of(
            "image-action-storyboarder", "image-learning-storyboarder");

    private final StoryRunRepository storyRepository;
    private final ImageRunRepository runRepository;
    private final ImageRunStepRepository stepRepository;
    private final ImageAgentConfigRepository agentRepository;
    private final ImageStylePresetRepository styleRepository;
    private final ImageFlowConfigRepository flowRepository;
    private final AiConfigService aiConfigService;
    private final AiTextGenerationService textGenerationService;
    @SuppressWarnings("unused")
    private final AiImageGenerationService imageGenerationService;
    private final ImageAssetStore assetStore;
    private final ObjectMapper objectMapper;
    private final ImageStructuredOutputParser parser;
    private final TaskExecutor runExecutor;
    private final TaskExecutor planningExecutor;

    public ImageRunExecutionService(
            StoryRunRepository storyRepository,
            ImageRunRepository runRepository,
            ImageRunStepRepository stepRepository,
            ImageAgentConfigRepository agentRepository,
            ImageStylePresetRepository styleRepository,
            ImageFlowConfigRepository flowRepository,
            AiConfigService aiConfigService,
            AiTextGenerationService textGenerationService,
            AiImageGenerationService imageGenerationService,
            ImageAssetStore assetStore,
            ObjectMapper objectMapper,
            @Qualifier("imageRunExecutor") TaskExecutor runExecutor,
            @Qualifier("imagePlanningExecutor") TaskExecutor planningExecutor) {
        this.storyRepository = storyRepository;
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.agentRepository = agentRepository;
        this.styleRepository = styleRepository;
        this.flowRepository = flowRepository;
        this.aiConfigService = aiConfigService;
        this.textGenerationService = textGenerationService;
        this.imageGenerationService = imageGenerationService;
        this.assetStore = assetStore;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.parser = new ImageStructuredOutputParser(objectMapper);
        this.runExecutor = runExecutor;
        this.planningExecutor = planningExecutor;
    }

    public RunSummary createRun(StartImageRunRequest request) {
        PreparedRun prepared = prepare(request);
        ImageRun run = prepared.run();
        assetStore.assertWritable();
        runRepository.saveAndFlush(run);
        RunSummary queued = summary(run, prepared.style());
        try {
            runExecutor.execute(() -> executePlanning(run, prepared));
        } catch (RejectedExecutionException exception) {
            failRun(run, "图片运行队列已满，请稍后重试", prepared.state().totalTokens());
            throw new IllegalArgumentException("图片运行队列已满，请稍后重试");
        }
        return queued;
    }

    private PreparedRun prepare(StartImageRunRequest request) {
        if (request == null) throw new IllegalArgumentException("请提交图片运行参数");
        String storyRunId = clean(request.storyRunId());
        if (!StringUtils.hasText(storyRunId)) throw new IllegalArgumentException("请选择故事批次");
        if (request.stylePresetId() == null || request.stylePresetId() <= 0) {
            throw new IllegalArgumentException("请选择画风预设");
        }

        StoryRun story = storyRepository.findByRunId(storyRunId)
                .orElseThrow(() -> new IllegalArgumentException("故事批次不存在"));
        String finalStory = clean(story.getFinalStory());
        if (!StringUtils.hasText(finalStory)) throw new IllegalArgumentException("故事批次没有可用的最终故事");
        if (!StringUtils.hasText(story.getInputWordsJson()) || !StringUtils.hasText(story.getTargetGrade())) {
            throw new IllegalArgumentException("故事批次快照不完整");
        }

        ImageStylePreset style = styleRepository.findById(request.stylePresetId())
                .orElseThrow(() -> new IllegalArgumentException("画风预设不存在"));
        if (!style.isEnabled()) throw new IllegalArgumentException("画风预设未启用");
        StyleSnapshot styleSnapshot = new StyleSnapshot(style.getId(), style.getPresetKey(), style.getName(),
                style.getPositivePrompt(), style.getNegativePrompt(), style.getDescription());

        ImageFlowConfig flow = flowRepository.findByFlowKey(ImageFlowConfig.DEFAULT_FLOW_KEY)
                .orElseThrow(() -> new IllegalArgumentException("图片流程配置不存在"));
        validateFlow(flow);
        AiProviderConfigItem imageProvider = requireImageProvider(flow.getImageProviderId());
        FlowSnapshot flowSnapshot = new FlowSnapshot(
                flow.getWidth(), flow.getHeight(), flow.getMaxShotsPerScene(), flow.getMaxShotsPerStory(),
                safeProviderSnapshot(imageProvider));

        Map<String, ImageAgentConfig> configured = configuredAgents();
        List<AgentExecution> executionAgents = new ArrayList<>();
        List<AgentSnapshot> agentSnapshots = new ArrayList<>();
        int sequence = 1;
        for (NodeDefinition definition : ImageAgentCatalog.agents()) {
            ImageAgentConfig config = configured.get(definition.key());
            validateAgent(definition, config);
            AiProviderConfigItem provider = copyProvider(aiConfigService.getProviderForExecution(config.getAiProviderId()));
            AgentExecution execution = new AgentExecution(
                    sequence++, stageKey(definition.key()), definition.key(), definition.name(),
                    config.getSystemPrompt(), config.getPromptVersion(), config.getTemperature(), provider);
            executionAgents.add(execution);
            agentSnapshots.add(new AgentSnapshot(
                    execution.sequence(), execution.stageKey(), execution.key(), execution.name(),
                    execution.systemPrompt(), execution.promptVersion(), execution.temperature(),
                    safeProviderSnapshot(provider)));
        }

        ImageRun run = new ImageRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setStoryRunId(storyRunId);
        run.setStorySnapshot(finalStory);
        run.setInputWordsJson(story.getInputWordsJson());
        run.setTargetGrade(clean(story.getTargetGrade()));
        run.setStylePresetId(String.valueOf(style.getId()));
        run.setStyleSnapshotJson(writeJson(styleSnapshot));
        run.setFlowSnapshotJson(writeJson(flowSnapshot));
        run.setAgentSnapshotJson(writeJson(agentSnapshots));
        run.setStatus("QUEUED");
        run.setExpectedImageCount(0);
        run.setGeneratedImageCount(0);
        run.setTotalTextTokens(0);

        JsonNode targetWords = readTree(story.getInputWordsJson(), "故事单词快照无效");
        PlanningState state = new PlanningState(Map.copyOf(executionAgents.stream()
                .collect(java.util.stream.Collectors.toMap(AgentExecution::key, Function.identity()))));
        return new PreparedRun(run, style, styleSnapshot, flowSnapshot, targetWords, state,
                copyProvider(imageProvider));
    }

    private void executePlanning(ImageRun run, PreparedRun prepared) {
        PlanningState state = prepared.state();
        run.setStatus("PLANNING");
        run.setStartedAt(now());
        runRepository.saveAndFlush(run);
        try {
            Map<String, AgentCall> foundationCalls = new LinkedHashMap<>();
            foundationCalls.put(FOUNDATION_KEYS.get(0), new AgentCall(
                    input("storySnapshot", run.getStorySnapshot(),
                            "targetGrade", run.getTargetGrade(),
                            "targetWords", prepared.targetWords(),
                            "imageSettings", prepared.flow()),
                    parser::storyAnalysis));
            foundationCalls.put(FOUNDATION_KEYS.get(1), new AgentCall(
                    input("storySnapshot", run.getStorySnapshot(),
                            "targetGrade", run.getTargetGrade(),
                            "targetWords", prepared.targetWords(),
                            "imageSettings", prepared.flow()),
                    parser::continuityBible));
            foundationCalls.put(FOUNDATION_KEYS.get(2), new AgentCall(
                    input("storySnapshot", run.getStorySnapshot(),
                            "targetGrade", run.getTargetGrade(),
                            "stylePreset", prepared.styleSnapshot(),
                            "imageSettings", prepared.flow()),
                    parser::styleBible));
            Map<String, StepResult> foundation = callParallel(run, state, foundationCalls);
            StoryAnalysis analysis = foundation.get(FOUNDATION_KEYS.get(0)).parsed(StoryAnalysis.class);
            ContinuityBible continuity = foundation.get(FOUNDATION_KEYS.get(1)).parsed(ContinuityBible.class);
            StyleBible styleBible = foundation.get(FOUNDATION_KEYS.get(2)).parsed(StyleBible.class);
            validateStep(foundation.get(FOUNDATION_KEYS.get(1)),
                    () -> parser.validateContinuityReferences(analysis, continuity));

            Map<String, AgentCall> storyboardCalls = new LinkedHashMap<>();
            for (String key : STORYBOARD_KEYS) {
                storyboardCalls.put(key, new AgentCall(
                        input("storySnapshot", run.getStorySnapshot(),
                                "storyAnalysis", analysis,
                                "continuityBible", continuity,
                                "styleBible", styleBible,
                                "imageSettings", prepared.flow()),
                        raw -> {
                            StoryboardProposal proposal = parser.storyboardProposal(raw);
                            parser.validateProposalReferences(analysis, proposal);
                            return proposal;
                        }));
            }
            Map<String, StepResult> storyboards = callParallel(run, state, storyboardCalls);
            StoryboardProposal actionProposal = storyboards.get(STORYBOARD_KEYS.get(0)).parsed(StoryboardProposal.class);
            StoryboardProposal learningProposal = storyboards.get(STORYBOARD_KEYS.get(1)).parsed(StoryboardProposal.class);

            StepResult director = call(run, state, "image-storyboard-director",
                    input("storySnapshot", run.getStorySnapshot(),
                            "storyAnalysis", analysis,
                            "continuityBible", continuity,
                            "styleBible", styleBible,
                            "actionStoryboardProposal", actionProposal,
                            "learningStoryboardProposal", learningProposal,
                            "imageSettings", prepared.flow()),
                    raw -> {
                        FinalStoryboard value = parser.finalStoryboard(raw);
                        parser.validateCoverage(analysis, value);
                        return value;
                    });
            FinalStoryboard finalStoryboard = director.parsed(FinalStoryboard.class);

            StepResult reference = call(run, state, "image-reference-planner",
                    input("storyAnalysis", analysis,
                            "continuityBible", continuity,
                            "styleBible", styleBible,
                            "finalStoryboard", finalStoryboard,
                            "imageSettings", prepared.flow()),
                    raw -> {
                        ReferencePlan value = parser.referencePlan(raw);
                        parser.validateReferenceTargets(value, analysis, continuity);
                        return value;
                    });
            ReferencePlan referencePlan = reference.parsed(ReferencePlan.class);

            StepResult shotPrompts = call(run, state, "image-shot-prompt-engineer",
                    input("storySnapshot", run.getStorySnapshot(),
                            "continuityBible", continuity,
                            "styleBible", styleBible,
                            "finalStoryboard", finalStoryboard,
                            "referencePlan", referencePlan,
                            "imageSettings", prepared.flow()),
                    raw -> {
                        ShotPromptPlan value = parser.shotPromptPlan(raw);
                        parser.validateReferences(value, referencePlan);
                        parser.validateShotPrompts(finalStoryboard, value);
                        return value;
                    });
            ShotPromptPlan shotPromptPlan = shotPrompts.parsed(ShotPromptPlan.class);

            StepResult preflight = call(run, state, "image-prompt-preflight",
                    input("storySnapshot", run.getStorySnapshot(),
                            "storyAnalysis", analysis,
                            "continuityBible", continuity,
                            "styleBible", styleBible,
                            "finalStoryboard", finalStoryboard,
                            "referencePlan", referencePlan,
                            "shotPromptPlan", shotPromptPlan,
                            "imageSettings", prepared.flow()),
                    raw -> {
                        PreflightPlan value = parser.preflight(raw);
                        parser.validatePreflight(value, finalStoryboard, analysis, continuity);
                        return value;
                    });
            PreflightPlan finalPlan = preflight.parsed(PreflightPlan.class);
            run.setExpectedImageCount(finalPlan.referenceAssets().size() + finalPlan.shots().size());
            run.setTotalTextTokens(state.totalTokens());
            runRepository.saveAndFlush(run);
        } catch (Exception exception) {
            failRun(run, bounded(exception.getMessage()), state.totalTokens());
        }
    }

    private Map<String, StepResult> callParallel(
            ImageRun run, PlanningState state, Map<String, AgentCall> calls) {
        Map<String, CompletableFuture<StepResult>> futures = new LinkedHashMap<>();
        for (Map.Entry<String, AgentCall> entry : calls.entrySet()) {
            try {
                futures.put(entry.getKey(), CompletableFuture.supplyAsync(
                        () -> call(run, state, entry.getKey(), entry.getValue().input(), entry.getValue().parser()),
                        command -> planningExecutor.execute(command)));
            } catch (RuntimeException exception) {
                futures.put(entry.getKey(), CompletableFuture.failedFuture(exception));
            }
        }
        Map<String, StepResult> results = new LinkedHashMap<>();
        RuntimeException failure = null;
        for (Map.Entry<String, CompletableFuture<StepResult>> entry : futures.entrySet()) {
            try {
                results.put(entry.getKey(), entry.getValue().join());
            } catch (CompletionException exception) {
                RuntimeException current = asRuntime(exception.getCause());
                if (failure == null) failure = current;
            }
        }
        if (failure != null) throw failure;
        return results;
    }

    private StepResult call(
            ImageRun run,
            PlanningState state,
            String key,
            Map<String, Object> input,
            Function<String, Object> outputParser) {
        AgentExecution agent = state.agent(key);
        String inputJson = writeJson(input);
        ImageRunStep step = new ImageRunStep();
        step.setRunId(run.getRunId());
        step.setSequence(agent.sequence());
        step.setStageKey(agent.stageKey());
        step.setNodeKey(agent.key());
        step.setNodeName(agent.name());
        step.setNodeKind("AGENT");
        step.setPromptVersion(agent.promptVersion());
        step.setProviderId(agent.provider().getId());
        step.setProviderModel(agent.provider().getModel());
        step.setInputJson(inputJson);
        step.setStatus("RUNNING");
        step.setStartedAt(now());
        stepRepository.saveAndFlush(step);

        long started = System.nanoTime();
        try {
            int maxTokens = agent.provider().getMaxTokens() == null || agent.provider().getMaxTokens() <= 0
                    ? DEFAULT_MAX_TOKENS : agent.provider().getMaxTokens();
            AiTextGenerationService.GenerationResult generated = textGenerationService.generateWithUsage(
                    agent.provider(), agent.systemPrompt(), inputJson, agent.temperature(), maxTokens);
            if (generated == null) throw new IllegalArgumentException("AI 返回内容为空");
            String raw = generated.text();
            step.setRawOutput(raw);
            long inputTokens = generated.inputTokens() > 0
                    ? generated.inputTokens() : estimateTokens(agent.systemPrompt() + "\n" + inputJson);
            long outputTokens = generated.outputTokens() > 0
                    ? generated.outputTokens() : estimateTokens(raw);
            long totalTokens = generated.totalTokens() > 0
                    ? generated.totalTokens() : inputTokens + outputTokens;
            step.setInputTokens(inputTokens);
            step.setOutputTokens(outputTokens);
            step.setTotalTokens(totalTokens);
            state.addTokens(totalTokens);
            Object parsed = outputParser.apply(raw);
            step.setParsedOutputJson(writeJson(parsed));
            step.setStatus("COMPLETED");
            step.setDurationMs(elapsedMillis(started));
            step.setFinishedAt(now());
            stepRepository.saveAndFlush(step);
            return new StepResult(step, parsed);
        } catch (Exception exception) {
            String message = redact(bounded(exception.getMessage()), agent.provider().getApiKey());
            step.setErrorMessage(message);
            step.setStatus("FAILED");
            step.setDurationMs(elapsedMillis(started));
            step.setFinishedAt(now());
            stepRepository.saveAndFlush(step);
            throw new PlanningFailureException(message, exception);
        }
    }

    private void validateStep(StepResult result, Runnable validation) {
        try {
            validation.run();
        } catch (Exception exception) {
            String message = bounded(exception.getMessage());
            result.step().setStatus("FAILED");
            result.step().setErrorMessage(message);
            result.step().setFinishedAt(now());
            stepRepository.saveAndFlush(result.step());
            throw new PlanningFailureException(message, exception);
        }
    }

    private Map<String, ImageAgentConfig> configuredAgents() {
        Map<String, ImageAgentConfig> values = new HashMap<>();
        for (ImageAgentConfig config : agentRepository.findAllByOrderByAgentKeyAsc()) {
            if (config == null || !StringUtils.hasText(config.getAgentKey())) continue;
            if (values.put(config.getAgentKey().trim(), config) != null) {
                throw new IllegalArgumentException("图片 Agent 配置重复");
            }
        }
        return values;
    }

    private void validateAgent(NodeDefinition definition, ImageAgentConfig config) {
        if (config == null) throw new IllegalArgumentException("图片 Agent「" + definition.key() + "」配置不存在");
        if (!config.isEnabled()) throw new IllegalArgumentException("图片 Agent「" + definition.key() + "」未启用");
        if (!StringUtils.hasText(config.getSystemPrompt())) {
            throw new IllegalArgumentException("图片 Agent「" + definition.key() + "」提示词为空");
        }
        if (!StringUtils.hasText(config.getAiProviderId())) {
            throw new IllegalArgumentException("图片 Agent「" + definition.key() + "」未配置文本 Provider");
        }
        if (!Double.isFinite(config.getTemperature()) || config.getTemperature() < 0 || config.getTemperature() > 2) {
            throw new IllegalArgumentException("图片 Agent「" + definition.key() + "」温度无效");
        }
        if (config.getPromptVersion() < 1) {
            throw new IllegalArgumentException("图片 Agent「" + definition.key() + "」提示词版本无效");
        }
    }

    private void validateFlow(ImageFlowConfig flow) {
        if (flow.getWidth() != ImageAgentCatalog.DEFAULT_WIDTH
                || flow.getHeight() != ImageAgentCatalog.DEFAULT_HEIGHT
                || flow.getMaxShotsPerScene() != ImageAgentCatalog.DEFAULT_MAX_SHOTS_PER_SCENE
                || flow.getMaxShotsPerStory() != ImageAgentCatalog.DEFAULT_MAX_SHOTS_PER_STORY) {
            throw new IllegalArgumentException("图片流程规格无效");
        }
        if (!StringUtils.hasText(flow.getImageProviderId())) {
            throw new IllegalArgumentException("图片流程未配置图片 Provider");
        }
    }

    private AiProviderConfigItem requireImageProvider(String id) {
        AiConfigRequest config = aiConfigService.getConfig();
        List<AiProviderConfigItem> providers = config == null || config.getProviders() == null
                ? List.of() : config.getProviders();
        AiProviderConfigItem provider = providers.stream()
                .filter(Objects::nonNull)
                .filter(value -> clean(id).equals(clean(value.getId())))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("图片 Provider 不存在"));
        if (!"openai-compatible".equals(clean(provider.getType()).toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("图片 Provider 必须使用 OpenAI compatible 协议");
        }
        if (!provider.isEnabled() || !supports(provider, "IMAGE_GENERATION") || !supports(provider, "IMAGE_REFERENCE")) {
            throw new IllegalArgumentException("图片 Provider 必须启用并支持图片生成和多参考图");
        }
        if (!StringUtils.hasText(provider.getModel()) || !StringUtils.hasText(provider.getBaseUrl())) {
            throw new IllegalArgumentException("图片 Provider 配置不完整");
        }
        validateImageProviderUrl(provider.getBaseUrl());
        return copyProvider(provider);
    }

    private void validateImageProviderUrl(String baseUrl) {
        try {
            URI uri = URI.create(clean(baseUrl));
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            if (!uri.isAbsolute() || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || path.endsWith("/images/generations") || path.endsWith("/images/edits")) {
                throw new IllegalArgumentException();
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("图片 Provider 地址无效");
        }
    }

    private void failRun(ImageRun run, String error, long totalTokens) {
        run.setStatus("FAILED");
        run.setErrorMessage(bounded(error));
        run.setTotalTextTokens(totalTokens);
        run.setFinishedAt(now());
        runRepository.saveAndFlush(run);
    }

    private RunSummary summary(ImageRun run, ImageStylePreset style) {
        return new RunSummary(run.getRunId(), run.getStoryRunId(), style.getId(), style.getName(),
                run.getTargetGrade(), run.getStatus(), run.getExpectedImageCount(), run.getGeneratedImageCount(),
                run.getTotalTextTokens(), run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt());
    }

    private String stageKey(String agentKey) {
        return ImageAgentCatalog.stages().stream()
                .filter(stage -> stage.nodes().stream().anyMatch(node -> node.key().equals(agentKey)))
                .map(ImageAgentCatalog.StageDefinition::key)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("图片 Agent 阶段不存在"));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("图片运行快照序列化失败");
        }
    }

    private JsonNode readTree(String value, String message) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node == null || !node.isArray()) throw new IllegalArgumentException(message);
            return node;
        } catch (Exception exception) {
            throw new IllegalArgumentException(message);
        }
    }

    private static Map<String, Object> input(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return result;
    }

    private static boolean supports(AiProviderConfigItem provider, String capability) {
        return provider.getCapabilities() != null && provider.getCapabilities().stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .anyMatch(capability::equals);
    }

    private static AiProviderConfigItem copyProvider(AiProviderConfigItem source) {
        AiProviderConfigItem copy = new AiProviderConfigItem();
        copy.setId(clean(source.getId()));
        copy.setLabel(clean(source.getLabel()));
        copy.setType(clean(source.getType()));
        copy.setBaseUrl(clean(source.getBaseUrl()));
        copy.setApiKey(source.getApiKey());
        copy.setModel(clean(source.getModel()));
        copy.setMaxTokens(source.getMaxTokens());
        copy.setVoice(null);
        copy.setCapabilities(source.getCapabilities() == null ? List.of() : List.copyOf(source.getCapabilities()));
        copy.setOptions(source.getOptions() == null ? Map.of() : new LinkedHashMap<>(source.getOptions()));
        copy.setEnabled(source.isEnabled());
        copy.setActive(source.isActive());
        return copy;
    }

    private static ProviderSnapshot safeProviderSnapshot(AiProviderConfigItem provider) {
        Map<String, Object> safeOptions = new LinkedHashMap<>();
        if (provider.getOptions() != null) {
            for (String key : List.of("responseFormat", "quality", "size")) {
                Object value = provider.getOptions().get(key);
                if (value != null) safeOptions.put(key, value);
            }
        }
        return new ProviderSnapshot(provider.getId(), provider.getLabel(), provider.getType(),
                provider.getModel(), provider.getMaxTokens(), provider.getCapabilities(), Map.copyOf(safeOptions));
    }

    private static RuntimeException asRuntime(Throwable throwable) {
        if (throwable instanceof RuntimeException runtime) return runtime;
        return new IllegalArgumentException("图片规划执行失败", throwable);
    }

    private static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (text.length() + 3L) / 4L);
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000L);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String bounded(String value) {
        String message = clean(value).replaceAll("[\\r\\n\\t]+", " ").replaceAll(" +", " ");
        if (!StringUtils.hasText(message)) message = "图片规划执行失败";
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH) + "…";
    }

    private static String redact(String value, String secret) {
        return StringUtils.hasText(secret) ? value.replace(secret, "[REDACTED]") : value;
    }

    private record PreparedRun(
            ImageRun run,
            ImageStylePreset style,
            StyleSnapshot styleSnapshot,
            FlowSnapshot flow,
            JsonNode targetWords,
            PlanningState state,
            AiProviderConfigItem imageProvider) {
    }

    private record PlanningState(Map<String, AgentExecution> agents, AtomicLong tokens) {
        private PlanningState(Map<String, AgentExecution> agents) {
            this(agents, new AtomicLong());
        }

        private AgentExecution agent(String key) {
            AgentExecution value = agents.get(key);
            if (value == null) throw new IllegalArgumentException("图片 Agent「" + key + "」运行快照不存在");
            return value;
        }

        private void addTokens(long value) {
            tokens.addAndGet(value);
        }

        private long totalTokens() {
            return tokens.get();
        }
    }

    private record AgentExecution(
            int sequence,
            String stageKey,
            String key,
            String name,
            String systemPrompt,
            int promptVersion,
            double temperature,
            AiProviderConfigItem provider) {
    }

    private record AgentCall(Map<String, Object> input, Function<String, Object> parser) {
    }

    private record StepResult(ImageRunStep step, Object value) {
        private <T> T parsed(Class<T> type) {
            return type.cast(value);
        }
    }

    private record StyleSnapshot(
            Long id,
            String key,
            String name,
            String positivePrompt,
            String negativePrompt,
            String description) {
    }

    private record FlowSnapshot(
            int width,
            int height,
            int maxShotsPerScene,
            int maxShotsPerStory,
            ProviderSnapshot imageProvider) {
    }

    private record AgentSnapshot(
            int sequence,
            String stageKey,
            String key,
            String name,
            String systemPrompt,
            int promptVersion,
            double temperature,
            ProviderSnapshot provider) {
    }

    private record ProviderSnapshot(
            String id,
            String label,
            String type,
            String model,
            Integer maxTokens,
            List<String> capabilities,
            Map<String, Object> options) {
        private ProviderSnapshot {
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            options = options == null ? Map.of() : Map.copyOf(options);
        }
    }

    private static final class PlanningFailureException extends RuntimeException {
        private PlanningFailureException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
