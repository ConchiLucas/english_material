package com.aitaskcenter.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aitaskcenter.dto.StoryRunDtos.RunDetail;
import com.aitaskcenter.dto.StoryRunDtos.RunStepView;
import com.aitaskcenter.dto.StoryRunDtos.RunSummary;
import com.aitaskcenter.dto.StoryRunDtos.StoryWord;
import com.aitaskcenter.service.StoryRunQueryService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StoryRunControllerTest {
    private StoryRunQueryService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(StoryRunQueryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new StoryRunController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listsRunBatches() throws Exception {
        when(service.listRuns()).thenReturn(List.of(summary("run-2"), summary("run-1")));

        mockMvc.perform(get("/api/story-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].runId").value("run-2"))
                .andExpect(jsonPath("$.data[0].words[0].word").value("book"));

        verify(service).listRuns();
    }

    @Test
    void getsFullRunDetail() throws Exception {
        RunSummary summary = summary("run-2");
        RunStepView step = new RunStepView(
                1L, 1, 1, "story-writer", "故事作家 Agent", 2,
                "provider-1", "gemini", "{\"storyBlueprint\":\"full input\"}",
                "full output", "COMPLETED", 10, 20, 30, 1500, OffsetDateTime.parse("2026-08-13T20:00:00+08:00"));
        when(service.getRun("run-2")).thenReturn(new RunDetail(
                summary.runId(), summary.words(), summary.targetGrade(), summary.status(),
                "Final story", null, 30, summary.createdAt(), summary.startedAt(), summary.finishedAt(), List.of(step)));

        mockMvc.perform(get("/api/story-runs/run-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.steps[0].agentKey").value("story-writer"))
                .andExpect(jsonPath("$.data.steps[0].inputJson").value("{\"storyBlueprint\":\"full input\"}"))
                .andExpect(jsonPath("$.data.steps[0].outputText").value("full output"))
                .andExpect(jsonPath("$.data.finalStory").value("Final story"));
    }

    @Test
    void unknownRunUsesStandardErrorEnvelope() throws Exception {
        when(service.getRun("missing")).thenThrow(new IllegalArgumentException("运行批次不存在"));

        mockMvc.perform(get("/api/story-runs/missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(7))
                .andExpect(jsonPath("$.msg").value("运行批次不存在"));
    }

    private RunSummary summary(String runId) {
        OffsetDateTime time = OffsetDateTime.parse("2026-08-13T20:00:00+08:00");
        return new RunSummary(
                runId,
                List.of(new StoryWord("book", "书")),
                "三年级上册",
                "COMPLETED",
                30,
                time,
                time,
                time);
    }
}
