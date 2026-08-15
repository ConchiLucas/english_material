package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aitaskcenter.dto.AiConfigRequest;
import com.aitaskcenter.dto.AiProviderConfigItem;
import com.aitaskcenter.dto.ImageProviderBootstrapRequest;
import com.aitaskcenter.model.AiConfig;
import com.aitaskcenter.repository.AiConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiConfigServiceTest {
    private static final String SOURCE_ID = "antigravity-gemini-3-1-pro";
    private static final String SECRET = "local-secret-never-return";
    private static final String BASE_URL = "http://antigravity.internal/v1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiConfigRepository repository;
    private AiConfigService service;
    private AiConfig config;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(AiConfigRepository.class);
        service = new AiConfigService(repository, objectMapper);
        config = configWith(sourceProvider());
        when(repository.findByConfigKey("default")).thenReturn(Optional.of(config));
        when(repository.save(any(AiConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void bootstrapsFixedImageProviderByCopyingSecretOnlyInsidePersistence() throws Exception {
        AiConfigRequest result = service.bootstrapAntigravityImageProvider(
                new ImageProviderBootstrapRequest(SOURCE_ID));

        ArgumentCaptor<AiConfig> capture = ArgumentCaptor.forClass(AiConfig.class);
        verify(repository).save(capture.capture());
        Map<String, AiProviderConfigItem> persisted = providers(capture.getValue().getProviders());
        AiProviderConfigItem target = persisted.get("antigravity-gemini-image");
        assertEquals("Antigravity Gemini Image", target.getLabel());
        assertEquals("openai-compatible", target.getType());
        assertEquals(BASE_URL, target.getBaseUrl());
        assertEquals(SECRET, target.getApiKey());
        assertEquals("gemini-3-pro-image", target.getModel());
        assertEquals(List.of("IMAGE_GENERATION", "IMAGE_REFERENCE"), target.getCapabilities());
        assertEquals(Map.of("responseFormat", "b64_json", "quality", "hd", "size", "1536x864"),
                target.getOptions());
        assertEquals(SOURCE_ID, result.getActive());
        AiProviderConfigItem returned = result.getProviders().stream()
                .filter(provider -> "antigravity-gemini-image".equals(provider.getId()))
                .findFirst().orElseThrow();
        assertNull(returned.getApiKey());
    }

    @Test
    void rejectsMissingSourceWithoutWriting() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.bootstrapAntigravityImageProvider(
                        new ImageProviderBootstrapRequest("missing-provider")));

        assertFalse(exception.getMessage().contains(SECRET));
        assertFalse(exception.getMessage().contains(BASE_URL));
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsSourceWithoutReusableSecretOrOpenAiProtocol() throws Exception {
        AiProviderConfigItem invalid = sourceProvider();
        invalid.setApiKey("");
        invalid.setType("anthropic-compatible");
        config.setProviders(objectMapper.writeValueAsString(Map.of(SOURCE_ID, invalid)));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.bootstrapAntigravityImageProvider(
                        new ImageProviderBootstrapRequest(SOURCE_ID)));

        assertFalse(exception.getMessage().contains(SECRET));
        assertFalse(exception.getMessage().contains(BASE_URL));
        verify(repository, never()).save(any());
    }

    @Test
    void refusesToOverwriteExistingImageProvider() throws Exception {
        AiProviderConfigItem existing = sourceProvider();
        existing.setId("antigravity-gemini-image");
        existing.setModel("custom-image-model");
        config.setProviders(objectMapper.writeValueAsString(Map.of(SOURCE_ID, sourceProvider(), existing.getId(), existing)));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.bootstrapAntigravityImageProvider(
                        new ImageProviderBootstrapRequest(SOURCE_ID)));

        assertEquals("Antigravity 图片模型配置已存在", exception.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsNullRequestWithBoundedMessage() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.bootstrapAntigravityImageProvider(null));

        assertEquals("请选择凭据来源 Provider", exception.getMessage());
        verify(repository, never()).save(any());
    }

    private AiConfig configWith(AiProviderConfigItem provider) throws Exception {
        AiConfig value = new AiConfig();
        value.setConfigKey("default");
        value.setActive(provider.getId());
        value.setProviders(objectMapper.writeValueAsString(Map.of(provider.getId(), provider)));
        return value;
    }

    private AiProviderConfigItem sourceProvider() {
        AiProviderConfigItem provider = new AiProviderConfigItem();
        provider.setId(SOURCE_ID);
        provider.setLabel("Antigravity Gemini 3.1 Pro");
        provider.setType("openai-compatible");
        provider.setBaseUrl(BASE_URL);
        provider.setApiKey(SECRET);
        provider.setModel("gemini-3.1-pro");
        provider.setMaxTokens(4096);
        provider.setCapabilities(List.of("TEXT_GENERATION"));
        provider.setEnabled(true);
        return provider;
    }

    private Map<String, AiProviderConfigItem> providers(String json) throws Exception {
        return objectMapper.readValue(json,
                new TypeReference<LinkedHashMap<String, AiProviderConfigItem>>() { });
    }
}
