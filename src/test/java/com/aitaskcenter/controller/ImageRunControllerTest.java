package com.aitaskcenter.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aitaskcenter.dto.ImageRunDtos.RunDetail;
import com.aitaskcenter.dto.ImageRunDtos.RunSummary;
import com.aitaskcenter.dto.ImageRunDtos.SourceStoryView;
import com.aitaskcenter.dto.ImageRunDtos.StartImageRunRequest;
import com.aitaskcenter.dto.StoryRunDtos.StoryWord;
import com.aitaskcenter.service.ImageRunExecutionService;
import com.aitaskcenter.service.ImageRunQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ImageRunControllerTest {
    private ImageRunQueryService queryService;
    private ImageRunExecutionService executionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(ImageRunQueryService.class);
        executionService = mock(ImageRunExecutionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ImageRunController(queryService, executionService)).build();
    }

    @Test
    void exposesExactSourceListAndDetailRoutes() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-15T10:00:00+08:00");
        SourceStoryView source = new SourceStoryView("story-1", List.of(new StoryWord("book", "书")), null,
                "小学三年级上册", "COMPLETED", "A story.", now, now);
        RunSummary summary = summary("image-1", now);
        RunDetail detail = new RunDetail("image-1", "story-1", List.of(new StoryWord("book", "书")), null,
                "小学三年级上册", "COMPLETED", "A story.", "7", "Paper Cut", "{}", "{}",
                1, 1, 30, null, now, now, now, List.of(), List.of(), List.of());
        when(queryService.listSourceStories()).thenReturn(List.of(source));
        when(queryService.listRuns()).thenReturn(List.of(summary));
        when(queryService.getRun("image-1")).thenReturn(detail);

        mockMvc.perform(get("/api/image-runs/source-stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].runId").value("story-1"))
                .andExpect(jsonPath("$.data[0].finalStory").value("A story."));
        mockMvc.perform(get("/api/image-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].runId").value("image-1"));
        mockMvc.perform(get("/api/image-runs/image-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storySnapshot").value("A story."));
    }

    @Test
    void createsRunOnceAndReturnsQueuedSnapshot() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-15T10:00:00+08:00");
        StartImageRunRequest request = new StartImageRunRequest("story-1", 7L);
        RunSummary queued = summary("image-queued", now);
        when(executionService.createRun(request)).thenReturn(queued);

        mockMvc.perform(post("/api/image-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storyRunId\":\"story-1\",\"stylePresetId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("图片批次已创建"))
                .andExpect(jsonPath("$.data.runId").value("image-queued"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));

        verify(executionService).createRun(request);
        verifyNoMoreInteractions(executionService);
    }

    private RunSummary summary(String runId, OffsetDateTime now) {
        return new RunSummary(runId, "story-1", 7L, "Paper Cut", "小学三年级上册",
                List.of(new StoryWord("book", "书")), null, "QUEUED", 0, 0, 0, null,
                now, null, null);
    }
}
