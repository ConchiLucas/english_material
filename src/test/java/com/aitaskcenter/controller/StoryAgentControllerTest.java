package com.aitaskcenter.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aitaskcenter.config.StoryAgentInitializer;
import com.aitaskcenter.dto.StoryAgentDtos.AgentUpdateRequest;
import com.aitaskcenter.dto.StoryAgentDtos.AgentView;
import com.aitaskcenter.dto.StoryAgentDtos.BudgetUpdateRequest;
import com.aitaskcenter.dto.StoryAgentDtos.BudgetView;
import com.aitaskcenter.dto.StoryAgentDtos.FlowView;
import com.aitaskcenter.dto.StoryAgentDtos.PromptVersionView;
import com.aitaskcenter.dto.StoryAgentDtos.StageView;
import com.aitaskcenter.service.StoryAgentService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StoryAgentControllerTest {
    private static final OffsetDateTime UPDATED_AT = OffsetDateTime.parse("2026-08-13T10:15:30+08:00");

    private StoryAgentService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(StoryAgentService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new StoryAgentController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void getFlowReturnsPlanningStage() throws Exception {
        FlowView flow = new FlowView(
                List.of(new StageView("planning", "策划", "", 1, List.of())),
                budget());
        when(service.getFlow()).thenReturn(flow);

        mockMvc.perform(get("/api/story-agents/flow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.stages[0].key").value("planning"));

        verify(service).getFlow();
    }

    @Test
    void updateAgentPassesBodyAndReturnsSavedAgent() throws Exception {
        when(service.update(eq("story-writer"), any(AgentUpdateRequest.class)))
                .thenReturn(agent("story-writer"));

        mockMvc.perform(put("/api/story-agents/story-writer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "systemPrompt": "Write a vivid story",
                                  "aiProviderId": "provider-1",
                                  "temperature": 0.7,
                                  "enabled": true,
                                  "updatedAt": "2026-08-13T10:15:30+08:00"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.key").value("story-writer"))
                .andExpect(jsonPath("$.msg").value("Prompt 已保存"));

        ArgumentCaptor<AgentUpdateRequest> requestCaptor = ArgumentCaptor.forClass(AgentUpdateRequest.class);
        verify(service).update(eq("story-writer"), requestCaptor.capture());
        AgentUpdateRequest captured = requestCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("Write a vivid story", captured.systemPrompt());
        org.junit.jupiter.api.Assertions.assertEquals("provider-1", captured.aiProviderId());
        org.junit.jupiter.api.Assertions.assertEquals(0.7, captured.temperature());
        org.junit.jupiter.api.Assertions.assertEquals(true, captured.enabled());
        org.junit.jupiter.api.Assertions.assertEquals(UPDATED_AT.toInstant(), captured.updatedAt().toInstant());
    }

    @Test
    void getVersionsPassesAgentKeyAndReturnsVersions() throws Exception {
        when(service.versions("story-writer")).thenReturn(List.of(
                new PromptVersionView(2, "Prompt v2", "provider-1", 0.7, true, UPDATED_AT)));

        mockMvc.perform(get("/api/story-agents/story-writer/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].version").value(2));

        verify(service).versions("story-writer");
    }

    @Test
    void restorePassesAgentKeyAndVersionAndReturnsRestoredAgent() throws Exception {
        when(service.restore(eq("story-writer"), eq(3), any(OffsetDateTime.class)))
                .thenReturn(agent("story-writer"));

        mockMvc.perform(post("/api/story-agents/story-writer/versions/3/restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "updatedAt": "2026-08-13T10:15:30+08:00"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.key").value("story-writer"))
                .andExpect(jsonPath("$.msg").value("Prompt 版本已恢复"));

        ArgumentCaptor<OffsetDateTime> timestampCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(service).restore(eq("story-writer"), eq(3), timestampCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(UPDATED_AT.toInstant(), timestampCaptor.getValue().toInstant());
    }

    @Test
    void updateBudgetUsesStaticFlowConfigRouteAndReturnsBudget() throws Exception {
        BudgetView budget = budget();
        when(service.updateBudget(any(BudgetUpdateRequest.class))).thenReturn(budget);

        mockMvc.perform(put("/api/story-agents/flow/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "maxQualityRounds": 3,
                                  "maxLocalRevisions": 2,
                                  "maxWriterRewrites": 2,
                                  "maxDirectorReturns": 1,
                                  "maxPitchReturns": 1,
                                  "maxPlanReturns": 1,
                                  "maxTotalTokens": 120000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.maxTotalTokens").value(120000))
                .andExpect(jsonPath("$.msg").value("质量预算已保存"));

        ArgumentCaptor<BudgetUpdateRequest> requestCaptor = ArgumentCaptor.forClass(BudgetUpdateRequest.class);
        verify(service).updateBudget(requestCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                new BudgetUpdateRequest(3, 2, 2, 1, 1, 1, 120000),
                requestCaptor.getValue());
        verify(service, never()).update(any(), any());
    }

    @Test
    void illegalServiceExceptionIsConvertedToCodeSeven() throws Exception {
        when(service.getFlow()).thenThrow(new IllegalArgumentException("invalid story flow"));

        mockMvc.perform(get("/api/story-agents/flow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(7))
                .andExpect(jsonPath("$.msg").value("invalid story flow"));
    }

    @Test
    void initializerInvokesInitializeDefaultsExactlyOnce() throws Exception {
        StoryAgentService initializerService = mock(StoryAgentService.class);
        StoryAgentInitializer initializer = new StoryAgentInitializer(initializerService);

        initializer.run(null);

        verify(initializerService).initializeDefaults();
        verifyNoMoreInteractions(initializerService);
    }

    private AgentView agent(String key) {
        return new AgentView(
                key,
                "故事作家",
                "AGENT",
                "WRITER",
                "writing",
                1,
                null,
                "Writes stories",
                List.of("outline"),
                List.of("story-planner"),
                List.of("story-editor"),
                "Write a vivid story",
                "provider-1",
                0.7,
                true,
                2,
                UPDATED_AT,
                true);
    }

    private BudgetView budget() {
        return new BudgetView(3, 2, 2, 1, 1, 1, 120000, UPDATED_AT);
    }
}
