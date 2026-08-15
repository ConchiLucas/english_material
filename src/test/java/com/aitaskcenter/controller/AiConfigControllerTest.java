package com.aitaskcenter.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aitaskcenter.dto.AiConfigRequest;
import com.aitaskcenter.dto.AiProviderConfigItem;
import com.aitaskcenter.service.AiConfigService;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiConfigControllerTest {
    private AiConfigService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(AiConfigService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AiConfigController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void bootstrapsImageProviderAtDedicatedRouteWithoutReturningSecret() throws Exception {
        AiConfigRequest response = new AiConfigRequest();
        response.setActive("text");
        AiProviderConfigItem image = new AiProviderConfigItem();
        image.setId("antigravity-gemini-image");
        image.setLabel("Antigravity Gemini Image");
        image.setType("openai-compatible");
        image.setBaseUrl("http://antigravity.internal/v1");
        image.setApiKey(null);
        image.setModel("gemini-3-pro-image");
        image.setCapabilities(List.of("IMAGE_GENERATION", "IMAGE_REFERENCE"));
        response.setProviders(List.of(image));
        when(service.bootstrapAntigravityImageProvider(any())).thenReturn(response);

        mvc.perform(post("/api/ai/config/image/bootstrap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceProviderId\":\"antigravity-gemini-3-1-pro\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("图片模型配置已创建"))
                .andExpect(jsonPath("$.data.providers[0].id").value("antigravity-gemini-image"))
                .andExpect(jsonPath("$.data.providers[0].api_key").value(Matchers.nullValue()))
                .andExpect(content().string(Matchers.not(Matchers.containsString("local-secret-never-return"))));

        ArgumentCaptor<com.aitaskcenter.dto.ImageProviderBootstrapRequest> request =
                ArgumentCaptor.forClass(com.aitaskcenter.dto.ImageProviderBootstrapRequest.class);
        verify(service).bootstrapAntigravityImageProvider(request.capture());
        org.junit.jupiter.api.Assertions.assertEquals("antigravity-gemini-3-1-pro",
                request.getValue().sourceProviderId());
    }

    @Test
    void delegatesNullBodyToBoundedValidationWithoutNpeDetails() throws Exception {
        when(service.bootstrapAntigravityImageProvider(isNull()))
                .thenThrow(new IllegalArgumentException("请选择凭据来源 Provider"));

        mvc.perform(post("/api/ai/config/image/bootstrap")
                        .contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(7))
                .andExpect(jsonPath("$.msg").value("请选择凭据来源 Provider"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("NullPointerException"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("Cannot invoke"))));

        verify(service).bootstrapAntigravityImageProvider(null);
    }
}
