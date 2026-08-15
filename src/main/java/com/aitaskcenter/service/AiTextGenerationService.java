package com.aitaskcenter.service;

import com.aitaskcenter.dto.AiProviderConfigItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private static final long MAX_RESPONSE_BYTES = 2L * 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public AiTextGenerationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String generate(AiProviderConfigItem provider, String systemPrompt, String userPrompt,
                           double temperature, int maxTokens) {
        return generateWithUsage(provider, systemPrompt, userPrompt, temperature, maxTokens).text();
    }

    public GenerationResult generateWithUsage(
            AiProviderConfigItem provider,
            String systemPrompt,
            String userPrompt,
            double temperature,
            int maxTokens) {
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
            HttpResponse<InputStream> response = httpClient.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload))).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            JsonNode root;
            try (InputStream responseBody = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalArgumentException("AI 调用失败（HTTP " + response.statusCode() + "）");
                }
                root = objectMapper.readTree(readBounded(response, responseBody));
            }
            String content = anthropic ? anthropicContent(root) : openAiContent(root);
            if (!StringUtils.hasText(content)) throw new IllegalArgumentException("AI 返回内容为空");
            JsonNode usage = root.path("usage");
            long inputTokens = anthropic
                    ? usage.path("input_tokens").asLong(0)
                    : usage.path("prompt_tokens").asLong(0);
            long outputTokens = anthropic
                    ? usage.path("output_tokens").asLong(0)
                    : usage.path("completion_tokens").asLong(0);
            long totalTokens = usage.path("total_tokens").asLong(saturatedAdd(inputTokens, outputTokens));
            return new GenerationResult(content.trim(), inputTokens, outputTokens, totalTokens);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("AI 调用失败: " + ex.getMessage());
        }
    }

    public record GenerationResult(String text, long inputTokens, long outputTokens, long totalTokens) {
    }

    private byte[] readBounded(HttpResponse<?> response, InputStream input) throws IOException {
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (contentLength > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("AI 响应超过最大长度");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("AI 响应超过最大长度");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private long saturatedAdd(long left, long right) {
        if (left < 0 || right < 0) return 0;
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
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
