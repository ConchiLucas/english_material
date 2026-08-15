package com.aitaskcenter.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aitaskcenter.dto.MinioConfigRequest;
import com.aitaskcenter.dto.MinioConfigView;
import com.aitaskcenter.service.MinioConfigService;
import java.time.OffsetDateTime;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MinioConfigControllerTest {
    private static final OffsetDateTime UPDATED = OffsetDateTime.parse("2026-08-16T10:00:00+08:00");
    private static final String SECRET = "must-not-appear-in-response";

    private MinioConfigService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(MinioConfigService.class);
        mvc = MockMvcBuilders.standaloneSetup(new MinioConfigController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void getsRedactedConfiguration() throws Exception {
        when(service.get()).thenReturn(view());

        mvc.perform(get("/api/minio-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bucketName").value("english-material"))
                .andExpect(jsonPath("$.data.secretConfigured").value(true))
                .andExpect(jsonPath("$.data.secretAccessKey").doesNotExist())
                .andExpect(content().string(Matchers.not(Matchers.containsString(SECRET))));

        verify(service).get();
    }

    @Test
    void savesConfigurationWithoutReturningSecret() throws Exception {
        when(service.save(any())).thenReturn(view());

        mvc.perform(put("/api/minio-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("MinIO 配置已保存"))
                .andExpect(jsonPath("$.data.secretConfigured").value(true))
                .andExpect(content().string(Matchers.not(Matchers.containsString(SECRET))));

        ArgumentCaptor<MinioConfigRequest> request = ArgumentCaptor.forClass(MinioConfigRequest.class);
        verify(service).save(request.capture());
        org.junit.jupiter.api.Assertions.assertEquals(SECRET, request.getValue().secretAccessKey());
    }

    @Test
    void testsConnectionWithoutReturningRequestOrSecret() throws Exception {
        mvc.perform(post("/api/minio-config/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("MinIO 连接验证成功"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(content().string(Matchers.not(Matchers.containsString(SECRET))));

        verify(service).test(any(MinioConfigRequest.class));
    }

    private static String requestJson() {
        return """
                {"enabled":true,"endpoint":"minio.internal:9000","accessKeyId":"english-app",
                 "secretAccessKey":"%s","useSsl":false,"bucketName":"english-material",
                 "basePath":"image-story","updatedAt":"2026-08-16T10:00:00+08:00"}
                """.formatted(SECRET);
    }

    private static MinioConfigView view() {
        return new MinioConfigView(true, "minio.internal:9000", "english-app", false,
                "english-material", "image-story", true, UPDATED);
    }
}
