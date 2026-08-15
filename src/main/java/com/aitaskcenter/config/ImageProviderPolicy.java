package com.aitaskcenter.config;

import com.aitaskcenter.dto.AiProviderConfigItem;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.util.StringUtils;

/** Shared eligibility policy for the single OpenAI Images-compatible adapter. */
public final class ImageProviderPolicy {
    private static final int MAX_OPTION_LENGTH = 64;
    private static final String FIXED_SIZE = "1536x864";
    private static final Set<String> OPTION_KEYS = Set.of("responseFormat", "quality", "size");
    private static final Set<String> QUALITIES = Set.of("auto", "low", "medium", "high", "standard", "hd");

    private ImageProviderPolicy() { }

    public static boolean isExecutable(AiProviderConfigItem provider) {
        try {
            requireExecutable(provider);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static Map<String, Object> requireExecutable(AiProviderConfigItem provider) {
        if (provider == null
                || !StringUtils.hasText(provider.getId())
                || !StringUtils.hasText(provider.getModel())
                || !StringUtils.hasText(provider.getBaseUrl())) {
            throw new IllegalArgumentException("图片 Provider 配置不完整");
        }
        if (!"openai-compatible".equals(clean(provider.getType()).toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("图片 Provider 必须使用 OpenAI compatible 协议");
        }
        if (!provider.isEnabled()
                || !supports(provider, "IMAGE_GENERATION")
                || !supports(provider, "IMAGE_REFERENCE")) {
            throw new IllegalArgumentException("图片 Provider 必须启用并支持图片生成和多参考图");
        }
        validateUrl(provider.getBaseUrl());
        return normalizeOptions(provider.getOptions());
    }

    private static void validateUrl(String baseUrl) {
        try {
            URI uri = URI.create(clean(baseUrl));
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            while (path.endsWith("/") && !path.isEmpty()) path = path.substring(0, path.length() - 1);
            if (!uri.isAbsolute()
                    || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || path.endsWith("/images/generations")
                    || path.endsWith("/images/edits")) {
                throw new IllegalArgumentException();
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("图片 Provider 地址无效");
        }
    }

    private static Map<String, Object> normalizeOptions(Map<String, Object> options) {
        if (options == null || options.isEmpty()) return Map.of();
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            String key = entry.getKey();
            if (!OPTION_KEYS.contains(key) || !(entry.getValue() instanceof String text)) {
                throw new IllegalArgumentException("图片 Provider options 只能包含受支持的字符串参数");
            }
            String value = text.trim();
            if (value.isEmpty() || value.length() > MAX_OPTION_LENGTH) {
                throw new IllegalArgumentException("图片 Provider option 值无效");
            }
            if ("responseFormat".equals(key)) {
                value = value.toLowerCase(Locale.ROOT);
                if (!"b64_json".equals(value)) {
                    throw new IllegalArgumentException("图片 Provider responseFormat 必须为 b64_json");
                }
            } else if ("quality".equals(key)) {
                value = value.toLowerCase(Locale.ROOT);
                if (!QUALITIES.contains(value)) {
                    throw new IllegalArgumentException("图片 Provider quality 无效");
                }
            } else if (!FIXED_SIZE.equals(value)) {
                throw new IllegalArgumentException("图片 Provider size 必须为固定图片尺寸");
            }
            normalized.put(key, value);
        }
        return Map.copyOf(normalized);
    }

    private static boolean supports(AiProviderConfigItem provider, String capability) {
        return provider.getCapabilities() != null && provider.getCapabilities().stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .anyMatch(capability::equals);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
