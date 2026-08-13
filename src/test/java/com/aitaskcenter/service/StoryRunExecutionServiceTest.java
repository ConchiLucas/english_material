package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;

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
                wordSourceService, new ObjectMapper(), new SyncTaskExecutor());
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
        assertEquals("A funny final story", persisted.getFinalStory());
        assertNotNull(created.runId());
    }

    @Test
    void repeatsReviewRoundAfterTargetedRevisionUntilPass() {
        ArrayDeque<String> outputs = successfulOutputs("ACTION: REVISE\nTARGET_NODE: targeted-reviser");
        outputs.add("STORY_TEXT_BEGIN\nA better story\nSTORY_TEXT_END");
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
        assertEquals(2, steps.getAllValues().get(12).getQualityRound());
        assertEquals("A better story", persisted.getFinalStory());
    }

    @Test
    void routesRewriteDecisionBackToWriterWithinBudget() {
        ArrayDeque<String> outputs = successfulOutputs("ACTION: REWRITE\nTARGET_NODE: story-writer");
        outputs.add("STORY_TEXT_BEGIN\nA rewritten story\nSTORY_TEXT_END");
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
        assertEquals("A rewritten story", persisted.getFinalStory());
    }

    @Test
    void stopsWhenTokenBudgetIsReached() {
        when(agentService.getFlow()).thenReturn(flow(3, 50));
        when(generationService.generateWithUsage(any(), anyString(), anyString(), any(Double.class), anyInt()))
                .thenReturn(result("plan", 60));

        service.createRun(new StartRunRequest(List.of(new StoryWord("book", "书")), "三年级上册"));

        assertEquals("LIMIT_REACHED", persisted.getStatus());
        assertEquals(60, persisted.getTotalTokens());
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
                "STORY_TEXT_BEGIN\nA funny final story\nSTORY_TEXT_END", "fun review",
                "language review", "continuity review", "score", decision));
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
