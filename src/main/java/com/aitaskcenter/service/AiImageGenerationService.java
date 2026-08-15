package com.aitaskcenter.service;

import com.aitaskcenter.dto.AiProviderConfigItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AiImageGenerationService {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final int DEFAULT_WIDTH = 1536;
    private static final int DEFAULT_HEIGHT = 864;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AiImageGenerationService(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    AiImageGenerationService(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public ImageResult generate(AiProviderConfigItem provider, String prompt, String negativePrompt,
                                int width, int height, List<ImageReference> references) {
        validateCapability(provider, "IMAGE_GENERATION", "不支持图片生成");
        List<ImageReference> safeReferences = references == null ? List.of() : List.copyOf(references);
        if (!safeReferences.isEmpty()) {
            validateCapability(provider, "IMAGE_REFERENCE", "不支持参考图编辑");
            safeReferences.forEach(this::validateReference);
        }

        ImageOptions options = imageOptions(provider.getOptions(), width, height);
        try {
            HttpRequest request = safeReferences.isEmpty()
                    ? jsonRequest(provider, prompt, negativePrompt, options)
                    : multipartRequest(provider, prompt, negativePrompt, options, safeReferences);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("图片生成调用失败（HTTP " + response.statusCode() + "）");
            }
            return imageResult(response, options);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("图片生成调用失败，请稍后重试");
        }
    }

    private HttpRequest jsonRequest(AiProviderConfigItem provider, String prompt, String negativePrompt,
                                    ImageOptions options) throws Exception {
        Map<String, Object> body = commonFields(provider, prompt, negativePrompt, options);
        return requestBuilder(provider, endpoint(provider.getBaseUrl(), "/images/generations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
    }

    private HttpRequest multipartRequest(AiProviderConfigItem provider, String prompt, String negativePrompt,
                                         ImageOptions options, List<ImageReference> references) throws IOException {
        String boundary = "----AiImage" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, Object> field : commonFields(provider, prompt, negativePrompt, options).entrySet()) {
            writeTextPart(body, boundary, field.getKey(), String.valueOf(field.getValue()));
        }
        for (ImageReference reference : references) {
            writeImagePart(body, boundary, reference);
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return requestBuilder(provider, endpoint(provider.getBaseUrl(), "/images/edits"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
    }

    private HttpRequest.Builder requestBuilder(AiProviderConfigItem provider, String endpoint) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(REQUEST_TIMEOUT);
        if (StringUtils.hasText(provider.getApiKey())) {
            request.header("Authorization", "Bearer " + provider.getApiKey());
        }
        return request;
    }

    private Map<String, Object> commonFields(AiProviderConfigItem provider, String prompt, String negativePrompt,
                                             ImageOptions options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.getModel());
        body.put("prompt", prompt == null ? "" : prompt);
        if (StringUtils.hasText(negativePrompt)) {
            body.put("negative_prompt", negativePrompt);
        }
        body.put("response_format", options.responseFormat());
        body.put("quality", options.quality());
        body.put("size", options.size());
        return body;
    }

    private ImageResult imageResult(HttpResponse<String> response, ImageOptions options) throws Exception {
        JsonNode root = objectMapper.readTree(response.body());
        String encoded = root.path("data").path(0).path("b64_json").asText("");
        if (!StringUtils.hasText(encoded)) {
            throw new IllegalArgumentException("图片生成返回的图片数据为空");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("图片生成返回的图片数据无效");
        }
        DecodedImage image = decodeImage(bytes);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("responseFormat", options.responseFormat());
        metadata.put("quality", options.quality());
        metadata.put("size", options.size());
        return new ImageResult(bytes, image.mimeType(), image.width(), image.height(),
                root.path("id").asText(response.headers().firstValue("x-request-id").orElse("")), metadata);
    }

    private DecodedImage decodeImage(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IllegalArgumentException("图片生成返回的图片数据无效");
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("图片生成返回的图片数据无效");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IllegalArgumentException("图片生成返回的图片数据无效");
                }
                return new DecodedImage(mimeType(reader.getFormatName()), image.getWidth(), image.getHeight());
            } finally {
                reader.dispose();
            }
        }
    }

    private void writeTextPart(ByteArrayOutputStream body, String boundary, String name, String value) throws IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private void writeImagePart(ByteArrayOutputStream body, String boundary, ImageReference reference) throws IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\""
                + safeFilename(reference.filename()) + "\"\r\nContent-Type: " + reference.mimeType()
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(reference.bytes());
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void validateCapability(AiProviderConfigItem provider, String capability, String message) {
        if (provider == null || provider.getCapabilities() == null || provider.getCapabilities().stream()
                .filter(StringUtils::hasText)
                .noneMatch(value -> capability.equals(value.trim().toUpperCase(Locale.ROOT)))) {
            throw new IllegalArgumentException("AI 配置" + message);
        }
    }

    private void validateReference(ImageReference reference) {
        if (reference == null || !StringUtils.hasText(reference.filename()) || !StringUtils.hasText(reference.mimeType())
                || reference.bytes() == null || reference.bytes().length == 0) {
            throw new IllegalArgumentException("参考图数据无效");
        }
    }

    private ImageOptions imageOptions(Map<String, Object> options, int width, int height) {
        Map<String, Object> safeOptions = options == null ? Map.of() : options;
        String responseFormat = option(safeOptions, "responseFormat", "b64_json");
        if (!"b64_json".equals(responseFormat)) {
            throw new IllegalArgumentException("图片生成仅支持 b64_json 返回格式");
        }
        String quality = option(safeOptions, "quality", "high");
        String defaultSize = width > 0 && height > 0 ? width + "x" + height : DEFAULT_WIDTH + "x" + DEFAULT_HEIGHT;
        String size = option(safeOptions, "size", defaultSize);
        if (!StringUtils.hasText(size)) {
            size = DEFAULT_WIDTH + "x" + DEFAULT_HEIGHT;
        }
        return new ImageOptions(responseFormat, quality, size);
    }

    private String option(Map<String, Object> options, String name, String fallback) {
        Object value = options.get(name);
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? fallback : String.valueOf(value).trim();
    }

    private String endpoint(String baseUrl, String path) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("图片生成服务地址为空");
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.endsWith("/v1") ? value + path : value + "/v1" + path;
    }

    private String safeFilename(String filename) {
        return filename.replaceAll("[\\r\\n\\\"\\\\]", "_");
    }

    private String mimeType(String format) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            default -> "image/" + format.toLowerCase(Locale.ROOT);
        };
    }

    private record ImageOptions(String responseFormat, String quality, String size) {
    }

    private record DecodedImage(String mimeType, int width, int height) {
    }

    public record ImageReference(String filename, String mimeType, byte[] bytes) {
    }

    public record ImageResult(byte[] bytes, String mimeType, int width, int height,
                              String providerRequestId, Map<String, Object> metadata) {
    }
}
