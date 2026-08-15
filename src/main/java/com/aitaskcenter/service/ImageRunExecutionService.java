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
    private static final int MAX_AGENT_RAW_CHARS = 512_000;
    private static final String OVERSIZED_RAW_PLACEHOLDER = "图片规划原始输出超过最大长度，正文未保存";
    private static final int MAX_IMAGE_OPTION_LENGTH = 64;
    private static final Set<String> IMAGE_OPTION_KEYS = Set.of("responseFormat", "quality", "size");
    private static final Set<String> IMAGE_QUALITIES = Set.of("auto", "low", "medium", "high", "standard", "hd");
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
        ImageRun run = newRun(prepared.run());
        assetStore.assertWritable();
        ImageRun persisted = runRepository.saveAndFlush(run);
        String runId = persisted.getRunId();
        RunSummary queued = summary(persisted, prepared.styleId(), prepared.styleName());
        try {
            runExecutor.execute(() -> executePlanning(runId, prepared));
        } catch (RejectedExecutionException exception) {
            failRun(runId, "图片运行队列已满，请稍后重试", 0);
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
        AiProviderConfigItem imageProviderConfig = requireImageProvider(flow.getImageProviderId());
        ProviderExecution imageProvider = providerExecution(imageProviderConfig,
                normalizeImageOptions(imageProviderConfig.getOptions(), flow.getWidth(), flow.getHeight()));
        FlowSnapshot flowSnapshot = new FlowSnapshot(
                flow.getWidth(), flow.getHeight(), flow.getMaxShotsPerScene(), flow.getMaxShotsPerStory(),
                imageProviderSnapshot(imageProvider));

        Map<String, ImageAgentConfig> configured = configuredAgents();
        List<AgentExecution> executionAgents = new ArrayList<>();
        List<AgentSnapshot> agentSnapshots = new ArrayList<>();
        int sequence = 1;
        for (NodeDefinition definition : ImageAgentCatalog.agents()) {
            ImageAgentConfig config = configured.get(definition.key());
            validateAgent(definition, config);
            ProviderExecution provider = providerExecution(
                    aiConfigService.getProviderForExecution(config.getAiProviderId()), Map.of());
            AgentExecution execution = new AgentExecution(
                    sequence++, stageKey(definition.key()), definition.key(), definition.name(),
                    config.getSystemPrompt(), config.getPromptVersion(), config.getTemperature(), provider);
            executionAgents.add(execution);
            agentSnapshots.add(new AgentSnapshot(
                    execution.sequence(), execution.stageKey(), execution.key(), execution.name(),
                    execution.systemPrompt(), execution.promptVersion(), execution.temperature(),
                    textProviderSnapshot(provider)));
        }

        readTree(story.getInputWordsJson(), "故事单词快照无效");
        RunSnapshot run = new RunSnapshot(UUID.randomUUID().toString(), storyRunId, finalStory,
                story.getInputWordsJson(), clean(story.getTargetGrade()), String.valueOf(style.getId()),
                writeJson(styleSnapshot), writeJson(flowSnapshot), writeJson(agentSnapshots));
        Map<String, AgentExecution> executionSnapshot = executionAgents.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(AgentExecution::key, Function.identity()));
        return new PreparedRun(run, style.getId(), style.getName(), styleSnapshot, flowSnapshot,
                story.getInputWordsJson(), executionSnapshot,
                imageProvider);
    }

    private void executePlanning(String runId, PreparedRun prepared) {
        PlanningState state = new PlanningState(prepared.agents());
        try {
            ImageRun run = runRepository.findByRunId(runId)
                    .orElseThrow(() -> new IllegalArgumentException("图片运行批次不存在"));
            run.setStatus("PLANNING");
            run.setStartedAt(now());
            run = runRepository.saveAndFlush(run);
            JsonNode targetWords = readTree(prepared.targetWordsJson(), "故事单词快照无效");
            Map<String, AgentCall> foundationCalls = new LinkedHashMap<>();
            foundationCalls.put(FOUNDATION_KEYS.get(0), new AgentCall(
                    input("storySnapshot", prepared.run().storySnapshot(),
                            "targetGrade", prepared.run().targetGrade(),
                            "targetWords", targetWords,
                            "imageSettings", prepared.flow()),
                    parser::storyAnalysis));
            foundationCalls.put(FOUNDATION_KEYS.get(1), new AgentCall(
                    input("storySnapshot", prepared.run().storySnapshot(),
                            "targetGrade", prepared.run().targetGrade(),
                            "targetWords", targetWords,
                            "imageSettings", prepared.flow()),
                    parser::continuityBible));
            foundationCalls.put(FOUNDATION_KEYS.get(2), new AgentCall(
                    input("storySnapshot", prepared.run().storySnapshot(),
                            "targetGrade", prepared.run().targetGrade(),
                            "stylePreset", prepared.styleSnapshot(),
                            "imageSettings", prepared.flow()),
                    parser::styleBible));
            Map<String, StepResult> foundation = callParallel(runId, state, foundationCalls);
            StoryAnalysis analysis = foundation.get(FOUNDATION_KEYS.get(0)).parsed(StoryAnalysis.class);
            ContinuityBible continuity = foundation.get(FOUNDATION_KEYS.get(1)).parsed(ContinuityBible.class);
            StyleBible styleBible = foundation.get(FOUNDATION_KEYS.get(2)).parsed(StyleBible.class);
            validateStep(foundation.get(FOUNDATION_KEYS.get(1)),
                    () -> parser.validateContinuityReferences(analysis, continuity));

            Map<String, AgentCall> storyboardCalls = new LinkedHashMap<>();
            for (String key : STORYBOARD_KEYS) {
                storyboardCalls.put(key, new AgentCall(
                        input("storySnapshot", prepared.run().storySnapshot(),
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
            Map<String, StepResult> storyboards = callParallel(runId, state, storyboardCalls);
            StoryboardProposal actionProposal = storyboards.get(STORYBOARD_KEYS.get(0)).parsed(StoryboardProposal.class);
            StoryboardProposal learningProposal = storyboards.get(STORYBOARD_KEYS.get(1)).parsed(StoryboardProposal.class);

            StepResult director = call(runId, state, "image-storyboard-director",
                    input("storySnapshot", prepared.run().storySnapshot(),
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

            StepResult reference = call(runId, state, "image-reference-planner",
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

            StepResult shotPrompts = call(runId, state, "image-shot-prompt-engineer",
                    input("storySnapshot", prepared.run().storySnapshot(),
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

            StepResult preflight = call(runId, state, "image-prompt-preflight",
                    input("storySnapshot", prepared.run().storySnapshot(),
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
            failRun(runId, bounded(exception.getMessage()), state.totalTokens());
        }
    }

    private Map<String, StepResult> callParallel(
            String runId, PlanningState state, Map<String, AgentCall> calls) {
        Map<String, CompletableFuture<StepResult>> futures = new LinkedHashMap<>();
        for (Map.Entry<String, AgentCall> entry : calls.entrySet()) {
            try {
                futures.put(entry.getKey(), CompletableFuture.supplyAsync(
                        () -> call(runId, state, entry.getKey(), entry.getValue().input(), entry.getValue().parser()),
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
            String runId,
            PlanningState state,
            String key,
            Map<String, Object> input,
            Function<String, Object> outputParser) {
        AgentExecution agent = state.agent(key);
        String inputJson = writeJson(input);
        ImageRunStep step = new ImageRunStep();
        step.setRunId(runId);
        step.setSequence(agent.sequence());
        step.setStageKey(agent.stageKey());
        step.setNodeKey(agent.key());
        step.setNodeName(agent.name());
        step.setNodeKind("AGENT");
        step.setPromptVersion(agent.promptVersion());
        step.setProviderId(agent.provider().id());
        step.setProviderModel(agent.provider().model());
        step.setInputJson(inputJson);
        step.setStatus("RUNNING");
        step.setStartedAt(now());
        stepRepository.saveAndFlush(step);

        long started = System.nanoTime();
        try {
            int maxTokens = agent.provider().maxTokens() == null || agent.provider().maxTokens() <= 0
                    ? DEFAULT_MAX_TOKENS : agent.provider().maxTokens();
            AiTextGenerationService.GenerationResult generated = textGenerationService.generateWithUsage(
                    agent.provider().toConfig(), agent.systemPrompt(), inputJson, agent.temperature(), maxTokens);
            if (generated == null) throw new IllegalArgumentException("AI 返回内容为空");
            String raw = generated.text();
            long inputTokens = generated.inputTokens() > 0
                    ? generated.inputTokens() : estimateTokens(agent.systemPrompt() + "\n" + inputJson);
            long outputTokens = generated.outputTokens() > 0
                    ? generated.outputTokens() : estimateTokens(raw);
            long minimumTotal = saturatedAdd(inputTokens, outputTokens);
            long totalTokens = generated.totalTokens() >= minimumTotal
                    ? generated.totalTokens() : minimumTotal;
            step.setInputTokens(inputTokens);
            step.setOutputTokens(outputTokens);
            step.setTotalTokens(totalTokens);
            state.addTokens(totalTokens);
            if (raw != null && raw.length() > MAX_AGENT_RAW_CHARS) {
                step.setRawOutput(OVERSIZED_RAW_PLACEHOLDER);
                throw new IllegalArgumentException("图片规划原始输出超过最大长度");
            }
            step.setRawOutput(raw);
            Object parsed = outputParser.apply(raw);
            step.setParsedOutputJson(writeJson(parsed));
            step.setStatus("COMPLETED");
            step.setDurationMs(elapsedMillis(started));
            step.setFinishedAt(now());
            stepRepository.saveAndFlush(step);
            return new StepResult(step, parsed);
        } catch (Exception exception) {
            String message = redact(bounded(exception.getMessage()), agent.provider().apiKey());
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
        return provider;
    }

    private Map<String, Object> normalizeImageOptions(Map<String, Object> options, int width, int height) {
        if (options == null || options.isEmpty()) return Map.of();
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            String key = entry.getKey();
            if (!IMAGE_OPTION_KEYS.contains(key) || !(entry.getValue() instanceof String text)) {
                throw new IllegalArgumentException("图片 Provider options 只能包含受支持的字符串参数");
            }
            String value = text.trim();
            if (value.isEmpty() || value.length() > MAX_IMAGE_OPTION_LENGTH) {
                throw new IllegalArgumentException("图片 Provider option 值无效");
            }
            if ("responseFormat".equals(key)) {
                value = value.toLowerCase(Locale.ROOT);
                if (!"b64_json".equals(value)) {
                    throw new IllegalArgumentException("图片 Provider responseFormat 必须为 b64_json");
                }
            } else if ("quality".equals(key)) {
                value = value.toLowerCase(Locale.ROOT);
                if (!IMAGE_QUALITIES.contains(value)) {
                    throw new IllegalArgumentException("图片 Provider quality 无效");
                }
            } else {
                if (!value.matches("[1-9][0-9]{0,4}x[1-9][0-9]{0,4}")
                        || !(width + "x" + height).equals(value)) {
                    throw new IllegalArgumentException("图片 Provider size 必须与图片流程尺寸一致");
                }
            }
            normalized.put(key, value);
        }
        return Map.copyOf(normalized);
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

    private ImageRun newRun(RunSnapshot snapshot) {
        ImageRun run = new ImageRun();
        run.setRunId(snapshot.runId());
        run.setStoryRunId(snapshot.storyRunId());
        run.setStorySnapshot(snapshot.storySnapshot());
        run.setInputWordsJson(snapshot.inputWordsJson());
        run.setTargetGrade(snapshot.targetGrade());
        run.setStylePresetId(snapshot.stylePresetId());
        run.setStyleSnapshotJson(snapshot.styleSnapshotJson());
        run.setFlowSnapshotJson(snapshot.flowSnapshotJson());
        run.setAgentSnapshotJson(snapshot.agentSnapshotJson());
        run.setStatus("QUEUED");
        return run;
    }

    private void failRun(String runId, String error, long totalTokens) {
        ImageRun run = runRepository.findByRunId(runId)
                .orElseThrow(() -> new IllegalArgumentException("图片运行批次不存在"));
        run.setStatus("FAILED");
        run.setErrorMessage(bounded(error));
        run.setTotalTextTokens(totalTokens);
        run.setFinishedAt(now());
        runRepository.saveAndFlush(run);
    }

    private RunSummary summary(ImageRun run, Long styleId, String styleName) {
        return new RunSummary(run.getRunId(), run.getStoryRunId(), styleId, styleName,
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

    private static ProviderExecution providerExecution(AiProviderConfigItem source, Map<String, Object> options) {
        return new ProviderExecution(clean(source.getId()), clean(source.getLabel()), clean(source.getType()),
                clean(source.getBaseUrl()), source.getApiKey(), clean(source.getModel()), source.getMaxTokens(),
                source.getCapabilities(), options);
    }

    private static ProviderSnapshot textProviderSnapshot(ProviderExecution provider) {
        return providerSnapshot(provider, Map.of());
    }

    private static ProviderSnapshot imageProviderSnapshot(ProviderExecution provider) {
        return providerSnapshot(provider, provider.options());
    }

    private static ProviderSnapshot providerSnapshot(ProviderExecution provider, Map<String, Object> options) {
        return new ProviderSnapshot(provider.id(), provider.label(), provider.type(),
                provider.model(), provider.maxTokens(), provider.capabilities(), options);
    }

    private static RuntimeException asRuntime(Throwable throwable) {
        if (throwable instanceof RuntimeException runtime) return runtime;
        return new IllegalArgumentException("图片规划执行失败", throwable);
    }

    private static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (text.length() + 3L) / 4L);
    }

    private static long saturatedAdd(long left, long right) {
        if (left < 0 || right < 0) throw new IllegalArgumentException("Token 用量不能为负数");
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
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
            RunSnapshot run,
            Long styleId,
            String styleName,
            StyleSnapshot styleSnapshot,
            FlowSnapshot flow,
            String targetWordsJson,
            Map<String, AgentExecution> agents,
            ProviderExecution imageProvider) {
        private PreparedRun {
            agents = Map.copyOf(agents);
        }
    }

    private record RunSnapshot(
            String runId,
            String storyRunId,
            String storySnapshot,
            String inputWordsJson,
            String targetGrade,
            String stylePresetId,
            String styleSnapshotJson,
            String flowSnapshotJson,
            String agentSnapshotJson) {
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
            tokens.updateAndGet(current -> saturatedAdd(current, value));
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
            ProviderExecution provider) {
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

    private record ProviderExecution(
            String id,
            String label,
            String type,
            String baseUrl,
            String apiKey,
            String model,
            Integer maxTokens,
            List<String> capabilities,
            Map<String, Object> options) {
        private ProviderExecution {
            capabilities = capabilities == null ? List.of() : capabilities.stream()
                    .filter(Objects::nonNull).map(String::trim).toList();
            options = options == null ? Map.of() : Map.copyOf(options);
        }

        private AiProviderConfigItem toConfig() {
            AiProviderConfigItem provider = new AiProviderConfigItem();
            provider.setId(id);
            provider.setLabel(label);
            provider.setType(type);
            provider.setBaseUrl(baseUrl);
            provider.setApiKey(apiKey);
            provider.setModel(model);
            provider.setMaxTokens(maxTokens);
            provider.setCapabilities(capabilities);
            provider.setOptions(options);
            provider.setEnabled(true);
            return provider;
        }
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
