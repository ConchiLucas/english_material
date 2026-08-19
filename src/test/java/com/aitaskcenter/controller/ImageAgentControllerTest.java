package com.aitaskcenter.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aitaskcenter.dto.ImageAgentDtos.AgentUpdateRequest;
import com.aitaskcenter.dto.ImageAgentDtos.AgentView;
import com.aitaskcenter.dto.ImageAgentDtos.FlowConfigView;
import com.aitaskcenter.dto.ImageAgentDtos.FlowUpdateRequest;
import com.aitaskcenter.dto.ImageAgentDtos.FlowView;
import com.aitaskcenter.dto.ImageAgentDtos.PromptVersionView;
import com.aitaskcenter.dto.ImageAgentDtos.StageView;
import com.aitaskcenter.service.ImageAgentService;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ImageAgentControllerTest {
    private static final OffsetDateTime UPDATED = OffsetDateTime.parse("2026-08-15T10:00:00+08:00");
    private ImageAgentService service;
    private MockMvc mvc;

    @BeforeEach void setUp() { service = mock(ImageAgentService.class); mvc = MockMvcBuilders.standaloneSetup(new ImageAgentController(service)).setControllerAdvice(new ApiExceptionHandler()).build(); }

    @Test void getsFlowAtStaticRoute() throws Exception {
        when(service.getFlow()).thenReturn(new FlowView(List.of(new StageView("understanding", "理解", "", 1, List.of())), flow(), List.of()));
        mvc.perform(get("/api/image-agents/flow")).andExpect(status().isOk()).andExpect(jsonPath("$.data.stages[0].key").value("understanding"));
        verify(service).getFlow();
    }

    @Test void putsAgentWithJsonBodyAndUrlEncodedKey() throws Exception {
        when(service.updateAgent(eq("image-story-analyst"), any())).thenReturn(agent());
        mvc.perform(put(URI.create("/api/image-agents/image%2Dstory%2Danalyst")).contentType(MediaType.APPLICATION_JSON).content("""
                {"systemPrompt":"prompt","aiProviderId":"provider","temperature":0.4,"enabled":true,"updatedAt":"2026-08-15T10:00:00+08:00"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.msg").value("Prompt 已保存"));
        ArgumentCaptor<AgentUpdateRequest> capture = ArgumentCaptor.forClass(AgentUpdateRequest.class);
        verify(service).updateAgent(eq("image-story-analyst"), capture.capture());
        org.junit.jupiter.api.Assertions.assertEquals(UPDATED.toInstant(), capture.getValue().updatedAt().toInstant());
    }

    @Test void getsVersionsAndRestoresWithTimestamp() throws Exception {
        when(service.versions("image-story-analyst")).thenReturn(List.of(new PromptVersionView(2, "p", "provider", .2, true, UPDATED)));
        when(service.restoreVersion(eq("image-story-analyst"), eq(2), any())).thenReturn(agent());
        mvc.perform(get("/api/image-agents/image-story-analyst/versions")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].version").value(2));
        mvc.perform(post("/api/image-agents/image-story-analyst/versions/2/restore").contentType(MediaType.APPLICATION_JSON).content("{\"updatedAt\":\"2026-08-15T10:00:00+08:00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.msg").value("Prompt 版本已恢复"));
        verify(service).versions("image-story-analyst");
        verify(service).restoreVersion(eq("image-story-analyst"), eq(2), any());
    }

    @Test void restoreNullJsonBodyDelegatesNullTimestampWithoutLeakingNpeDetails() throws Exception {
        when(service.restoreVersion(eq("image-story-analyst"), eq(2), isNull())).thenReturn(agent());

        mvc.perform(post("/api/image-agents/image-story-analyst/versions/2/restore")
                        .contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("Prompt 版本已恢复"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("NullPointerException"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Cannot invoke"))));

        verify(service).restoreVersion("image-story-analyst", 2, null);
    }

    @Test void usesStaticFlowConfigRouteInsteadOfDynamicAgentRoute() throws Exception {
        when(service.updateFlow(any())).thenReturn(flow());
        mvc.perform(put("/api/image-agents/flow/config").contentType(MediaType.APPLICATION_JSON).content("""
                {"imageProviderId":"image","width":1536,"height":864,"maxShotsPerScene":5,"maxShotsPerStory":20,"updatedAt":"2026-08-15T10:00:00+08:00"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.width").value(1536));
        ArgumentCaptor<FlowUpdateRequest> capture = ArgumentCaptor.forClass(FlowUpdateRequest.class);
        verify(service).updateFlow(capture.capture());
        org.junit.jupiter.api.Assertions.assertEquals(1536, capture.getValue().width());
    }

    private FlowConfigView flow() { return new FlowConfigView("image", 1536, 864, 5, 20, UPDATED); }
    private AgentView agent() { return new AgentView("image-story-analyst", "分析", "AGENT", "ANALYST", "understanding", 1, null, "d", List.of(), List.of(), "p", "provider", .2, true, 1, UPDATED, true); }
}
