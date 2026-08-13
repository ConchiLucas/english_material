package com.aitaskcenter.service;

import com.aitaskcenter.dto.AiProviderConfigItem;
import com.aitaskcenter.dto.StoryAgentDtos.AgentView;
import com.aitaskcenter.dto.StoryAgentDtos.BudgetView;
import com.aitaskcenter.dto.StoryRunDtos.RunSummary;
import com.aitaskcenter.dto.StoryRunDtos.StartRunRequest;
import com.aitaskcenter.dto.StoryRunDtos.StoryWord;
import com.aitaskcenter.model.StoryRun;
import com.aitaskcenter.model.StoryRunStep;
import com.aitaskcenter.repository.StoryRunRepository;
import com.aitaskcenter.repository.StoryRunStepRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class StoryRunExecutionService {
    private static final Pattern STORY_BLOCK = Pattern.compile(
            "STORY_TEXT_BEGIN\\s*(.*?)\\s*STORY_TEXT_END", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final List<String> PITCH_KEYS = List.of("pitch-humor", "pitch-adventure", "pitch-wonder");
    private static final List<String> REVIEW_KEYS = List.of("review-fun", "review-language", "review-continuity");

    private final StoryRunRepository runRepository;
    private final StoryRunStepRepository stepRepository;
    private final StoryAgentService agentService;
    private final AiConfigService aiConfigService;
    private final AiTextGenerationService generationService;
    private final StoryWordSourceService wordSourceService;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;

    public StoryRunExecutionService(
            StoryRunRepository runRepository,
            StoryRunStepRepository stepRepository,
            StoryAgentService agentService,
            AiConfigService aiConfigService,
            AiTextGenerationService generationService,
            StoryWordSourceService wordSourceService,
            ObjectMapper objectMapper,
            @Qualifier("storyRunExecutor") TaskExecutor executor) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.agentService = agentService;
        this.aiConfigService = aiConfigService;
        this.generationService = generationService;
        this.wordSourceService = wordSourceService;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public RunSummary createRun(StartRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请提交运行参数");
        }
        List<StoryWord> words = wordSourceService.normalizeManualWords(request.words());
        String targetGrade = clean(request.targetGrade());
        if (!StringUtils.hasText(targetGrade)) {
            throw new IllegalArgumentException("请填写目标年级");
        }
        StoryRun run = new StoryRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setInputWordsJson(writeJson(words));
        run.setTargetGrade(targetGrade);
        run.setStatus("QUEUED");
        runRepository.save(run);
        executor.execute(() -> execute(run.getRunId()));
        return toSummary(run, words);
    }

    void execute(String runId) {
        StoryRun run = runRepository.findByRunId(runId)
                .orElseThrow(() -> new IllegalArgumentException("运行批次不存在"));
        run.setStatus("RUNNING");
        run.setStartedAt(now());
        runRepository.save(run);
        try {
            ExecutionState state = new ExecutionState(run, loadAgents(), agentService.getFlow().budget());
            List<StoryWord> words = readWords(run.getInputWordsJson());
            String targetWords = writeJson(words);

            String plan = call(state, "vocabulary-planner", 0, mapOf(
                    "targetWords", words,
                    "targetGrade", run.getTargetGrade(),
                    "sourceContext", "运行批次内保存的单词快照"));
            if (state.limitReached()) return;

            Map<String, String> pitches = new LinkedHashMap<>();
            for (String key : PITCH_KEYS) {
                pitches.put(key, call(state, key, 0, mapOf(
                        "vocabularyPlan", plan,
                        "targetGrade", run.getTargetGrade(),
                        "priorQualityFeedback", "")));
                if (state.limitReached()) return;
            }
            String blueprint = call(state, "story-director", 0, mapOf(
                    "vocabularyPlan", plan,
                    "anonymousPitches", pitches.values(),
                    "targetGrade", run.getTargetGrade(),
                    "directorFeedback", ""));
            if (state.limitReached()) return;

            String candidate = call(state, "story-writer", 0, mapOf(
                    "vocabularyPlan", plan,
                    "storyBlueprint", blueprint,
                    "targetGrade", run.getTargetGrade(),
                    "writerFeedback", ""));
            run.setFinalStory(extractStory(candidate));
            runRepository.save(run);
            if (state.limitReached()) return;

            int qualityRound = 1;
            int revisions = 0;
            int writerRewrites = 0;
            int directorReturns = 0;
            int pitchReturns = 0;
            int planReturns = 0;
            while (true) {
                Map<String, String> reviews = new LinkedHashMap<>();
                for (String key : REVIEW_KEYS) {
                    Map<String, Object> input = new LinkedHashMap<>();
                    input.put("candidateStory", candidate);
                    input.put("targetWords", targetWords);
                    input.put("wordUsageMap", "由故事正文中的使用位置清单提供");
                    input.put("targetGrade", run.getTargetGrade());
                    input.put("storyBlueprint", blueprint);
                    input.put("qualityRound", qualityRound);
                    reviews.put(key, call(state, key, qualityRound, input));
                    if (state.limitReached()) return;
                }
                String score = call(state, "story-scorer", qualityRound, mapOf(
                        "candidateStory", candidate,
                        "funReview", reviews.get("review-fun"),
                        "languageReview", reviews.get("review-language"),
                        "continuityReview", reviews.get("review-continuity"),
                        "scoringRubric", "趣味性、用词自然度、连续性和年级适配"));
                if (state.limitReached()) return;
                String decision = call(state, "quality-decider", qualityRound, mapOf(
                        "reviewReports", reviews,
                        "scoreReport", score,
                        "budgetState", state.budget,
                        "revisionHistory", state.revisionHistory));
                if (state.limitReached()) return;
                state.revisionHistory.add(decision);
                if (isPass(decision)) {
                    complete(run, "COMPLETED", candidate, state.totalTokens);
                    return;
                }
                if (qualityRound >= state.budget.maxQualityRounds()) {
                    complete(run, "LIMIT_REACHED", candidate, state.totalTokens);
                    return;
                }
                qualityRound++;
                String action = decisionAction(decision);
                if ("REPLAN".equals(action) && planReturns < state.budget.maxPlanReturns()) {
                    planReturns++;
                    plan = call(state, "vocabulary-planner", qualityRound, mapOf(
                            "targetWords", words,
                            "targetGrade", run.getTargetGrade(),
                            "sourceContext", decision));
                    pitches.clear();
                    for (String key : PITCH_KEYS) {
                        pitches.put(key, call(state, key, qualityRound, mapOf(
                                "vocabularyPlan", plan,
                                "targetGrade", run.getTargetGrade(),
                                "priorQualityFeedback", decision)));
                    }
                    blueprint = call(state, "story-director", qualityRound, mapOf(
                            "vocabularyPlan", plan,
                            "anonymousPitches", pitches.values(),
                            "targetGrade", run.getTargetGrade(),
                            "directorFeedback", decision));
                    candidate = call(state, "story-writer", qualityRound, mapOf(
                            "vocabularyPlan", plan,
                            "storyBlueprint", blueprint,
                            "targetGrade", run.getTargetGrade(),
                            "writerFeedback", decision));
                } else if ("REPITCH".equals(action) && pitchReturns < state.budget.maxPitchReturns()) {
                    pitchReturns++;
                    pitches.clear();
                    for (String key : PITCH_KEYS) {
                        pitches.put(key, call(state, key, qualityRound, mapOf(
                                "vocabularyPlan", plan,
                                "targetGrade", run.getTargetGrade(),
                                "priorQualityFeedback", decision)));
                    }
                    blueprint = call(state, "story-director", qualityRound, mapOf(
                            "vocabularyPlan", plan,
                            "anonymousPitches", pitches.values(),
                            "targetGrade", run.getTargetGrade(),
                            "directorFeedback", decision));
                    candidate = call(state, "story-writer", qualityRound, mapOf(
                            "vocabularyPlan", plan,
                            "storyBlueprint", blueprint,
                            "targetGrade", run.getTargetGrade(),
                            "writerFeedback", decision));
                } else if ("REDIRECT".equals(action) && directorReturns < state.budget.maxDirectorReturns()) {
                    directorReturns++;
                    blueprint = call(state, "story-director", qualityRound, mapOf(
                            "vocabularyPlan", plan,
                            "anonymousPitches", pitches.values(),
                            "targetGrade", run.getTargetGrade(),
                            "directorFeedback", decision));
                    candidate = call(state, "story-writer", qualityRound, mapOf(
                            "vocabularyPlan", plan,
                            "storyBlueprint", blueprint,
                            "targetGrade", run.getTargetGrade(),
                            "writerFeedback", decision));
                } else if ("REWRITE".equals(action) && writerRewrites < state.budget.maxWriterRewrites()) {
                    writerRewrites++;
                    candidate = call(state, "story-writer", qualityRound, mapOf(
                            "vocabularyPlan", plan,
                            "storyBlueprint", blueprint,
                            "targetGrade", run.getTargetGrade(),
                            "writerFeedback", decision));
                } else if ("REVISE".equals(action) && revisions < state.budget.maxLocalRevisions()) {
                    revisions++;
                    candidate = call(state, "targeted-reviser", qualityRound, mapOf(
                            "candidateStory", candidate,
                            "issueList", decision,
                            "protectedPasses", reviews,
                            "vocabularyPlan", plan,
                            "storyBlueprint", blueprint));
                } else {
                    complete(run, "LIMIT_REACHED", candidate, state.totalTokens);
                    return;
                }
                run.setFinalStory(extractStory(candidate));
                runRepository.save(run);
                if (state.limitReached()) return;
            }
        } catch (Exception ex) {
            run.setStatus("FAILED");
            run.setErrorMessage(bounded(ex.getMessage()));
            run.setFinishedAt(now());
            runRepository.save(run);
        }
    }

    private String call(
            ExecutionState state,
            String agentKey,
            int qualityRound,
            Map<String, Object> input) {
        AgentView agent = state.agents.get(agentKey);
        if (agent == null || !Boolean.TRUE.equals(agent.enabled())) {
            throw new IllegalArgumentException("故事 Agent「" + agentKey + "」未启用或不存在");
        }
        AiProviderConfigItem provider = aiConfigService.getProviderForExecution(agent.aiProviderId());
        String inputJson = writeJson(input);
        long started = System.nanoTime();
        StoryRunStep step = new StoryRunStep();
        step.setRunId(state.run.getRunId());
        step.setSequence(++state.sequence);
        step.setQualityRound(qualityRound);
        step.setAgentKey(agent.key());
        step.setAgentName(agent.name());
        step.setPromptVersion(agent.promptVersion() == null ? 0 : agent.promptVersion());
        step.setProviderId(agent.aiProviderId());
        step.setProviderModel(provider.getModel());
        step.setInputJson(inputJson);
        try {
            AiTextGenerationService.GenerationResult result = generationService.generateWithUsage(
                    provider,
                    agent.systemPrompt(),
                    inputJson,
                    agent.temperature() == null ? 0.2 : agent.temperature(),
                    provider.getMaxTokens() == null ? 4096 : provider.getMaxTokens());
            step.setOutputText(result.text());
            step.setStatus("COMPLETED");
            step.setInputTokens(result.inputTokens());
            step.setOutputTokens(result.outputTokens());
            step.setTotalTokens(result.totalTokens());
            step.setDurationMs(elapsedMillis(started));
            stepRepository.save(step);
            state.addTokens(result.totalTokens());
            return result.text();
        } catch (Exception ex) {
            step.setOutputText(bounded(ex.getMessage()));
            step.setStatus("FAILED");
            step.setDurationMs(elapsedMillis(started));
            stepRepository.save(step);
            throw ex;
        }
    }

    private Map<String, AgentView> loadAgents() {
        return agentService.getFlow().stages().stream()
                .flatMap(stage -> stage.nodes().stream())
                .filter(AgentView::editable)
                .collect(Collectors.toMap(AgentView::key, agent -> agent));
    }

    private void complete(StoryRun run, String status, String candidate, long totalTokens) {
        run.setStatus(status);
        run.setFinalStory(extractStory(candidate));
        run.setTotalTokens(totalTokens);
        run.setFinishedAt(now());
        runRepository.save(run);
    }

    private static boolean isPass(String decision) {
        String normalized = decision == null ? "" : decision.toUpperCase(Locale.ROOT);
        return normalized.matches("(?s).*FINAL_DECISION\\s*:\\s*PASS.*")
                || normalized.matches("(?s).*ACTION\\s*:\\s*PASS.*")
                || normalized.trim().equals("PASS");
    }

    private static String decisionAction(String decision) {
        String normalized = decision == null ? "" : decision.toUpperCase(Locale.ROOT);
        for (String action : List.of("REPLAN", "REPITCH", "REDIRECT", "REWRITE", "REVISE", "PASS")) {
            if (normalized.matches("(?s).*(?:ACTION|FINAL_DECISION)\\s*:\\s*" + action + ".*")) {
                return action;
            }
        }
        return "REVISE";
    }

    static String extractStory(String output) {
        String value = output == null ? "" : output.trim();
        Matcher matcher = STORY_BLOCK.matcher(value);
        return matcher.find() ? matcher.group(1).trim() : value;
    }

    private RunSummary toSummary(StoryRun run, List<StoryWord> words) {
        return new RunSummary(
                run.getRunId(), words, run.getTargetGrade(), run.getStatus(), run.getTotalTokens(),
                run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt());
    }

    private List<StoryWord> readWords(String json) {
        try {
            return objectMapper.readerForListOf(StoryWord.class).readValue(json);
        } catch (Exception ex) {
            throw new IllegalArgumentException("运行批次单词快照无法读取", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("运行内容无法序列化", ex);
        }
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String bounded(String value) {
        String text = clean(value);
        return text.length() <= 2_000 ? text : text.substring(0, 2_000) + "…";
    }

    private final class ExecutionState {
        private final StoryRun run;
        private final Map<String, AgentView> agents;
        private final BudgetView budget;
        private final List<String> revisionHistory = new ArrayList<>();
        private int sequence;
        private long totalTokens;
        private boolean limitReached;

        private ExecutionState(StoryRun run, Map<String, AgentView> agents, BudgetView budget) {
            this.run = run;
            this.agents = agents;
            this.budget = budget;
        }

        private void addTokens(long tokens) {
            totalTokens += Math.max(0, tokens);
            run.setTotalTokens(totalTokens);
            runRepository.save(run);
            if (totalTokens >= budget.maxTotalTokens()) {
                limitReached = true;
                run.setStatus("LIMIT_REACHED");
                run.setFinishedAt(now());
                runRepository.save(run);
            }
        }

        private boolean limitReached() {
            return limitReached;
        }
    }
}
