package com.aitaskcenter.service;

import com.aitaskcenter.dto.AiProviderConfigItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AiTextGenerationService {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public AiTextGenerationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String generate(AiProviderConfigItem provider, String systemPrompt, String userPrompt,
                           double temperature, int maxTokens) {
        try {
            boolean anthropic = "anthropic-compatible".equals(provider.getType());
            String endpoint = endpoint(provider.getBaseUrl(), anthropic ? "/messages" : "/chat/completions");
            Map<String, Object> payload = anthropic
                    ? anthropicPayload(provider, systemPrompt, userPrompt, temperature, maxTokens)
                    : openAiPayload(provider, systemPrompt, userPrompt, temperature, maxTokens);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json");
            if (anthropic) {
                request.header("anthropic-version", "2023-06-01");
                if (StringUtils.hasText(provider.getApiKey())) request.header("x-api-key", provider.getApiKey());
            } else if (StringUtils.hasText(provider.getApiKey())) {
                request.header("Authorization", "Bearer " + provider.getApiKey());
            }
            HttpResponse<String> response = httpClient.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("AI 调用失败（HTTP " + response.statusCode() + "）: " + bounded(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = anthropic ? anthropicContent(root) : openAiContent(root);
            if (!StringUtils.hasText(content)) throw new IllegalArgumentException("AI 返回内容为空");
            return content.trim();
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("AI 调用失败: " + ex.getMessage());
        }
    }

    private Map<String, Object> openAiPayload(AiProviderConfigItem provider, String systemPrompt,
                                               String userPrompt, double temperature, int maxTokens) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", provider.getModel());
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        return payload;
    }

    private Map<String, Object> anthropicPayload(AiProviderConfigItem provider, String systemPrompt,
                                                  String userPrompt, double temperature, int maxTokens) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", provider.getModel());
        payload.put("system", systemPrompt);
        payload.put("messages", List.of(Map.of("role", "user", "content", userPrompt)));
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        return payload;
    }

    private String openAiContent(JsonNode root) {
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            content.forEach(item -> {
                String text = item.path("text").asText("");
                if (!text.isBlank()) builder.append(text);
            });
            return builder.toString();
        }
        return "";
    }

    private String anthropicContent(JsonNode root) {
        StringBuilder builder = new StringBuilder();
        root.path("content").forEach(item -> {
            if ("text".equals(item.path("type").asText())) builder.append(item.path("text").asText(""));
        });
        return builder.toString();
    }

    private String endpoint(String baseUrl, String suffix) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (value.endsWith("/chat/completions") || value.endsWith("/messages")) return value;
        return value.endsWith("/") ? value.substring(0, value.length() - 1) + suffix : value + suffix;
    }

    private String bounded(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= 600 ? text : text.substring(0, 600) + "…";
    }
}
