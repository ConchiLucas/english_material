package com.aitaskcenter.service;

import com.aitaskcenter.config.StorySnapshotLimits;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
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
            "^[ \\t]*STORY_TEXT_BEGIN[ \\t]*\\R(.*?)\\R[ \\t]*STORY_TEXT_END[ \\t]*(?:\\R|\\z)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.MULTILINE);
    private static final Pattern MARKDOWN = Pattern.compile(
            "(?m)^\\s*(?:#{1,6}\\s+|[-*+]\\s+|>\\s*|\\d+[.)]\\s+|(?:-{3,}|={3,})\\s*$)|"
                    + "!?\\[[^]\\r\\n]+]\\([^)\\r\\n]+\\)|[*_`~]");
    private static final Pattern AUDIT_SECTION = Pattern.compile(
            "(?im)^\\s*(?:target\\s+words?(?:\\s+checklist)?|word\\s+usage(?:\\s+map)?|"
                    + "score(?:s|\\s+report)?|scoring(?:\\s+report)?|rating(?:s|\\s+report)?|"
                    + "review(?:er)?(?:\\s+(?:notes?|report|summary))?|changes?|"
                    + "change\\s+(?:log|notes?|history|summary)|"
                    + "revision(?:\\s+(?:log|notes?|history|summary))?|"
                    + "analysis|explanation)[ \\t]*(?:[:：]|$)");
    private static final Pattern SCENE_TITLE = Pattern.compile(
            "(?im)^\\s*Scene\\s+1\\s*:\\s*\\S.*$");
    private static final String STORY_OUTPUT_CONTRACT = """

            [运行时最终输出协议：此协议优先于前文的输出结构要求]
            只输出以下边界之间的纯英文故事，不得在边界外输出任何内容：
            STORY_TEXT_BEGIN
            Scene 1: Plain English Title

            Plain English story paragraphs only.
            STORY_TEXT_END
            故事块内禁止 Markdown、中文说明、目标词清单、评分信息、变更记录、表格或代码围栏。
            禁止使役结构 make + 宾语 + 名词补语，禁止宾语从句，禁止定语从句或后置定语从句，禁止分词短语充当后置修饰。每句以常见动作动词或 be 为主，一词一义，不堆抽象心理动词。
            每一个 Scene 必须写出 8 到 16 个可以单独画成一张图的连续动作，每场 5 到 8 个短段落。不要一场只写三四句就换场。
            第一段必须立刻出现可见冲突或意外动作。禁止用 sits、walks to the desk、takes out a book 这类摆拍开场。
            猫用 The cat 或 It，不要用 He 指猫。
            可入画动作不超过 16 个时只写 1 个 Scene。超过 16 个必须再开一场，拆成每场仍有 8 到 16 个动作，例如 8 和 9，禁止拆出不够 8 个动作的薄场。
            若这是重写：必须保住蓝图里每一个 PictureBeat 的可见动作，不得收成 is happy 或删掉书签、笔、抽书、滑行这类动作。
            """;
    private static final String PLANNER_CONTRACT = """

            [运行时用词策划协议]
            必须为每个目标词给出：词义、允许词形、建议场景序号、角色 MUST 或 BACKGROUND。
            MUST 词必须能单独支撑该场一个可见动作或物件；BACKGROUND 词只能作颜色、数量或地点修饰，不得单独成场。
            不得丢词。词数大于 8 时必须给出分场表，一场的 MUST 词不超过 4 个。
            建议场景默认 1 场。只有可入画动作将超过 16 个时才建议第 2 场；每场必须能支撑 8 到 16 个画面。
            """;
    private static final String PITCH_CONTRACT = """

            [运行时提案协议]
            每个场景必须列出 8 到 16 个可入画动作。动作不超过 16 个时只规划 1 个场景。
            超过 16 个再拆场，拆完每场仍是 8 到 16 个，例如 8 和 9，不要拆出薄场。
            只输出提案和节拍，不写英文故事正文。
            """;
    private static final String DIRECTOR_CONTRACT = """

            [运行时蓝图协议]
            第一段必须用固定标题列出：
            MAIN_PITCH: humor|adventure|wonder
            SCENE_COUNT: 1|2|3
            BEAT_COUNTS: 每场画面数，逗号分隔，每场必须是 8 到 16
            TAKE_FROM_HUMOR: 一条不超过 40 词的中文或英文要点
            TAKE_FROM_ADVENTURE: 一条不超过 40 词的要点
            TAKE_FROM_WONDER: 一条不超过 40 词的要点
            主提案对应的 TAKE_FROM_* 写“主提案本身”。另外两个不得写“无”或“不采用”，必须是终稿蓝图里能核对的具体要素。
            每一场必须包含 SetupRequired，以及 8 到 16 个 PictureBeat（可单独入画的动作：谁、做什么、什么物件）。
            可入画动作不超过 16 个时 SCENE_COUNT 必须是 1。超过 16 个必须拆场，BEAT_COUNTS 例如 8,9，每场仍是 8 到 16，禁止出现少于 8 的场。
            只输出选案说明和蓝图，不写英文故事正文。
            """;
    private static final String SCORER_CONTRACT = """

            [运行时评分协议]
            先按现有职责写简短证据，最后必须且只能出现一次：
            SCORE_BEGIN
            fun: <1-5整数>
            language: <1-5整数>
            continuity: <1-5整数>
            grade: <1-5整数>
            SCORE_END
            不得在块外重复这四行。不得输出小数或等级字母。
            简单重复的主谓宾短句不得降低 grade。年级是否扣分见后附年级协议。
            """;
    private static final String DECIDER_CONTRACT = """

            [运行时决策协议]
            第一行必须是：
            ACTION: PASS|REVISE|REWRITE
            第二行必须是：
            BLOCKING: NONE
            或
            BLOCKING: GRADE
            或
            BLOCKING: LANGUAGE
            或
            BLOCKING: GRADE,LANGUAGE
            若 ACTION 不是 PASS，随后必须且只能出现一次：
            ISSUES_JSON_BEGIN
            [{"scene":1,"quote":"原句","type":"GRADE|LANGUAGE|CONTINUITY|FUN|COVERAGE","instruction":"这一句怎么改","replaceWith":"替换后的英文原句","protect":false}]
            ISSUES_JSON_END
            scene 为正整数。quote、instruction、replaceWith 非空。replaceWith 必须是可直接写入故事的英文句子。
            type 只能是上述枚举。数组至少 1 项、至多 4 项。
            禁止要求加入从句、使役结构、后置定语或“更高级/更文学”的句式。GRADE 与 LANGUAGE 只允许拆句或换词。
            replaceWith 必须保留原句里的可见动作：谁、做什么、碰到什么。禁止收成纯状态，例如 Leo is happy、The cat is on the book。
            FUN 只能换成另一个可见动作，禁止只加 happily、sadly、excitedly 这类空情绪词。
            GRADE 与 LANGUAGE 不得把 jumps、slides、puts、pulls、stretches 等画面动词收成 moves 或 is，但可以换成另一个画面动词，例如 tap 换成 hit。
            不要删掉 paw、page、rug、desk 这类可入画物件，只许换成更简单的同类词，例如 paw 换成 hand。
            不合规的替换句会被忽略；若全部不合规，本轮不再改稿，交付当前最好一稿。
            蓝图 PictureBeat 或 TAKE_FROM 里的物件只许换成更简单的词，不许删掉动作。
            语言低于 3，或在需要管超纲时年级低到 1，先选 REVISE，不要整篇 REWRITE。只有已经修订过一轮仍阻断，或同时缺目标词，才选 REWRITE。
            是否开 GRADE 问题见后附年级协议。
            不得把一场收成不够 8 个可画动作的薄场。只有一场将超过 16 个可画动作时才要求拆场，拆完例如 8 和 9。
            不要输出 TARGET_NODE。不要输出 REPLAN、REPITCH、REDIRECT。
            执行器忽略本输出中的 BLOCKING 行，是否重写只看评分块的 language 与 grade，以及是否已经修订过。
            """;
    private static final String GRADE_OPEN_CONTRACT = """

            [运行时年级协议]
            本批次不限制学段或不考虑超纲。不要判断超纲，不要因用词难度扣 grade、开 GRADE 问题或换掉具体画面词。grade 给 4 或 5。
            """;
    private static final String GRADE_PRIMARY_CONTRACT = """

            [运行时年级协议]
            本批次为小学。用词不超过高中即可，paw、rug、cardboard、kneel、sigh、sunglasses 都可用。
            只有大学学术词或专业术语这种离谱超纲才扣 grade 或换词。不要为高中及以内的词开 GRADE 问题或整篇重写。
            """;
    private static final List<String> PITCH_KEYS = List.of("pitch-humor", "pitch-adventure", "pitch-wonder");
    private static final List<String> REVIEW_KEYS = List.of("review-fun", "review-language", "review-continuity");
    private static final Pattern DECISION_LINE = Pattern.compile(
            "(?i)^\\s*ACTION\\s*:\\s*(PASS|REVISE|REWRITE)\\b.*");
    private static final Pattern SCORE_BLOCK = Pattern.compile(
            "(?is)SCORE_BEGIN\\s*(.*?)\\s*SCORE_END");
    private static final Pattern ISSUES_BLOCK = Pattern.compile(
            "(?is)ISSUES_JSON_BEGIN\\s*(.*?)\\s*ISSUES_JSON_END");
    private static final Pattern MAIN_PITCH_LINE = Pattern.compile(
            "(?im)^MAIN_PITCH:\\s*(humor|adventure|wonder)\\s*$");
    private static final Pattern SCENE_COUNT_LINE = Pattern.compile(
            "(?im)^SCENE_COUNT:\\s*([123])\\s*$");
    private static final Pattern BEAT_COUNTS_LINE = Pattern.compile(
            "(?im)^BEAT_COUNTS:\\s*(\\d+(?:\\s*,\\s*\\d+)*)\\s*$");
    private static final Set<String> ISSUE_TYPES = Set.of("GRADE", "LANGUAGE", "CONTINUITY", "FUN", "COVERAGE");
    private static final Set<String> ACTION_STEMS = Set.of(
            "ask", "call", "carry", "catch", "climb", "close", "draw", "drop", "fall", "go",
            "hit", "hold", "hug", "jump", "kick", "land", "lie", "lift", "look", "nod",
            "open", "pick", "play", "point", "pull", "push", "put", "reach", "read", "rest",
            "roll", "run", "say", "see", "shake", "shout", "show", "sit", "sleep", "slide",
            "smile", "stand", "step", "stretch", "take", "tap", "throw", "touch", "turn", "walk",
            "wave", "wrap", "write");
    private static final Pattern STATE_ONLY = Pattern.compile(
            "(?i)\\b(?:is|are|was|were|'s)\\s+(?:happy|sad|glad|angry|fine|sorry|ready|quiet|asleep)\\b");
    private static final Pattern LOCATIVE_COPULA = Pattern.compile(
            "(?i)\\b(?:is|are|was|were|'s)\\s+(?:on|in|at|under|over|near)\\b");
    private static final Pattern EMPTY_TONE = Pattern.compile(
            "(?i)\\b(?:happily|sadly|angrily|excitedly|quietly|suddenly|nervously|carefully|slowly|quickly)\\b");
    private static final Set<String> PICTURE_VERBS = Set.of(
            "catch", "climb", "close", "draw", "drop", "hug", "hit", "jump", "kick", "land",
            "lie", "lift", "open", "pick", "pull", "push", "put", "rest", "roll", "run",
            "scratch", "shake", "sit", "sleep", "slide", "smile", "stretch", "tap", "throw",
            "touch", "wave");
    private static final Set<String> PICTURE_NOUNS = Set.of(
            "book", "cat", "desk", "page", "pages", "paw", "pen", "rug");
    private static final Map<String, Set<String>> PICTURE_NOUN_SYNONYMS = Map.of(
            "paw", Set.of("hand", "hands"),
            "page", Set.of("pages", "book"),
            "pages", Set.of("page", "book"),
            "rug", Set.of("mat"),
            "desk", Set.of("table"));

    private final StoryRunRepository runRepository;
    private final StoryRunStepRepository stepRepository;
    private final StoryAgentService agentService;
    private final AiConfigService aiConfigService;
    private final AiTextGenerationService generationService;
    private final StoryWordSourceService wordSourceService;
    private final ObjectMapper objectMapper;
    private final TaskExecutor executor;
    private final TaskExecutor callExecutor;

    public StoryRunExecutionService(
            StoryRunRepository runRepository,
            StoryRunStepRepository stepRepository,
            StoryAgentService agentService,
            AiConfigService aiConfigService,
            AiTextGenerationService generationService,
            StoryWordSourceService wordSourceService,
            ObjectMapper objectMapper,
            @Qualifier("storyRunExecutor") TaskExecutor executor,
            @Qualifier("storyAgentCallExecutor") TaskExecutor callExecutor) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.agentService = agentService;
        this.aiConfigService = aiConfigService;
        this.generationService = generationService;
        this.wordSourceService = wordSourceService;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.callExecutor = callExecutor;
    }

    public RunSummary createRun(StartRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请提交运行参数");
        }
        List<StoryWord> words = wordSourceService.normalizeManualWords(request.words());
        String targetGrade = clean(request.targetGrade());
        if (targetGrade == null) {
            targetGrade = "";
        }
        String inputWordsJson = writeJson(words);
        if (inputWordsJson.length() > StorySnapshotLimits.MAX_WORD_SNAPSHOT_CHARS) {
            throw new IllegalArgumentException("单词快照超过最大长度");
        }
        StoryRun run = new StoryRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setInputWordsJson(inputWordsJson);
        run.setTargetGrade(targetGrade);
        run.setStatus("QUEUED");
        runRepository.save(run);
        try {
            executor.execute(() -> execute(run.getRunId()));
        } catch (RejectedExecutionException ex) {
            run.setStatus("FAILED");
            run.setErrorMessage("运行队列已满，请稍后重试");
            run.setFinishedAt(now());
            runRepository.save(run);
            throw new IllegalArgumentException("运行队列已满，请稍后重试");
        }
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

            String plan = call(state, "vocabulary-planner", 0, mapOf(
                    "targetWords", words,
                    "targetGrade", gradeContext(run.getTargetGrade()),
                    "sourceContext", "运行批次内保存的单词快照"));
            if (state.limitReached()) return;

            Map<String, Map<String, Object>> pitchInputs = new LinkedHashMap<>();
            for (String key : PITCH_KEYS) {
                pitchInputs.put(key, mapOf(
                        "vocabularyPlan", plan,
                        "targetGrade", gradeContext(run.getTargetGrade()),
                        "priorQualityFeedback", ""));
            }
            Map<String, String> pitches = callParallel(state, 0, pitchInputs);
            String blueprint = call(state, "story-director", 0, mapOf(
                    "vocabularyPlan", plan,
                    "anonymousPitches", pitches.values(),
                    "targetGrade", gradeContext(run.getTargetGrade()),
                    "directorFeedback", ""));
            if (state.limitReached()) return;

            String candidate = call(state, "story-writer", 0, mapOf(
                    "vocabularyPlan", plan,
                    "storyBlueprint", blueprint,
                    "targetGrade", gradeContext(run.getTargetGrade()),
                    "writerFeedback", ""));
            run.setFinalStory(candidate);
            runRepository.save(run);
            if (state.limitReached()) return;

            int cycle = 1;
            int revisions = 0;
            int writerRewrites = 0;
            Integer bestTotal = null;
            String bestCandidate = null;
            Scores previousScores = null;
            String previousCandidate = null;
            boolean lastAppliedEdit = false;
            while (true) {
                List<WordUsage> wordUsage = scanTargetWords(words, candidate);
                boolean missingWords = wordUsage.stream().anyMatch(item -> !item.covered());
                Map<String, Map<String, Object>> reviewInputs = new LinkedHashMap<>();
                reviewInputs.put("review-fun", mapOf(
                        "candidateStory", candidate,
                        "targetGrade", gradeContext(run.getTargetGrade()),
                        "qualityRound", cycle));
                reviewInputs.put("review-language", mapOf(
                        "candidateStory", candidate,
                        "targetWords", words,
                        "wordUsage", wordUsage,
                        "targetGrade", gradeContext(run.getTargetGrade()),
                        "qualityRound", cycle));
                reviewInputs.put("review-continuity", mapOf(
                        "candidateStory", candidate,
                        "storyBlueprint", blueprint,
                        "qualityRound", cycle));
                Map<String, String> reviews = callParallel(state, cycle, reviewInputs);
                String score = call(state, "story-scorer", cycle, mapOf(
                        "candidateStory", candidate,
                        "funReview", reviews.get("review-fun"),
                        "languageReview", reviews.get("review-language"),
                        "continuityReview", reviews.get("review-continuity"),
                        "scoringRubric", isGradeOpen(run.getTargetGrade())
                                ? "趣味性、用词自然度、连续性；本批次不评超纲"
                                : "趣味性、用词自然度、连续性；小学仅拦离谱的大学或专业词"));
                Scores scores = parseScores(score);
                if (lastAppliedEdit && previousScores != null
                        && (scores.fun() < previousScores.fun()
                        || scores.continuity() < previousScores.continuity())) {
                    candidate = previousCandidate;
                    scores = previousScores;
                    wordUsage = scanTargetWords(words, candidate);
                    missingWords = wordUsage.stream().anyMatch(item -> !item.covered());
                } else {
                    previousCandidate = candidate;
                    previousScores = scores;
                }
                lastAppliedEdit = false;
                if (bestTotal == null || scores.total() >= bestTotal) {
                    bestTotal = scores.total();
                    bestCandidate = candidate;
                }
                if (state.limitReached()) return;
                String decision = call(state, "quality-decider", cycle, mapOf(
                        "reviewReports", reviews,
                        "scoreReport", score,
                        "budgetState", state.budget,
                        "revisionHistory", state.revisionHistory));
                if (state.limitReached()) return;
                state.revisionHistory.add(decision);
                Decision parsed = parseDecision(decision);
                boolean blocking = scores.language() < 3
                        || (!isGradeOpen(run.getTargetGrade()) && scores.grade() < 2);
                String action = parsed.action();
                String issuesJson = parsed.issuesJson();
                if ("PASS".equals(action) && (blocking || missingWords)) {
                    action = "REVISE";
                    if (issuesJson == null) {
                        issuesJson = coverageIssues(candidate, wordUsage);
                    }
                }
                if (!blocking && "REWRITE".equals(action)) {
                    action = "REVISE";
                }
                if (blocking && missingWords) {
                    action = "REWRITE";
                } else if (blocking && revisions == 0) {
                    action = "REVISE";
                } else if (blocking && revisions > 0) {
                    action = "REWRITE";
                }
                int edits = revisions + writerRewrites;
                if (!blocking && !missingWords) {
                    if ("PASS".equals(action) && scores.continuity() >= 3) {
                        complete(run, "COMPLETED",
                                bestCandidate != null ? bestCandidate : candidate, state.totalTokens);
                        return;
                    }
                    if (edits > 0 && scores.continuity() >= 3) {
                        complete(run, "COMPLETED",
                                bestCandidate != null ? bestCandidate : candidate, state.totalTokens);
                        return;
                    }
                    if (edits >= 2) {
                        complete(run, "COMPLETED",
                                bestCandidate != null ? bestCandidate : candidate, state.totalTokens);
                        return;
                    }
                    if (scores.continuity() < 3) {
                        action = "REVISE";
                    }
                }
                if (!"PASS".equals(action) && (issuesJson == null || issuesJson.isBlank() || "[]".equals(issuesJson.trim()))) {
                    complete(run, "COMPLETED",
                            bestCandidate != null ? bestCandidate : candidate, state.totalTokens);
                    return;
                }
                boolean applied = false;
                if ("REWRITE".equals(action) && writerRewrites < state.budget.maxWriterRewrites()) {
                    writerRewrites++;
                    candidate = call(state, "story-writer", cycle, mapOf(
                            "vocabularyPlan", plan,
                            "storyBlueprint", blueprint,
                            "targetGrade", gradeContext(run.getTargetGrade()),
                            "writerFeedback", Map.of(
                                    "reason", parsed.reason(),
                                    "issues", readIssues(issuesJson),
                                    "wordUsage", wordUsage)));
                    applied = true;
                    lastAppliedEdit = true;
                } else if ("REVISE".equals(action) && revisions < state.budget.maxLocalRevisions()) {
                    revisions++;
                    candidate = call(state, "targeted-reviser", cycle, mapOf(
                            "candidateStory", candidate,
                            "issueList", issuesJson == null ? "[]" : issuesJson,
                            "protectedPasses", reviews,
                            "vocabularyPlan", plan,
                            "storyBlueprint", blueprint));
                    applied = true;
                    lastAppliedEdit = true;
                }
                if (!applied) {
                    complete(run, "LIMIT_REACHED",
                            bestCandidate != null ? bestCandidate : candidate, state.totalTokens);
                    return;
                }
                run.setFinalStory(candidate);
                runRepository.save(run);
                if (state.limitReached()) return;
                if (cycle >= state.budget.maxQualityRounds()) {
                    complete(run, "LIMIT_REACHED", candidate, state.totalTokens);
                    return;
                }
                cycle++;
            }
        } catch (BudgetLimitReachedException ex) {
            run.setStatus("LIMIT_REACHED");
            run.setFinishedAt(now());
            runRepository.save(run);
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
        return call(state, agentKey, qualityRound, input, state.nextSequence());
    }

    private String call(
            ExecutionState state,
            String agentKey,
            int qualityRound,
            Map<String, Object> input,
            int sequence) {
        AgentView agent = state.agents.get(agentKey);
        if (agent == null || !Boolean.TRUE.equals(agent.enabled())) {
            throw new IllegalArgumentException("故事 Agent「" + agentKey + "」未启用或不存在");
        }
        AiProviderConfigItem provider = aiConfigService.getProviderForExecution(agent.aiProviderId());
        if (!provider.isEnabled()
                || (provider.getCapabilities() != null
                && !provider.getCapabilities().isEmpty()
                && !provider.getCapabilities().contains("TEXT_GENERATION"))) {
            throw new IllegalArgumentException("故事 Agent「" + agentKey + "」的 AI 配置未启用或不支持文本生成");
        }
        String inputJson = writeJson(input);
        String systemPrompt = withRuntimeContract(agentKey, agent.systemPrompt(), state.run.getTargetGrade());
        int configuredMax = provider.getMaxTokens() == null ? 4096 : provider.getMaxTokens();
        BudgetReservation reservation = state.reserve(systemPrompt, inputJson, configuredMax);
        long started = System.nanoTime();
        StoryRunStep step = new StoryRunStep();
        step.setRunId(state.run.getRunId());
        step.setSequence(sequence);
        step.setQualityRound(qualityRound);
        step.setAgentKey(agent.key());
        step.setAgentName(agent.name());
        step.setPromptVersion(agent.promptVersion() == null ? 0 : agent.promptVersion());
        step.setProviderId(agent.aiProviderId());
        step.setProviderModel(provider.getModel());
        step.setInputJson(inputJson);
        boolean generationCompleted = false;
        boolean reservationSettled = false;
        long inputTokens = 0;
        long outputTokens = 0;
        long totalTokens = 0;
        try {
            AiTextGenerationService.GenerationResult result = generationService.generateWithUsage(
                    provider,
                    systemPrompt,
                    inputJson,
                    agent.temperature() == null ? 0.2 : agent.temperature(),
                    reservation.maxOutputTokens());
            generationCompleted = true;
            inputTokens = result.inputTokens() > 0
                    ? result.inputTokens()
                    : estimateTokens(systemPrompt + "\n" + inputJson);
            outputTokens = result.outputTokens() > 0
                    ? result.outputTokens()
                    : estimateTokens(result.text());
            totalTokens = result.totalTokens() > 0
                    ? result.totalTokens()
                    : inputTokens + outputTokens;
            step.setOutputText(result.text());
            step.setInputTokens(inputTokens);
            step.setOutputTokens(outputTokens);
            step.setTotalTokens(totalTokens);
            String response = producesStory(agentKey) ? extractStory(result.text()) : result.text();
            validateAgentOutput(agentKey, response);
            step.setStatus("COMPLETED");
            step.setDurationMs(elapsedMillis(started));
            stepRepository.save(step);
            state.completeReservation(reservation, totalTokens);
            reservationSettled = true;
            return response;
        } catch (Exception ex) {
            if (generationCompleted && !reservationSettled) {
                state.completeReservation(reservation, totalTokens);
            } else if (!reservationSettled) {
                state.cancelReservation(reservation);
            }
            String safeMessage = redact(ex.getMessage(), provider.getApiKey());
            if (!generationCompleted) {
                step.setOutputText(safeMessage);
            }
            step.setStatus("FAILED");
            step.setDurationMs(elapsedMillis(started));
            stepRepository.save(step);
            if (ex instanceof BudgetLimitReachedException limit) throw limit;
            throw new IllegalArgumentException(safeMessage, ex);
        }
    }

    private Map<String, String> callParallel(
            ExecutionState state,
            int qualityRound,
            Map<String, Map<String, Object>> inputs) {
        List<CompletableFuture<Map.Entry<String, String>>> futures = new ArrayList<>();
        inputs.forEach((key, input) -> {
            int sequence = state.nextSequence();
            futures.add(CompletableFuture.supplyAsync(
                    () -> Map.entry(key, call(state, key, qualityRound, input, sequence)),
                    command -> callExecutor.execute(command)));
        });
        Map<String, String> result = new LinkedHashMap<>();
        try {
            for (CompletableFuture<Map.Entry<String, String>> future : futures) {
                Map.Entry<String, String> entry = future.join();
                result.put(entry.getKey(), entry.getValue());
            }
            return result;
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
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
        run.setFinalStory(candidate);
        run.setTotalTokens(totalTokens);
        run.setFinishedAt(now());
        runRepository.save(run);
    }

    static List<WordUsage> scanTargetWords(List<StoryWord> words, String story) {
        String text = story == null ? "" : story.toLowerCase(Locale.ROOT);
        List<WordUsage> result = new ArrayList<>();
        for (StoryWord word : words) {
            String base = word == null || word.word() == null ? "" : word.word().trim().toLowerCase(Locale.ROOT);
            if (base.isEmpty()) {
                continue;
            }
            List<String> formsFound = new ArrayList<>();
            int count = 0;
            for (String form : inflections(base)) {
                Matcher matcher = Pattern.compile("\\b" + Pattern.quote(form) + "\\b").matcher(text);
                int hits = 0;
                while (matcher.find()) {
                    hits++;
                }
                if (hits > 0) {
                    formsFound.add(form);
                    count += hits;
                }
            }
            result.add(new WordUsage(word.word().trim(), List.copyOf(formsFound), count, count > 0));
        }
        return result;
    }

    private static List<String> inflections(String base) {
        LinkedHashMap<String, Boolean> forms = new LinkedHashMap<>();
        forms.put(base, true);
        forms.put(base + "s", true);
        forms.put(base + "es", true);
        if (base.endsWith("y") && base.length() > 1) {
            forms.put(base.substring(0, base.length() - 1) + "ies", true);
        }
        if (base.endsWith("e")) {
            forms.put(base + "d", true);
            forms.put(base.substring(0, base.length() - 1) + "ing", true);
        } else {
            forms.put(base + "ed", true);
            forms.put(base + "ing", true);
        }
        return List.copyOf(forms.keySet());
    }

    static boolean isGradeOpen(String targetGrade) {
        if (!StringUtils.hasText(targetGrade)) {
            return true;
        }
        String text = targetGrade.trim();
        return text.contains("不限制") || text.contains("未指定") || text.contains("不考虑")
                || text.contains("初中") || text.contains("高中") || text.contains("大学");
    }

    static String gradeContext(String targetGrade) {
        if (!StringUtils.hasText(targetGrade)) {
            return "未指定（不考虑超纲）";
        }
        return isGradeOpen(targetGrade) ? targetGrade.trim() + "（不考虑超纲）" : targetGrade.trim();
    }

    private static String withRuntimeContract(String agentKey, String configuredPrompt, String targetGrade) {
        String configured = configuredPrompt == null ? "" : configuredPrompt;
        String contract = switch (agentKey) {
            case "story-writer", "targeted-reviser" -> STORY_OUTPUT_CONTRACT;
            case "vocabulary-planner" -> PLANNER_CONTRACT;
            case "pitch-humor", "pitch-adventure", "pitch-wonder" -> PITCH_CONTRACT;
            case "story-director" -> DIRECTOR_CONTRACT;
            case "story-scorer" -> SCORER_CONTRACT;
            case "quality-decider" -> DECIDER_CONTRACT;
            default -> "";
        };
        String result = configured;
        if (!contract.isBlank()) {
            String marker = contract.lines().filter(line -> line.startsWith("[运行时")).findFirst().orElse(contract.trim());
            if (!configured.contains(marker.trim())) {
                result = configured + contract;
            }
        }
        String gradePolicy = gradePolicyContract(agentKey, targetGrade);
        if (!gradePolicy.isBlank() && !result.contains("[运行时年级协议]")) {
            result += gradePolicy;
        }
        return result;
    }

    private static String gradePolicyContract(String agentKey, String targetGrade) {
        if (!Set.of("story-writer", "targeted-reviser", "story-scorer", "quality-decider", "review-language")
                .contains(agentKey)) {
            return "";
        }
        return isGradeOpen(targetGrade) ? GRADE_OPEN_CONTRACT : GRADE_PRIMARY_CONTRACT;
    }

    private void validateAgentOutput(String agentKey, String output) {
        if ("story-director".equals(agentKey)) {
            validateDirectorBlueprint(output);
        } else if ("story-scorer".equals(agentKey)) {
            parseScores(output);
        } else if ("quality-decider".equals(agentKey)) {
            parseDecision(output);
        }
    }

    private static void validateDirectorBlueprint(String output) {
        String text = output == null ? "" : output;
        Matcher sceneCount = SCENE_COUNT_LINE.matcher(text);
        if (!MAIN_PITCH_LINE.matcher(text).find() || !sceneCount.find()) {
            throw new IllegalArgumentException("故事导演蓝图缺少主提案或落选吸收项");
        }
        Matcher beatCounts = BEAT_COUNTS_LINE.matcher(text);
        if (!beatCounts.find()) {
            throw new IllegalArgumentException("故事导演蓝图缺少场次或每场画面数");
        }
        String[] parts = beatCounts.group(1).split("\\s*,\\s*");
        if (parts.length != Integer.parseInt(sceneCount.group(1))) {
            throw new IllegalArgumentException("故事导演蓝图缺少场次或每场画面数");
        }
        for (String part : parts) {
            int beats = Integer.parseInt(part);
            if (beats < 8 || beats > 16) {
                throw new IllegalArgumentException("故事导演蓝图缺少场次或每场画面数");
            }
        }
        for (String label : List.of("TAKE_FROM_HUMOR", "TAKE_FROM_ADVENTURE", "TAKE_FROM_WONDER")) {
            Matcher matcher = Pattern.compile("(?im)^" + label + ":\\s*(.+)\\s*$").matcher(text);
            if (!matcher.find()) {
                throw new IllegalArgumentException("故事导演蓝图缺少主提案或落选吸收项");
            }
            String value = matcher.group(1).trim();
            if (value.isEmpty() || value.length() > 80) {
                throw new IllegalArgumentException("故事导演蓝图缺少主提案或落选吸收项");
            }
        }
    }

    static Scores parseScores(String output) {
        String text = output == null ? "" : output;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.indexOf("score_begin") != lower.lastIndexOf("score_begin")
                || lower.indexOf("score_end") != lower.lastIndexOf("score_end")) {
            throw new IllegalArgumentException("评分输出缺少 SCORE 块");
        }
        Matcher matcher = SCORE_BLOCK.matcher(text);
        if (!matcher.find()) {
            throw new IllegalArgumentException("评分输出缺少 SCORE 块");
        }
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String line : matcher.group(1).split("\\R")) {
            Matcher pair = Pattern.compile("(?i)^(fun|language|continuity|grade)\\s*:\\s*([1-5])\\s*$")
                    .matcher(line.trim());
            if (pair.matches()) {
                values.put(pair.group(1).toLowerCase(Locale.ROOT), Integer.parseInt(pair.group(2)));
            }
        }
        if (values.size() != 4) {
            throw new IllegalArgumentException("评分输出缺少 SCORE 块");
        }
        return new Scores(values.get("fun"), values.get("language"), values.get("continuity"), values.get("grade"));
    }

    static Decision parseDecision(String decision) {
        if (decision == null) {
            throw new IllegalArgumentException("质量决策必须在首行输出 ACTION:");
        }
        List<String> lines = new ArrayList<>();
        for (String line : decision.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line.trim());
            }
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("质量决策必须在首行输出 ACTION:");
        }
        Matcher actionMatcher = DECISION_LINE.matcher(lines.get(0));
        if (!actionMatcher.matches()) {
            throw new IllegalArgumentException("质量决策必须在首行输出 ACTION:");
        }
        String action = actionMatcher.group(1).toUpperCase(Locale.ROOT);
        String blocking = "NONE";
        if (lines.size() > 1 && lines.get(1).toUpperCase(Locale.ROOT).startsWith("BLOCKING:")) {
            blocking = lines.get(1).substring("BLOCKING:".length()).trim().toUpperCase(Locale.ROOT);
        }
        String issuesJson = null;
        if (!"PASS".equals(action)) {
            String lower = decision.toLowerCase(Locale.ROOT);
            if (lower.indexOf("issues_json_begin") != lower.lastIndexOf("issues_json_begin")
                    || lower.indexOf("issues_json_end") != lower.lastIndexOf("issues_json_end")) {
                throw new IllegalArgumentException("质量决策问题清单无效");
            }
            Matcher issues = ISSUES_BLOCK.matcher(decision);
            if (!issues.find()) {
                throw new IllegalArgumentException("质量决策问题清单无效");
            }
            issuesJson = validateIssuesJson(issues.group(1).trim());
        }
        String reason = decision;
        int issuesAt = decision.toUpperCase(Locale.ROOT).indexOf("ISSUES_JSON_BEGIN");
        if (issuesAt > 0) {
            reason = decision.substring(0, issuesAt).trim();
        }
        return new Decision(action, blocking, issuesJson, reason);
    }

    private static String validateIssuesJson(String raw) {
        try {
            JsonNode root = new ObjectMapper().readTree(raw);
            if (root == null || !root.isArray() || root.size() < 1 || root.size() > 4) {
                throw new IllegalArgumentException("质量决策问题清单无效");
            }
            List<JsonNode> kept = new ArrayList<>();
            for (JsonNode item : root) {
                if (item == null || !item.isObject()) {
                    throw new IllegalArgumentException("质量决策问题清单无效");
                }
                int scene = item.path("scene").asInt(0);
                String quote = item.path("quote").asText("").trim();
                String type = item.path("type").asText("").trim().toUpperCase(Locale.ROOT);
                String instruction = item.path("instruction").asText("").trim();
                String replaceWith = item.path("replaceWith").asText("").trim();
                if (scene < 1 || quote.isEmpty() || instruction.isEmpty() || replaceWith.isEmpty()
                        || !ISSUE_TYPES.contains(type)) {
                    throw new IllegalArgumentException("质量决策问题清单无效");
                }
                if (addsEmptyTone(quote, replaceWith)
                        || dropsVisibleAction(quote, replaceWith)
                        || (("GRADE".equals(type) || "LANGUAGE".equals(type))
                        && (dropsPictureVerb(quote, replaceWith) || dropsPictureNoun(quote, replaceWith)))) {
                    continue;
                }
                kept.add(item);
            }
            return new ObjectMapper().writeValueAsString(kept);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("质量决策问题清单无效", ex);
        }
    }

    static boolean addsEmptyTone(String quote, String replaceWith) {
        if (replaceWith == null || replaceWith.isBlank()) {
            return false;
        }
        Matcher matcher = EMPTY_TONE.matcher(replaceWith);
        while (matcher.find()) {
            String word = matcher.group();
            if (quote == null || !Pattern.compile("(?i)\\b" + Pattern.quote(word) + "\\b").matcher(quote).find()) {
                return true;
            }
        }
        return false;
    }

    static boolean dropsPictureVerb(String quote, String replaceWith) {
        Set<String> quoted = pictureStems(quote);
        if (quoted.isEmpty()) {
            return false;
        }
        return pictureStems(replaceWith).isEmpty();
    }

    static boolean dropsPictureNoun(String quote, String replaceWith) {
        Set<String> quoted = pictureNouns(quote);
        if (quoted.isEmpty()) {
            return false;
        }
        Set<String> replacedWords = wordsIn(replaceWith);
        for (String noun : quoted) {
            if (replacedWords.contains(noun)) {
                continue;
            }
            Set<String> synonyms = PICTURE_NOUN_SYNONYMS.getOrDefault(noun, Set.of());
            if (synonyms.stream().noneMatch(replacedWords::contains)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> wordsIn(String text) {
        Set<String> words = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return words;
        }
        Matcher tokens = Pattern.compile("[A-Za-z']+").matcher(text.toLowerCase(Locale.ROOT));
        while (tokens.find()) {
            words.add(tokens.group());
        }
        return words;
    }

    private static Set<String> pictureNouns(String text) {
        Set<String> nouns = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return nouns;
        }
        Matcher tokens = Pattern.compile("[A-Za-z']+").matcher(text.toLowerCase(Locale.ROOT));
        while (tokens.find()) {
            String token = tokens.group();
            if (PICTURE_NOUNS.contains(token)) {
                nouns.add(token);
            }
        }
        return nouns;
    }

    private static Set<String> pictureStems(String text) {
        Set<String> stems = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return stems;
        }
        Matcher tokens = Pattern.compile("[A-Za-z']+").matcher(text.toLowerCase(Locale.ROOT));
        while (tokens.find()) {
            String token = tokens.group();
            String stem = verbStem(token);
            for (String candidate : List.of(token, stem, stem + "e")) {
                if (PICTURE_VERBS.contains(candidate)) {
                    stems.add(candidate);
                }
            }
        }
        return stems;
    }

    static boolean dropsVisibleAction(String quote, String replaceWith) {
        String replacement = replaceWith == null ? "" : replaceWith.trim();
        if (replacement.isEmpty()) {
            return true;
        }
        if (STATE_ONLY.matcher(replacement).find() && !hasActionVerb(replacement)) {
            return true;
        }
        if (hasActionVerb(quote)
                && !hasActionVerb(replacement)
                && LOCATIVE_COPULA.matcher(replacement).find()) {
            return true;
        }
        return hasActionVerb(quote) && !hasActionVerb(replacement);
    }

    private static boolean hasActionVerb(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Matcher tokens = Pattern.compile("[A-Za-z']+").matcher(text.toLowerCase(Locale.ROOT));
        while (tokens.find()) {
            String token = tokens.group();
            String stem = verbStem(token);
            if (ACTION_STEMS.contains(token) || ACTION_STEMS.contains(stem) || ACTION_STEMS.contains(stem + "e")) {
                return true;
            }
        }
        return false;
    }

    private static String verbStem(String token) {
        if (token.endsWith("ies") && token.length() > 4) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.endsWith("ing") && token.length() > 5) {
            return token.substring(0, token.length() - 3);
        }
        if (token.endsWith("ed") && token.length() > 4) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("es") && token.length() > 4) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("s") && token.length() > 3 && !token.endsWith("ss")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    private List<Map<String, Object>> readIssues(String issuesJson) {
        if (issuesJson == null || issuesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readerForListOf(Map.class).readValue(issuesJson);
        } catch (Exception ex) {
            throw new IllegalArgumentException("质量决策问题清单无效", ex);
        }
    }

    private String coverageIssues(String candidate, List<WordUsage> usage) {
        List<String> missing = usage.stream()
                .filter(item -> !item.covered())
                .map(WordUsage::word)
                .toList();
        String quote = candidate == null || candidate.isBlank() ? "story" : candidate.lines().findFirst().orElse("story");
        String missingList = String.join("、", missing);
        return writeJson(List.of(Map.of(
                "scene", 1,
                "quote", quote,
                "type", "COVERAGE",
                "instruction", "补上缺失目标词：" + missingList,
                "replaceWith", quote + " " + missingList + ".",
                "protect", false)));
    }

    public record WordUsage(String word, List<String> formsFound, int count, boolean covered) {
        public WordUsage {
            formsFound = List.copyOf(formsFound);
        }
    }

    record Scores(int fun, int language, int continuity, int grade) {
        private int total() {
            return fun + language + continuity + grade;
        }

        private boolean blocking() {
            return language < 3 || grade < 2;
        }
    }

    record Decision(String action, String blocking, String issuesJson, String reason) {
    }

    static String extractStory(String output) {
        String value = output == null ? "" : output.trim();
        String normalizedValue = value.toLowerCase(Locale.ROOT);
        if (normalizedValue.indexOf("story_text_begin")
                        != normalizedValue.lastIndexOf("story_text_begin")
                || normalizedValue.indexOf("story_text_end")
                        != normalizedValue.lastIndexOf("story_text_end")) {
            throw invalidStoryOutput();
        }
        Matcher matcher = STORY_BLOCK.matcher(value);
        if (!matcher.find()) {
            throw invalidStoryOutput();
        }
        String story = matcher.group(1).trim();
        String normalized = story.toLowerCase(Locale.ROOT);
        if (story.isEmpty()
                || !SCENE_TITLE.matcher(story).find()
                || normalized.contains("story_text_begin")
                || normalized.contains("story_text_end")
                || MARKDOWN.matcher(story).find()
                || story.indexOf('|') >= 0
                || AUDIT_SECTION.matcher(story).find()
                || containsNonLatinText(story)
                || normalized.contains("target words checklist")
                || normalized.contains("revision log")) {
            throw invalidStoryOutput();
        }
        return story;
    }

    private static boolean producesStory(String agentKey) {
        return "story-writer".equals(agentKey) || "targeted-reviser".equals(agentKey);
    }

    private static boolean containsNonLatinText(String story) {
        return story.codePoints().anyMatch(codePoint -> {
            if (Character.isLetter(codePoint)) {
                return Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN;
            }
            return Character.getType(codePoint) == Character.OTHER_SYMBOL;
        });
    }

    private static IllegalArgumentException invalidStoryOutput() {
        return new IllegalArgumentException("故事输出格式错误：只允许纯英文场景标题和故事正文");
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

    private static long estimateTokens(String value) {
        int codePoints = value == null ? 0 : value.codePointCount(0, value.length());
        return Math.max(1, (codePoints + 3L) / 4L);
    }

    private static long inputTokenUpperBound(String value) {
        return Math.max(1, value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length);
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

    private static String redact(String value, String secret) {
        String safe = bounded(value);
        return StringUtils.hasText(secret) ? safe.replace(secret, "[REDACTED]") : safe;
    }

    private record BudgetReservation(long reservedTokens, int maxOutputTokens) {
    }

    private static final class BudgetLimitReachedException extends RuntimeException {
        private BudgetLimitReachedException() {
            super("已达到 Token 上限");
        }
    }

    private final class ExecutionState {
        private final StoryRun run;
        private final Map<String, AgentView> agents;
        private final BudgetView budget;
        private final List<String> revisionHistory = new ArrayList<>();
        private int sequence;
        private long totalTokens;
        private long reservedTokens;
        private boolean limitReached;

        private ExecutionState(StoryRun run, Map<String, AgentView> agents, BudgetView budget) {
            this.run = run;
            this.agents = agents;
            this.budget = budget;
        }

        private synchronized int nextSequence() {
            return ++sequence;
        }

        private synchronized BudgetReservation reserve(String systemPrompt, String inputJson, int providerMax) {
            long inputUpper = inputTokenUpperBound(systemPrompt + "\n" + inputJson);
            long remaining = budget.maxTotalTokens() - totalTokens - reservedTokens;
            if (remaining <= inputUpper) {
                markLimitReached();
                throw new BudgetLimitReachedException();
            }
            int maxOutput = (int) Math.min(Math.max(1, providerMax), remaining - inputUpper);
            long reserved = inputUpper + maxOutput;
            reservedTokens += reserved;
            return new BudgetReservation(reserved, maxOutput);
        }

        private synchronized void completeReservation(BudgetReservation reservation, long tokens) {
            reservedTokens = Math.max(0, reservedTokens - reservation.reservedTokens());
            totalTokens += Math.max(0, tokens);
            run.setTotalTokens(totalTokens);
            runRepository.save(run);
            if (totalTokens >= budget.maxTotalTokens()) {
                markLimitReached();
            }
        }

        private synchronized void cancelReservation(BudgetReservation reservation) {
            reservedTokens = Math.max(0, reservedTokens - reservation.reservedTokens());
        }

        private void markLimitReached() {
            limitReached = true;
            run.setStatus("LIMIT_REACHED");
            run.setTotalTokens(totalTokens);
            run.setFinishedAt(now());
            runRepository.save(run);
        }

        private synchronized boolean limitReached() {
            return limitReached;
        }
    }
}
