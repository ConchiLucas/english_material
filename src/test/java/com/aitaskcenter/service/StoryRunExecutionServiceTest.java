package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.AiProviderConfigItem;
import com.aitaskcenter.dto.StoryAgentDtos.AgentView;
import com.aitaskcenter.dto.StoryAgentDtos.BudgetView;
import com.aitaskcenter.dto.StoryAgentDtos.FlowView;
import com.aitaskcenter.dto.StoryAgentDtos.StageView;
import com.aitaskcenter.dto.StoryRunDtos.StartRunRequest;
import com.aitaskcenter.dto.StoryRunDtos.StoryWord;
import com.aitaskcenter.model.StoryRun;
import com.aitaskcenter.model.StoryRunStep;
import com.aitaskcenter.repository.StoryRunRepository;
import com.aitaskcenter.repository.StoryRunStepRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

class StoryRunExecutionServiceTest {
    private StoryRunRepository runRepository;
    private StoryRunStepRepository stepRepository;
    private StoryAgentService agentService;
    private AiConfigService aiConfigService;
    private AiTextGenerationService generationService;
    private StoryWordSourceService wordSourceService;
    private StoryRunExecutionService service;
    private StoryRun persisted;

    @BeforeEach
    void setUp() {
        runRepository = mock(StoryRunRepository.class);
        stepRepository = mock(StoryRunStepRepository.class);
        agentService = mock(StoryAgentService.class);
        aiConfigService = mock(AiConfigService.class);
        generationService = mock(AiTextGenerationService.class);
        wordSourceService = mock(StoryWordSourceService.class);
        when(wordSourceService.normalizeManualWords(any())).thenAnswer(call -> call.getArgument(0));
        when(runRepository.save(any())).thenAnswer(call -> {
            persisted = call.getArgument(0);
            return persisted;
        });
        when(runRepository.findByRunId(anyString())).thenAnswer(call -> Optional.ofNullable(persisted));
        when(stepRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(agentService.getFlow()).thenReturn(flow(3, 120_000));
        AiProviderConfigItem provider = new AiProviderConfigItem();
        provider.setId("provider-1");
        provider.setModel("gemini-test");
        provider.setEnabled(true);
        when(aiConfigService.getProviderForExecution("provider-1")).thenReturn(provider);
        service = new StoryRunExecutionService(
                runRepository, stepRepository, agentService, aiConfigService, generationService,
                wordSourceService, new ObjectMapper(), new SyncTaskExecutor(), new SyncTaskExecutor());
    }

    @Test
    void rejectsDecisionIssuesThatDoNotNameAReplacementSentence() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> StoryRunExecutionService.parseDecision("""
                        ACTION: REVISE
                        BLOCKING: NONE
                        ISSUES_JSON_BEGIN
                        [{"scene":1,"quote":"A funny green book.","type":"FUN","instruction":"Add a hook","protect":false}]
                        ISSUES_JSON_END
                        """));
        assertEquals("质量决策问题清单无效", error.getMessage());
    }

    @Test
    void ignoresDecisionWhenReplaceWithDropsTheVisibleAction() {
        StoryRunExecutionService.Decision happy = StoryRunExecutionService.parseDecision("""
                ACTION: REWRITE
                BLOCKING: GRADE
                ISSUES_JSON_BEGIN
                [{"scene":1,"quote":"He rests his head on his arms.","type":"GRADE","instruction":"简化超纲短语","replaceWith":"Leo is happy.","protect":false}]
                ISSUES_JSON_END
                """);
        assertEquals("REWRITE", happy.action());
        assertEquals("[]", happy.issuesJson());

        StoryRunExecutionService.Decision locative = StoryRunExecutionService.parseDecision("""
                ACTION: REVISE
                BLOCKING: GRADE
                ISSUES_JSON_BEGIN
                [{"scene":1,"quote":"The cat puts a paw on the pages.","type":"GRADE","instruction":"去掉超纲词","replaceWith":"The cat is on the pages.","protect":false}]
                ISSUES_JSON_END
                """);
        assertEquals("[]", locative.issuesJson());
    }

    @Test
    void acceptsDecisionWhenReplaceWithKeepsTheVisibleAction() {
        StoryRunExecutionService.Decision decision = StoryRunExecutionService.parseDecision("""
                ACTION: REVISE
                BLOCKING: GRADE
                ISSUES_JSON_BEGIN
                [{"scene":1,"quote":"The cat puts a paw on the pages.","type":"GRADE","instruction":"把 paw 换成 hand","replaceWith":"The cat puts a hand on the book.","protect":false}]
                ISSUES_JSON_END
                """);
        assertEquals("REVISE", decision.action());
    }

    @Test
    void ignoresDecisionWhenFunReplaceWithOnlyAddsAnEmptyToneWord() {
        StoryRunExecutionService.Decision decision = StoryRunExecutionService.parseDecision("""
                ACTION: REVISE
                BLOCKING: NONE
                ISSUES_JSON_BEGIN
                [{"scene":1,"quote":"He opens the book with his two hands.","type":"FUN","instruction":"增加开场感染力","replaceWith":"He happily opens the book.","protect":false}]
                ISSUES_JSON_END
                """);
        assertEquals("[]", decision.issuesJson());
    }

    @Test
    void ignoresDecisionWhenGradeReplaceWithWeakensAPictureVerb() {
        StoryRunExecutionService.Decision decision = StoryRunExecutionService.parseDecision("""
                ACTION: REVISE
                BLOCKING: GRADE
                ISSUES_JSON_BEGIN
                [{"scene":1,"quote":"It stretches its legs.","type":"GRADE","instruction":"换成更简单的词","replaceWith":"It moves its legs.","protect":false}]
                ISSUES_JSON_END
                """);
        assertEquals("[]", decision.issuesJson());
    }

    @Test
    void acceptsFunReplaceWithThatSwapsInAVisibleConflict() {
        StoryRunExecutionService.Decision decision = StoryRunExecutionService.parseDecision("""
                ACTION: REVISE
                BLOCKING: NONE
                ISSUES_JSON_BEGIN
                [{"scene":1,"quote":"Leo sits at a big desk.","type":"FUN","instruction":"第一拍改成可见冲突","replaceWith":"A cat jumps on the red book.","protect":false}]
                ISSUES_JSON_END
                """);
        assertEquals("REVISE", decision.action());
    }

    @Test
    void acceptsGradeReplaceWithThatSwapsOnePictureVerbForAnother() {
        StoryRunExecutionService.Decision decision = StoryRunExecutionService.parseDecision("""
                ACTION: REVISE
                BLOCKING: GRADE
                ISSUES_JSON_BEGIN
                [{"scene":1,"quote":"He taps the red book with a soft paw.","type":"GRADE","instruction":"去掉 soft 和 paw","replaceWith":"He hits the red book with a hand.","protect":false}]
                ISSUES_JSON_END
                """);
        assertEquals("REVISE", decision.action());
        assertTrue(decision.issuesJson().contains("hits the red book"));
    }

    @Test
    void scanTargetWordsCountsInflectionsAndRejectsEmbeddedSubstrings() {
        String story = "The cats sit. A catalog is on the desk. The cat is jumping.";
        List<StoryRunExecutionService.WordUsage> usage = StoryRunExecutionService.scanTargetWords(
                List.of(new StoryWord("cat", "猫"), new StoryWord("book", "书")), story);
        assertEquals("cat", usage.get(0).word());
        assertTrue(usage.get(0).covered());
        assertTrue(usage.get(0).count() >= 2);
        assertTrue(usage.get(0).formsFound().contains("cat"));
        assertTrue(usage.get(0).formsFound().contains("cats"));
        assertEquals("book", usage.get(1).word());
        assertFalse(usage.get(1).covered());
        assertEquals(0, usage.get(1).count());
    }

    @Test
    void extractsPlainEnglishMultiSceneStory() {
        String output = """
                STORY_TEXT_BEGIN
                Scene 1: The Blue Bag

                Ben opens his blue bag.

                Scene 2: The Red Juice

                The red juice spills.
                STORY_TEXT_END
                """;

        assertEquals("""
                Scene 1: The Blue Bag

                Ben opens his blue bag.

                Scene 2: The Red Juice

                The red juice spills.
                """.strip(), StoryRunExecutionService.extractStory(output));
    }

    @Test
    void extractsUniqueCleanStoryBlockWhileLeavingTrailingAuditInRawOutput() {
        String output = """
                STORY_TEXT_BEGIN
                Scene 1: A Better Story

                A better story.
                STORY_TEXT_END

                ### Target Words
                1. book

                ### Revision Notes
                Fixed the ending.
                """;

        assertEquals(
                "Scene 1: A Better Story\n\nA better story.",
                StoryRunExecutionService.extractStory(output));
    }

    @Test
    void rejectsUnframedOrNonPlainStoryOutput() {
        for (String invalid : List.of(
                "Here is the story:\nScene 1: A Story",
                "STORY_TEXT_BEGIN\n**Scene 1: A Story**\nSTORY_TEXT_END",
                "STORY_TEXT_BEGIN\n### Scene 1: A Story\nSTORY_TEXT_END",
                "STORY_TEXT_BEGIN\n故事说明\nScene 1: A Story\nSTORY_TEXT_END",
                "STORY_TEXT_BEGIN\nOnce upon a time.\nSTORY_TEXT_END",
                "STORY_TEXT_BEGIN\nTarget Words Checklist\nSTORY_TEXT_END")) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> StoryRunExecutionService.extractStory(invalid));
            assertEquals("故事输出格式错误：只允许纯英文场景标题和故事正文", error.getMessage());
        }
    }

    @Test
    void rejectsAmbiguousMarkersAndAuditContentInsideOtherwiseValidStories() {
        for (String invalid : List.of(
                "STORY_TEXT_BEGINfoo\nScene 1: A Story\n\nA story.\nSTORY_TEXT_END",
                "STORY_TEXT_BEGIN\nScene 1: A Story\n\nSTORY_TEXT_BEGIN\nA story.\nSTORY_TEXT_END",
                "STORY_TEXT_BEGIN\nScene 1: A Story\n\nA story.\nSTORY_TEXT_END\nSTORY_TEXT_END",
                framedStory("Target Words: book, green"),
                framedStory("Target Words\nbook, green"),
                framedStory("Score: 95"),
                framedStory("Rating: 9/10"),
                framedStory("Rating\n9/10"),
                framedStory("Scoring Report: 95"),
                framedStory("Changes: fixed the ending"),
                framedStory("Revision Notes: fixed the ending"),
                framedStory("Review Notes: check the ending"),
                framedStory("Review Notes\ncheck the ending"),
                framedStory("_A quiet story._"),
                framedStory("~~A deleted story.~~"),
                framedStory("~~~\nA fenced report.\n~~~"),
                framedStory("Read [the story](https://example.com)."),
                framedStory("> A quoted report."),
                framedStory("1. First change"),
                framedStory("Use `book` here."),
                framedStory("Word | Meaning"),
                framedStory("Word | Meaning | Scene"),
                framedStory("The child says Привет."))) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> StoryRunExecutionService.extractStory(invalid));
            assertEquals("故事输出格式错误：只允许纯英文场景标题和故事正文", error.getMessage());
        }
    }

    @Test
    void acceptsPlainEnglishPunctuationAndCurrency() {
        assertEquals(
                "Scene 1: The Toy Shop\n\nThe toy costs $5. \"Yes!\" Ben says.",
                StoryRunExecutionService.extractStory(framedStory(
                        "The toy costs $5. \"Yes!\" Ben says.")
                        .replace("Scene 1: A Story", "Scene 1: The Toy Shop")));
    }

    @Test
    void runsFixedAgentsInInvocationOrderAndStoresFinalStory() {
        ArrayDeque<String> outputs = successfulOutputs(passDecision());
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 100));

        var created = service.createRun(new StartRunRequest(
                List.of(new StoryWord("book", "书"), new StoryWord("green", "绿色")), "三年级上册"));

        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.times(11)).save(steps.capture());
        assertEquals(List.of(
                "vocabulary-planner", "pitch-humor", "pitch-adventure", "pitch-wonder",
                "story-director", "story-writer", "review-fun", "review-language",
                "review-continuity", "story-scorer", "quality-decider"),
                steps.getAllValues().stream().map(StoryRunStep::getAgentKey).toList());
        assertEquals("COMPLETED", persisted.getStatus());
        assertEquals("Scene 1: A Funny Story\n\nA funny green book.", persisted.getFinalStory());
        ArgumentCaptor<String> systemPrompts = ArgumentCaptor.forClass(String.class);
        verify(generationService, org.mockito.Mockito.atLeast(11))
                .generateWithUsage(any(), systemPrompts.capture(), anyString(), any(Double.class), anyInt());
        assertTrue(systemPrompts.getAllValues().stream()
                .anyMatch(prompt -> prompt.contains("[运行时提案协议]") && prompt.contains("8 到 16")));
        assertTrue(systemPrompts.getAllValues().stream()
                .anyMatch(prompt -> prompt.contains("[运行时蓝图协议]") && prompt.contains("BEAT_COUNTS")));
        assertTrue(systemPrompts.getAllValues().stream()
                .anyMatch(prompt -> prompt.contains("[运行时最终输出协议") && prompt.contains("8 到 16")));
        assertTrue(systemPrompts.getAllValues().stream()
                .anyMatch(prompt -> prompt.contains("[运行时评分协议]") && prompt.contains("高中")));
        assertNotNull(created.runId());
    }

    @Test
    void rejectsSerializedWordSnapshotsOverSixtyFourKiBBeforePersistence() throws Exception {
        ObjectMapper oversizedMapper = mock(ObjectMapper.class);
        when(oversizedMapper.writeValueAsString(any())).thenReturn("x".repeat(64 * 1024 + 1));
        StoryRunExecutionService bounded = new StoryRunExecutionService(
                runRepository, stepRepository, agentService, aiConfigService, generationService,
                wordSourceService, oversizedMapper, command -> { }, new SyncTaskExecutor());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> bounded.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册")));

        assertEquals("单词快照超过最大长度", error.getMessage());
        verify(runRepository, never()).save(any());
    }

    @Test
    void acceptsSerializedWordSnapshotAtExactSixtyFourKiBBoundary() throws Exception {
        ObjectMapper boundaryMapper = mock(ObjectMapper.class);
        when(boundaryMapper.writeValueAsString(any())).thenReturn("[]" + " ".repeat(64 * 1024 - 2));
        StoryRunExecutionService bounded = new StoryRunExecutionService(
                runRepository, stepRepository, agentService, aiConfigService, generationService,
                wordSourceService, boundaryMapper, command -> { }, new SyncTaskExecutor());

        bounded.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        verify(runRepository).save(any());
        assertEquals(64 * 1024, persisted.getInputWordsJson().length());
    }

    @Test
    void appendsRuntimeContractAndPassesOnlyExtractedStoryToReviewers() {
        ArrayDeque<String> outputs = successfulOutputs(passDecision());
        List<String> systemPrompts = new ArrayList<>();
        List<String> inputJsonValues = new ArrayList<>();
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> {
                    systemPrompts.add(call.getArgument(1));
                    inputJsonValues.add(call.getArgument(2));
                    return result(outputs.removeFirst(), 100);
                });

        service.createRun(new StartRunRequest(
                List.of(new StoryWord("book", "书")), "三年级上册"));

        String writerSystemPrompt = systemPrompts.stream()
                .filter(prompt -> prompt.contains("System prompt for story-writer"))
                .findFirst().orElseThrow();
        assertTrue(writerSystemPrompt.contains("STORY_TEXT_BEGIN"));
        assertTrue(writerSystemPrompt.contains("STORY_TEXT_END"));
        assertTrue(writerSystemPrompt.contains("禁止 Markdown"));
        assertTrue(writerSystemPrompt.contains("禁止使役结构"));

        String funInput = inputJsonValues.get(6);
        String languageInput = inputJsonValues.get(7);
        String continuityInput = inputJsonValues.get(8);
        assertTrue(funInput.contains("A funny green book."));
        assertFalse(funInput.contains("STORY_TEXT_BEGIN"));
        assertFalse(funInput.contains("wordUsage"));
        assertFalse(funInput.contains("storyBlueprint"));
        assertTrue(languageInput.contains("wordUsage"));
        assertTrue(languageInput.contains("\"word\":\"book\""));
        assertFalse(languageInput.contains("直接从 candidateStory"));
        assertTrue(continuityInput.contains("storyBlueprint"));
        assertFalse(continuityInput.contains("wordUsage"));

        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.times(11)).save(steps.capture());
        StoryRunStep writerStep = steps.getAllValues().stream()
                .filter(step -> "story-writer".equals(step.getAgentKey()))
                .findFirst().orElseThrow();
        assertTrue(writerStep.getOutputText().contains("STORY_TEXT_BEGIN"));
        assertEquals("COMPLETED", writerStep.getStatus());
    }

    @Test
    void startsAllPitchAgentsBeforeWaitingForTheirResults() throws Exception {
        CountDownLatch pitchesStarted = new CountDownLatch(3);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        StoryRunExecutionService parallelService = new StoryRunExecutionService(
                runRepository, stepRepository, agentService, aiConfigService, generationService,
                wordSourceService, new ObjectMapper(), new SyncTaskExecutor(), pool::execute);
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> {
                    String systemPrompt = call.getArgument(1);
                    if (systemPrompt.contains("pitch-")) {
                        pitchesStarted.countDown();
                        assertTrue(pitchesStarted.await(1, TimeUnit.SECONDS));
                        return result("pitch", 10);
                    }
                    if (systemPrompt.contains("story-writer")) {
                        return result("STORY_TEXT_BEGIN\nScene 1: A Story\n\nA book.\nSTORY_TEXT_END", 10);
                    }
                    if (systemPrompt.contains("quality-decider")) return result(passDecision(), 10);
                    if (systemPrompt.contains("story-director")) return result(directorBlueprint(), 10);
                    if (systemPrompt.contains("story-scorer")) return result(scoreBlock(4, 4, 4, 4), 10);
                    return result("ok", 10);
                });
        try {
            parallelService.createRun(new StartRunRequest(
                    List.of(new StoryWord("book", "书")), "三年级上册"));
            assertEquals("COMPLETED", persisted.getStatus());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void completesAfterOneRevisionWhenScoresStayAtThePassLine() {
        ArrayDeque<String> outputs = successfulOutputs(reviseDecision());
        outputs.add("STORY_TEXT_BEGIN\nScene 1: A Better Story\n\nA better book.\nSTORY_TEXT_END");
        outputs.add("fun review 2");
        outputs.add("language review 2");
        outputs.add("continuity review 2");
        outputs.add(scoreBlock(4, 4, 4, 4));
        outputs.add(reviseDecision());
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.times(17)).save(steps.capture());
        assertEquals("COMPLETED", persisted.getStatus());
        assertEquals("Scene 1: A Better Story\n\nA better book.", persisted.getFinalStory());
        assertEquals(1, steps.getAllValues().stream()
                .filter(step -> "targeted-reviser".equals(step.getAgentKey())).count());
        assertEquals(2, steps.getAllValues().stream()
                .filter(step -> "quality-decider".equals(step.getAgentKey())).count());
        assertEquals(1, steps.getAllValues().stream()
                .filter(step -> "story-writer".equals(step.getAgentKey())).count());
    }

    @Test
    void repeatsReviewRoundAfterTargetedRevisionUntilPass() {
        ArrayDeque<String> outputs = successfulOutputs(reviseDecision());
        String rawRevision = "STORY_TEXT_BEGIN\nScene 1: A Better Story\n\nA better book.\n"
                + "STORY_TEXT_END\n\n### Revision Notes\nFixed the ending.";
        outputs.add(rawRevision);
        outputs.add("fun review 2");
        outputs.add("language review 2");
        outputs.add("continuity review 2");
        outputs.add(scoreBlock(4, 4, 4, 4));
        outputs.add(passDecision());
        List<String> systemPrompts = new ArrayList<>();
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> {
                    systemPrompts.add(call.getArgument(1));
                    return result(outputs.removeFirst(), 50);
                });

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.times(17)).save(steps.capture());
        assertEquals(2, steps.getAllValues().get(12).getQualityRound());
        assertEquals("Scene 1: A Better Story\n\nA better book.", persisted.getFinalStory());
        assertEquals(rawRevision, steps.getAllValues().get(11).getOutputText());
        String reviserSystemPrompt = systemPrompts.stream()
                .filter(prompt -> prompt.contains("System prompt for targeted-reviser"))
                .findFirst().orElseThrow();
        assertTrue(reviserSystemPrompt.contains("STORY_TEXT_BEGIN"));
        assertTrue(reviserSystemPrompt.contains("禁止 Markdown"));
    }

    @Test
    void failsRunAndPreservesRawOutputWhenWriterBreaksStoryContract() {
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch", directorBlueprint(),
                "Here is the report:\n### Story\n**Not plain**"));
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(
                List.of(new StoryWord("book", "书")), "三年级上册"));

        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.times(6)).save(steps.capture());
        StoryRunStep writerStep = steps.getAllValues().get(5);
        assertEquals("story-writer", writerStep.getAgentKey());
        assertEquals("FAILED", writerStep.getStatus());
        assertEquals("Here is the report:\n### Story\n**Not plain**", writerStep.getOutputText());
        assertEquals("FAILED", persisted.getStatus());
        assertNull(persisted.getFinalStory());
        assertEquals("故事输出格式错误：只允许纯英文场景标题和故事正文", persisted.getErrorMessage());
        assertEquals(300, persisted.getTotalTokens());
        assertEquals(50, writerStep.getTotalTokens());
    }

    @Test
    void failsRevisionAndKeepsLastValidStoryWhenReviserBreaksContract() {
        ArrayDeque<String> outputs = successfulOutputs(reviseDecision());
        outputs.add("Here is the revision report:\nChanges: rewrote everything");
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(
                List.of(new StoryWord("book", "书")), "三年级上册"));

        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.times(12)).save(steps.capture());
        StoryRunStep reviserStep = steps.getAllValues().get(11);
        assertEquals("targeted-reviser", reviserStep.getAgentKey());
        assertEquals("FAILED", reviserStep.getStatus());
        assertEquals("Here is the revision report:\nChanges: rewrote everything", reviserStep.getOutputText());
        assertEquals(50, reviserStep.getTotalTokens());
        assertEquals(600, persisted.getTotalTokens());
        assertEquals("FAILED", persisted.getStatus());
        assertEquals("Scene 1: A Funny Story\n\nA funny green book.", persisted.getFinalStory());
    }

    @Test
    void keepsExtractedStoryWhenWriterCallReachesTokenLimit() {
        when(agentService.getFlow()).thenReturn(flow(3, 5_000));
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> {
                    String systemPrompt = call.getArgument(1);
                    if (systemPrompt.contains("story-writer")) {
                        return result(framedStory("The story is ready."), 4_800);
                    }
                    if (systemPrompt.contains("story-director")) {
                        return result(directorBlueprint(), 1);
                    }
                    return result("short", 1);
                });

        service.createRun(new StartRunRequest(
                List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("LIMIT_REACHED", persisted.getStatus());
        assertEquals("Scene 1: A Story\n\nThe story is ready.", persisted.getFinalStory());
        assertTrue(persisted.getTotalTokens() >= 4_800);
        assertTrue(persisted.getTotalTokens() <= 5_000);
    }

    @Test
    void acceptsBlankTargetGradeAndSkipsOverGradeChecks() {
        ArrayDeque<String> outputs = successfulOutputs(passDecision());
        List<String> systemPrompts = new ArrayList<>();
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> {
                    systemPrompts.add(call.getArgument(1));
                    return result(outputs.removeFirst(), 50);
                });

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), ""));

        assertEquals("COMPLETED", persisted.getStatus());
        assertEquals("", persisted.getTargetGrade());
        assertTrue(systemPrompts.stream().anyMatch(prompt -> prompt.contains("不考虑超纲")));
        assertTrue(StoryRunExecutionService.isGradeOpen(""));
        assertTrue(StoryRunExecutionService.isGradeOpen("初中"));
        assertFalse(StoryRunExecutionService.isGradeOpen("小学"));
        assertFalse(StoryRunExecutionService.isGradeOpen("三年级上册"));
        assertEquals("未指定（不考虑超纲）", StoryRunExecutionService.gradeContext(""));
    }

    @Test
    void completesWhenGradeIsTwoIfLanguageAlreadyPasses() {
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch", directorBlueprint(),
                "STORY_TEXT_BEGIN\nScene 1: A Funny Story\n\nA funny green book.\nSTORY_TEXT_END",
                "fun review", "language review", "continuity review", passDecision()));
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> {
                    String systemPrompt = call.getArgument(1);
                    if (systemPrompt.contains("story-scorer")) {
                        return result(scoreBlock(4, 4, 4, 2), 50);
                    }
                    return result(outputs.removeFirst(), 50);
                });

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("COMPLETED", persisted.getStatus());
        assertEquals("Scene 1: A Funny Story\n\nA funny green book.", persisted.getFinalStory());
        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.times(11)).save(steps.capture());
        assertEquals(0, steps.getAllValues().stream()
                .filter(step -> "targeted-reviser".equals(step.getAgentKey())).count());
        assertEquals(1, steps.getAllValues().stream()
                .filter(step -> "story-writer".equals(step.getAgentKey())).count());
    }

    @Test
    void revisesFirstWhenGradeIsBlockingInsteadOfRewriting() {
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch", directorBlueprint(),
                "STORY_TEXT_BEGIN\nScene 1: A Funny Story\n\nA funny green book.\nSTORY_TEXT_END",
                "fun review", "language review", "continuity review", scoreBlock(4, 4, 4, 1),
                rewriteDecision()));
        outputs.add("STORY_TEXT_BEGIN\nScene 1: A Revised Story\n\nBen sees a book. The book is green.\nSTORY_TEXT_END");
        outputs.add("fun review 2");
        outputs.add("language review 2");
        outputs.add("continuity review 2");
        outputs.add(scoreBlock(4, 4, 4, 4));
        outputs.add(passDecision());
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.times(17)).save(steps.capture());
        assertEquals(1, steps.getAllValues().stream()
                .filter(step -> "story-writer".equals(step.getAgentKey())).count());
        assertEquals(1, steps.getAllValues().stream()
                .filter(step -> "targeted-reviser".equals(step.getAgentKey())).count());
        assertEquals("Scene 1: A Revised Story\n\nBen sees a book. The book is green.", persisted.getFinalStory());
    }

    @Test
    void rewritesAfterARevisionStillLeavesGradeBlocking() {
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch", directorBlueprint(),
                "STORY_TEXT_BEGIN\nScene 1: A Funny Story\n\nA funny green book.\nSTORY_TEXT_END",
                "fun review", "language review", "continuity review", scoreBlock(4, 4, 4, 1),
                rewriteDecision()));
        outputs.add("STORY_TEXT_BEGIN\nScene 1: A Revised Story\n\nBen sees a book. The book is green.\nSTORY_TEXT_END");
        outputs.add("fun review 2");
        outputs.add("language review 2");
        outputs.add("continuity review 2");
        outputs.add(scoreBlock(4, 4, 4, 1));
        outputs.add(rewriteDecision());
        outputs.add("STORY_TEXT_BEGIN\nScene 1: A Rewritten Story\n\nBen sees a book.\nSTORY_TEXT_END");
        outputs.add("fun review 3");
        outputs.add("language review 3");
        outputs.add("continuity review 3");
        outputs.add(scoreBlock(4, 4, 4, 4));
        outputs.add(passDecision());
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.atLeast(18)).save(steps.capture());
        assertEquals(1, steps.getAllValues().stream()
                .filter(step -> "targeted-reviser".equals(step.getAgentKey())).count());
        assertEquals(2, steps.getAllValues().stream()
                .filter(step -> "story-writer".equals(step.getAgentKey())).count());
        assertEquals("Scene 1: A Rewritten Story\n\nBen sees a book.", persisted.getFinalStory());
    }

    @Test
    void allowsOneMoreRevisionWhenContinuityStaysBelowPassLine() {
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch", directorBlueprint(),
                "STORY_TEXT_BEGIN\nScene 1: A Funny Story\n\nA funny green book.\nSTORY_TEXT_END",
                "fun review", "language review", "continuity review", scoreBlock(4, 4, 2, 4),
                reviseDecision()));
        outputs.add("STORY_TEXT_BEGIN\nScene 1: A Better Story\n\nA better book.\nSTORY_TEXT_END");
        outputs.add("fun review 2");
        outputs.add("language review 2");
        outputs.add("continuity review 2");
        outputs.add(scoreBlock(4, 4, 2, 4));
        outputs.add(reviseDecision());
        outputs.add("STORY_TEXT_BEGIN\nScene 1: A Continuous Story\n\nBen opens a green book.\nSTORY_TEXT_END");
        outputs.add("fun review 3");
        outputs.add("language review 3");
        outputs.add("continuity review 3");
        outputs.add(scoreBlock(4, 4, 2, 4));
        outputs.add(reviseDecision());
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.atLeast(22)).save(steps.capture());
        assertEquals(2, steps.getAllValues().stream()
                .filter(step -> "targeted-reviser".equals(step.getAgentKey())).count());
        assertEquals(1, steps.getAllValues().stream()
                .filter(step -> "story-writer".equals(step.getAgentKey())).count());
        assertEquals("COMPLETED", persisted.getStatus());
        assertEquals("Scene 1: A Continuous Story\n\nBen opens a green book.", persisted.getFinalStory());
    }

    @Test
    void discardsRevisionWhenFunOrContinuityDropsEvenIfTotalRises() {
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch", directorBlueprint(),
                "STORY_TEXT_BEGIN\nScene 1: A Funny Story\n\nA funny green book.\nSTORY_TEXT_END",
                "fun review", "language review", "continuity review", scoreBlock(3, 4, 4, 3),
                reviseDecision()));
        outputs.add("STORY_TEXT_BEGIN\nScene 1: A Worse Story\n\nBen happily opens a book.\nSTORY_TEXT_END");
        outputs.add("fun review 2");
        outputs.add("language review 2");
        outputs.add("continuity review 2");
        outputs.add(scoreBlock(2, 4, 4, 5));
        outputs.add(reviseDecision());
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("COMPLETED", persisted.getStatus());
        assertEquals("Scene 1: A Funny Story\n\nA funny green book.", persisted.getFinalStory());
        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.atLeast(17)).save(steps.capture());
        assertEquals(1, steps.getAllValues().stream()
                .filter(step -> "targeted-reviser".equals(step.getAgentKey())).count());
    }

    @Test
    void completesWithBestStoryWhenDeciderReplaceWithViolatesPolicy() {
        ArrayDeque<String> outputs = successfulOutputs("""
                ACTION: REVISE
                BLOCKING: GRADE
                ISSUES_JSON_BEGIN
                [{"scene":1,"quote":"It stretches its legs.","type":"GRADE","instruction":"换成更简单的词","replaceWith":"It moves its legs.","protect":false}]
                ISSUES_JSON_END
                """);
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("COMPLETED", persisted.getStatus());
        assertEquals("Scene 1: A Funny Story\n\nA funny green book.", persisted.getFinalStory());
        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.atLeast(11)).save(steps.capture());
        assertEquals("quality-decider", steps.getAllValues().get(10).getAgentKey());
        assertEquals("COMPLETED", steps.getAllValues().get(10).getStatus());
        assertEquals(0, steps.getAllValues().stream()
                .filter(step -> "targeted-reviser".equals(step.getAgentKey())).count());
    }

    @Test
    void failsWhenDeciderHedgesInsteadOfEmittingAnActionLine() {
        ArrayDeque<String> outputs = successfulOutputs("this cannot PASS; do not REVISE, REWRITE instead");
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("FAILED", persisted.getStatus());
        assertEquals("Scene 1: A Funny Story\n\nA funny green book.", persisted.getFinalStory());
        assertTrue(persisted.getErrorMessage().contains("ACTION"));
        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.times(11)).save(steps.capture());
        assertEquals(0, steps.getAllValues().stream()
                .filter(step -> "targeted-reviser".equals(step.getAgentKey())).count());
        assertEquals(1, steps.getAllValues().stream()
                .filter(step -> "story-writer".equals(step.getAgentKey())).count());
    }

    @Test
    void stopsWhenTokenBudgetIsReached() {
        when(agentService.getFlow()).thenReturn(flow(3, 50));
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenReturn(result("plan", 60));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("LIMIT_REACHED", persisted.getStatus());
        assertEquals(0, persisted.getTotalTokens());
        verify(generationService, never()).generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt());
    }

    @Test
    void estimatesUsageWhenProviderOmitsTokenCountsSoBudgetStillStops() {
        when(agentService.getFlow()).thenReturn(flow(3, 1_200));
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> {
                    String systemPrompt = call.getArgument(1);
                    if (systemPrompt.contains("story-director")) return result(directorBlueprint(), 0);
                    return result("x".repeat(400), 0);
                });

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("LIMIT_REACHED", persisted.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(persisted.getTotalTokens() > 0);
        org.junit.jupiter.api.Assertions.assertTrue(persisted.getTotalTokens() <= 1_200);
    }

    @Test
    void marksPersistedRunFailedWhenBoundedQueueRejectsSubmission() {
        StoryRunExecutionService rejecting = new StoryRunExecutionService(
                runRepository, stepRepository, agentService, aiConfigService, generationService,
                wordSourceService, new ObjectMapper(), command -> {
                    throw new TaskRejectedException("queue full");
                }, new SyncTaskExecutor());

        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> rejecting.createRun(new StartRunRequest(
                        List.of(new StoryWord("book", "书")), "三年级上册")));

        assertEquals("运行队列已满，请稍后重试", error.getMessage());
        assertEquals("FAILED", persisted.getStatus());
        assertNotNull(persisted.getFinishedAt());
    }

    @Test
    void redactsProviderKeyFromPersistedFailure() {
        AiProviderConfigItem provider = new AiProviderConfigItem();
        provider.setId("provider-1");
        provider.setModel("gemini-test");
        provider.setApiKey("local-secret-key");
        provider.setEnabled(true);
        when(aiConfigService.getProviderForExecution("provider-1")).thenReturn(provider);
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenThrow(new IllegalArgumentException("gateway echoed Bearer local-secret-key"));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        ArgumentCaptor<StoryRunStep> step = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository).save(step.capture());
        org.junit.jupiter.api.Assertions.assertFalse(step.getValue().getOutputText().contains("local-secret-key"));
        org.junit.jupiter.api.Assertions.assertFalse(persisted.getErrorMessage().contains("local-secret-key"));
    }

    @Test
    void refusesDisabledProviderBeforeGeneration() {
        AiProviderConfigItem provider = new AiProviderConfigItem();
        provider.setId("provider-1");
        provider.setModel("gemini-test");
        provider.setEnabled(false);
        when(aiConfigService.getProviderForExecution("provider-1")).thenReturn(provider);

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("FAILED", persisted.getStatus());
        verify(generationService, never()).generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt());
    }

    @Test
    void persistsFailedAgentAndMarksRunFailed() {
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenThrow(new IllegalArgumentException("provider unavailable"));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        ArgumentCaptor<StoryRunStep> step = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository).save(step.capture());
        assertEquals("FAILED", step.getValue().getStatus());
        assertEquals("FAILED", persisted.getStatus());
        assertEquals("provider unavailable", persisted.getErrorMessage());
    }

    @Test
    void revisesLowGradeOnTheLastQualityRoundInsteadOfRewriting() {
        when(agentService.getFlow()).thenReturn(flow(1, 120_000));
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch", directorBlueprint(),
                "STORY_TEXT_BEGIN\nScene 1: A Funny Story\n\nA funny green book.\nSTORY_TEXT_END",
                "fun review", "language review", "continuity review", scoreBlock(4, 4, 4, 2),
                reviseDecision(),
                "STORY_TEXT_BEGIN\nScene 1: A Revised Story\n\nBen opens a green book.\nSTORY_TEXT_END"));
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.atLeast(12)).save(steps.capture());
        assertEquals(1, steps.getAllValues().stream()
                .filter(step -> "story-writer".equals(step.getAgentKey())).count());
        assertEquals(1, steps.getAllValues().stream()
                .filter(step -> "targeted-reviser".equals(step.getAgentKey())).count());
        assertEquals("Scene 1: A Revised Story\n\nBen opens a green book.", persisted.getFinalStory());
    }

    @Test
    void upgradesPassToReviseWhenATargetWordIsMissing() {
        ArrayDeque<String> outputs = successfulOutputs(passDecision());
        outputs.add("STORY_TEXT_BEGIN\nScene 1: A Better Story\n\nA better book and a cat.\nSTORY_TEXT_END");
        outputs.add("fun review 2");
        outputs.add("language review 2");
        outputs.add("continuity review 2");
        outputs.add(scoreBlock(4, 4, 4, 4));
        outputs.add(passDecision());
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(
                List.of(new StoryWord("book", "书"), new StoryWord("cat", "猫")), "三年级上册"));

        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.atLeast(12)).save(steps.capture());
        assertEquals(1, steps.getAllValues().stream()
                .filter(step -> "targeted-reviser".equals(step.getAgentKey())).count());
    }

    @Test
    void downgradesRewriteToReviseWhenLanguageAndGradeAreNotBlocking() {
        ArrayDeque<String> outputs = successfulOutputs(rewriteDecision());
        outputs.add("STORY_TEXT_BEGIN\nScene 1: A Better Story\n\nA better book.\nSTORY_TEXT_END");
        outputs.add("fun review 2");
        outputs.add("language review 2");
        outputs.add("continuity review 2");
        outputs.add(scoreBlock(4, 4, 4, 4));
        outputs.add(passDecision());
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.atLeast(12)).save(steps.capture());
        assertEquals(1, steps.getAllValues().stream()
                .filter(step -> "story-writer".equals(step.getAgentKey())).count());
        assertEquals(1, steps.getAllValues().stream()
                .filter(step -> "targeted-reviser".equals(step.getAgentKey())).count());
        assertEquals("Scene 1: A Better Story\n\nA better book.", persisted.getFinalStory());
    }

    @Test
    void appliesRewriteOnTheLastQualityRoundBeforeStopping() {
        when(agentService.getFlow()).thenReturn(flow(1, 120_000));
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch", directorBlueprint(),
                "STORY_TEXT_BEGIN\nScene 1: A Funny Story\n\nA funny green book.\nSTORY_TEXT_END",
                "fun review", "language review", "continuity review", scoreBlock(4, 4, 4, 1),
                rewriteDecision()));
        outputs.add("STORY_TEXT_BEGIN\nScene 1: A Rewritten Story\n\nA rewritten book and a cat.\nSTORY_TEXT_END");
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(
                List.of(new StoryWord("book", "书"), new StoryWord("cat", "猫")), "三年级上册"));

        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.atLeast(12)).save(steps.capture());
        assertEquals(2, steps.getAllValues().stream()
                .filter(step -> "story-writer".equals(step.getAgentKey())).count());
        assertEquals("LIMIT_REACHED", persisted.getStatus());
        assertEquals("Scene 1: A Rewritten Story\n\nA rewritten book and a cat.", persisted.getFinalStory());
    }

    @Test
    void failsDirectorWhenSceneCountIsMissing() {
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch",
                """
                MAIN_PITCH: humor
                TAKE_FROM_HUMOR: 主提案本身
                TAKE_FROM_ADVENTURE: 一场安全追逐
                TAKE_FROM_WONDER: 一本会说话的书
                """));
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("FAILED", persisted.getStatus());
        assertTrue(persisted.getErrorMessage().contains("导演"));
        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.times(5)).save(steps.capture());
        assertEquals("FAILED", steps.getAllValues().get(4).getStatus());
    }

    @Test
    void failsDirectorWhenBeatCountsAreMissingOrOutOfRange() {
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch",
                """
                MAIN_PITCH: humor
                SCENE_COUNT: 1
                TAKE_FROM_HUMOR: 主提案本身
                TAKE_FROM_ADVENTURE: 一场安全追逐
                TAKE_FROM_WONDER: 一本会说话的书
                """));
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("FAILED", persisted.getStatus());
        assertTrue(persisted.getErrorMessage().contains("画面数"));
    }

    @Test
    void failsDirectorWhenASceneHasFewerThanEightBeats() {
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch",
                """
                MAIN_PITCH: humor
                SCENE_COUNT: 2
                BEAT_COUNTS: 16,1
                TAKE_FROM_HUMOR: 主提案本身
                TAKE_FROM_ADVENTURE: 一场安全追逐
                TAKE_FROM_WONDER: 一本会说话的书
                """));
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("FAILED", persisted.getStatus());
        assertTrue(persisted.getErrorMessage().contains("画面数"));
    }

    @Test
    void failsDirectorWhenAbsorptionLinesAreMissing() {
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch",
                "MAIN_PITCH: humor\nTAKE_FROM_HUMOR: 主提案本身\nTAKE_FROM_ADVENTURE: 追逐"));
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("FAILED", persisted.getStatus());
        assertTrue(persisted.getErrorMessage().contains("导演"));
        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.times(5)).save(steps.capture());
        assertEquals("FAILED", steps.getAllValues().get(4).getStatus());
    }

    @Test
    void failsScorerWhenScoreBlockIsMissing() {
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch", directorBlueprint(),
                "STORY_TEXT_BEGIN\nScene 1: A Funny Story\n\nA funny green book.\nSTORY_TEXT_END",
                "fun review", "language review", "continuity review", "no scores here"));
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("FAILED", persisted.getStatus());
        assertTrue(persisted.getErrorMessage().contains("SCORE"));
    }

    @Test
    void keepsHighestScoredCandidateWhenLaterActionCannotRun() {
        when(agentService.getFlow()).thenReturn(new FlowView(
                List.of(new StageView("stage", "stage", "", 1, flow(3, 120_000).stages().get(0).nodes())),
                new BudgetView(3, 0, 0, 1, 1, 1, 120_000, null)));
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch", directorBlueprint(),
                "STORY_TEXT_BEGIN\nScene 1: A Funny Story\n\nA funny green book.\nSTORY_TEXT_END",
                "fun review", "language review", "continuity review", scoreBlock(5, 4, 4, 3),
                rewriteDecision()));
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("LIMIT_REACHED", persisted.getStatus());
        assertEquals("Scene 1: A Funny Story\n\nA funny green book.", persisted.getFinalStory());
    }

    private ArrayDeque<String> successfulOutputs(String decision) {
        return new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch", directorBlueprint(),
                "STORY_TEXT_BEGIN\nScene 1: A Funny Story\n\nA funny green book.\nSTORY_TEXT_END", "fun review",
                "language review", "continuity review", scoreBlock(4, 4, 4, 4), decision));
    }

    private static String directorBlueprint() {
        return """
                MAIN_PITCH: humor
                SCENE_COUNT: 1
                BEAT_COUNTS: 10
                TAKE_FROM_HUMOR: 主提案本身
                TAKE_FROM_ADVENTURE: 一场安全追逐
                TAKE_FROM_WONDER: 一本会说话的书
                SetupRequired: a green book
                """;
    }

    private static String scoreBlock(int fun, int language, int continuity, int grade) {
        return "notes\nSCORE_BEGIN\nfun: " + fun + "\nlanguage: " + language
                + "\ncontinuity: " + continuity + "\ngrade: " + grade + "\nSCORE_END";
    }

    private static String passDecision() {
        return "ACTION: PASS\nBLOCKING: NONE\n";
    }

    private static String reviseDecision() {
        return """
                ACTION: REVISE
                BLOCKING: NONE
                ISSUES_JSON_BEGIN
                [{"scene":1,"quote":"A funny green book.","type":"FUN","instruction":"把开篇换成动作句","replaceWith":"Ben opens a green book.","protect":false}]
                ISSUES_JSON_END
                """;
    }

    private static String rewriteDecision() {
        return """
                ACTION: REWRITE
                BLOCKING: GRADE
                ISSUES_JSON_BEGIN
                [{"scene":1,"quote":"A funny green book.","type":"GRADE","instruction":"拆成更短的句子","replaceWith":"Ben sees a book. The book is green.","protect":false}]
                ISSUES_JSON_END
                """;
    }

    private static String framedStory(String body) {
        return "STORY_TEXT_BEGIN\nScene 1: A Story\n\n" + body + "\nSTORY_TEXT_END";
    }

    private AiTextGenerationService.GenerationResult result(String text, long tokens) {
        return new AiTextGenerationService.GenerationResult(text, tokens / 2, tokens / 2, tokens);
    }

    private FlowView flow(int rounds, int maxTokens) {
        List<AgentView> agents = new ArrayList<>();
        for (String key : List.of(
                "vocabulary-planner", "pitch-humor", "pitch-adventure", "pitch-wonder",
                "story-director", "story-writer", "review-fun", "review-language",
                "review-continuity", "story-scorer", "quality-decider", "targeted-reviser")) {
            agents.add(new AgentView(
                    key, key, "AGENT", "TEST", "stage", 1, null, "", List.of(), List.of(), List.of(),
                    "System prompt for " + key, "provider-1", 0.2, true, 1, null, true));
        }
        BudgetView budget = new BudgetView(rounds, 2, 2, 1, 1, 1, maxTokens, null);
        return new FlowView(List.of(new StageView("stage", "stage", "", 1, agents)), budget);
    }
}
