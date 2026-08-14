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
                framedStory("Score: 95"),
                framedStory("Changes: fixed the ending"),
                framedStory("Revision Notes: fixed the ending"),
                framedStory("_A quiet story._"),
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
        ArrayDeque<String> outputs = successfulOutputs("FINAL_DECISION: PASS");
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
        assertEquals("Scene 1: A Funny Story\n\nA funny final story.", persisted.getFinalStory());
        assertNotNull(created.runId());
    }

    @Test
    void appendsRuntimeContractAndPassesOnlyExtractedStoryToReviewers() {
        ArrayDeque<String> outputs = successfulOutputs("FINAL_DECISION: PASS");
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

        String reviewInput = inputJsonValues.get(6);
        assertTrue(reviewInput.contains("A funny final story."));
        assertFalse(reviewInput.contains("STORY_TEXT_BEGIN"));
        assertTrue(reviewInput.contains("直接从 candidateStory 核对目标词"));
        assertFalse(reviewInput.contains("位置清单"));

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
                        return result("STORY_TEXT_BEGIN\nScene 1: A Story\n\nA story.\nSTORY_TEXT_END", 10);
                    }
                    if (systemPrompt.contains("quality-decider")) return result("ACTION: PASS", 10);
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
    void repeatsReviewRoundAfterTargetedRevisionUntilPass() {
        ArrayDeque<String> outputs = successfulOutputs("ACTION: REVISE\nTARGET_NODE: targeted-reviser");
        outputs.add("STORY_TEXT_BEGIN\nScene 1: A Better Story\n\nA better story.\nSTORY_TEXT_END");
        outputs.add("fun review 2");
        outputs.add("language review 2");
        outputs.add("continuity review 2");
        outputs.add("score 2");
        outputs.add("FINAL_DECISION: PASS");
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
        assertEquals("Scene 1: A Better Story\n\nA better story.", persisted.getFinalStory());
        String reviserSystemPrompt = systemPrompts.stream()
                .filter(prompt -> prompt.contains("System prompt for targeted-reviser"))
                .findFirst().orElseThrow();
        assertTrue(reviserSystemPrompt.contains("STORY_TEXT_BEGIN"));
        assertTrue(reviserSystemPrompt.contains("禁止 Markdown"));
    }

    @Test
    void failsRunAndPreservesRawOutputWhenWriterBreaksStoryContract() {
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch", "blueprint",
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
        ArrayDeque<String> outputs = successfulOutputs("ACTION: REVISE\nTARGET_NODE: targeted-reviser");
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
        assertEquals("Scene 1: A Funny Story\n\nA funny final story.", persisted.getFinalStory());
    }

    @Test
    void keepsExtractedStoryWhenWriterCallReachesTokenLimit() {
        when(agentService.getFlow()).thenReturn(flow(3, 1_000));
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> {
                    String systemPrompt = call.getArgument(1);
                    if (systemPrompt.contains("story-writer")) {
                        return result(framedStory("The story is ready."), 1_000);
                    }
                    return result("short", 1);
                });

        service.createRun(new StartRunRequest(
                List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("LIMIT_REACHED", persisted.getStatus());
        assertEquals("Scene 1: A Story\n\nThe story is ready.", persisted.getFinalStory());
        assertEquals(1_005, persisted.getTotalTokens());
    }

    @Test
    void routesRewriteDecisionBackToWriterWithinBudget() {
        ArrayDeque<String> outputs = successfulOutputs("After reviewing the evidence, the only action is REWRITE.");
        outputs.add("STORY_TEXT_BEGIN\nScene 1: A Rewritten Story\n\nA rewritten story.\nSTORY_TEXT_END");
        outputs.add("fun review 2");
        outputs.add("language review 2");
        outputs.add("continuity review 2");
        outputs.add("score 2");
        outputs.add("FINAL_DECISION: PASS");
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenAnswer(call -> result(outputs.removeFirst(), 50));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        ArgumentCaptor<StoryRunStep> steps = ArgumentCaptor.forClass(StoryRunStep.class);
        verify(stepRepository, org.mockito.Mockito.times(17)).save(steps.capture());
        assertEquals(2, steps.getAllValues().stream()
                .filter(step -> "story-writer".equals(step.getAgentKey())).count());
        assertEquals(0, steps.getAllValues().stream()
                .filter(step -> "targeted-reviser".equals(step.getAgentKey())).count());
        assertEquals("Scene 1: A Rewritten Story\n\nA rewritten story.", persisted.getFinalStory());
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
        when(agentService.getFlow()).thenReturn(flow(3, 500));
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenReturn(result("x".repeat(400), 0));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("LIMIT_REACHED", persisted.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(persisted.getTotalTokens() > 0);
        org.junit.jupiter.api.Assertions.assertTrue(persisted.getTotalTokens() <= 500);
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

    private ArrayDeque<String> successfulOutputs(String decision) {
        return new ArrayDeque<>(List.of(
                "word plan", "humor pitch", "adventure pitch", "wonder pitch", "blueprint",
                "STORY_TEXT_BEGIN\nScene 1: A Funny Story\n\nA funny final story.\nSTORY_TEXT_END", "fun review",
                "language review", "continuity review", "score", decision));
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
