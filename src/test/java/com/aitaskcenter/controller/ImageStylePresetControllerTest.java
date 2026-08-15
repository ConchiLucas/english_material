package com.aitaskcenter.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aitaskcenter.dto.ImageAgentDtos.StyleCreateRequest;
import com.aitaskcenter.dto.ImageAgentDtos.StylePresetView;
import com.aitaskcenter.dto.ImageAgentDtos.StyleUpdateRequest;
import com.aitaskcenter.service.ImageAgentService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ImageStylePresetControllerTest {
    private ImageAgentService service;
    private MockMvc mvc;
    @BeforeEach void setUp() { service = mock(ImageAgentService.class); mvc = MockMvcBuilders.standaloneSetup(new ImageStylePresetController(service)).setControllerAdvice(new ApiExceptionHandler()).build(); }

    @Test void getsStylesCreatesAndUpdatesUsingExactRoutes() throws Exception {
        when(service.styles()).thenReturn(List.of(style()));
        when(service.createStyle(any())).thenReturn(style());
        when(service.updateStyle(eq(7L), any())).thenReturn(style());
        mvc.perform(get("/api/image-style-presets")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].key").value("watercolor"));
        mvc.perform(post("/api/image-style-presets").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"new","positivePrompt":"p","negativePrompt":"n","description":"d","enabled":true}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.msg").value("画风预设已创建"));
        mvc.perform(put("/api/image-style-presets/7").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"updated","positivePrompt":"p","negativePrompt":"n","description":"d","enabled":false,"updatedAt":"2026-08-15T10:00:00+08:00"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.msg").value("画风预设已保存"));
        ArgumentCaptor<StyleCreateRequest> create = ArgumentCaptor.forClass(StyleCreateRequest.class);
        ArgumentCaptor<StyleUpdateRequest> update = ArgumentCaptor.forClass(StyleUpdateRequest.class);
        verify(service).createStyle(create.capture()); verify(service).updateStyle(eq(7L), update.capture());
        org.junit.jupiter.api.Assertions.assertEquals("new", create.getValue().name());
        org.junit.jupiter.api.Assertions.assertEquals(OffsetDateTime.parse("2026-08-15T10:00:00+08:00").toInstant(), update.getValue().updatedAt().toInstant());
    }
    private StylePresetView style() { return new StylePresetView(7L, "watercolor", "水彩", "p", "n", "d", true, true, OffsetDateTime.parse("2026-08-15T10:00:00+08:00")); }
}
