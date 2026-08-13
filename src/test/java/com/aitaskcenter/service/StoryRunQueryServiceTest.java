package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.StoryRunDtos.RunDetail;
import com.aitaskcenter.dto.StoryRunDtos.RunSummary;
import com.aitaskcenter.model.StoryRun;
import com.aitaskcenter.model.StoryRunStep;
import com.aitaskcenter.repository.StoryRunRepository;
import com.aitaskcenter.repository.StoryRunStepRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoryRunQueryServiceTest {
    private StoryRunRepository runRepository;
    private StoryRunStepRepository stepRepository;
    private StoryRunQueryService service;

    @BeforeEach
    void setUp() {
        runRepository = mock(StoryRunRepository.class);
        stepRepository = mock(StoryRunStepRepository.class);
        service = new StoryRunQueryService(runRepository, stepRepository, new ObjectMapper());
    }

    @Test
    void listsNewestRunsWithTheirCompleteWordSnapshots() {
        StoryRun newest = run("run-new", "[{\"word\":\"book\",\"meaning\":\"书\"},{\"word\":\"green\",\"meaning\":\"绿色\"}]");
        StoryRun older = run("run-old", "[{\"word\":\"friend\",\"meaning\":\"朋友\"}]");
        when(runRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(newest, older));

        List<RunSummary> result = service.listRuns();

        assertEquals(List.of("run-new", "run-old"), result.stream().map(RunSummary::runId).toList());
        assertEquals(List.of("book", "green"), result.get(0).words().stream().map(word -> word.word()).toList());
        verify(runRepository).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void returnsOrderedStepsWithoutTruncatingInputOrOutput() {
        String longInput = "input-" + "x".repeat(8_000);
        String longOutput = "output-" + "y".repeat(12_000);
        StoryRun run = run("run-1", "[{\"word\":\"book\",\"meaning\":\"书\"}]");
        run.setFinalStory("Final story");
        StoryRunStep first = step(1, "vocabulary-planner", longInput, longOutput);
        StoryRunStep second = step(2, "story-writer", "writer input", "writer output");
        when(runRepository.findByRunId("run-1")).thenReturn(Optional.of(run));
        when(stepRepository.findAllByRunIdOrderBySequenceAsc("run-1")).thenReturn(List.of(first, second));

        RunDetail result = service.getRun("run-1");

        assertEquals(List.of(1, 2), result.steps().stream().map(step -> step.sequence()).toList());
        assertEquals(longInput, result.steps().get(0).inputJson());
        assertEquals(longOutput, result.steps().get(0).outputText());
        assertEquals("Final story", result.finalStory());
    }

    @Test
    void rejectsUnknownRunId() {
        when(runRepository.findByRunId("missing")).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.getRun("missing"));

        assertEquals("运行批次不存在", error.getMessage());
    }

    private StoryRun run(String runId, String wordsJson) {
        StoryRun run = new StoryRun();
        run.setRunId(runId);
        run.setInputWordsJson(wordsJson);
        run.setTargetGrade("三年级上册");
        run.setStatus("COMPLETED");
        return run;
    }

    private StoryRunStep step(int sequence, String agentKey, String input, String output) {
        StoryRunStep step = new StoryRunStep();
        step.setRunId("run-1");
        step.setSequence(sequence);
        step.setQualityRound(1);
        step.setAgentKey(agentKey);
        step.setAgentName(agentKey);
        step.setInputJson(input);
        step.setOutputText(output);
        step.setStatus("COMPLETED");
        return step;
    }
}
