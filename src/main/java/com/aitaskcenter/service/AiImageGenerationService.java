package com.aitaskcenter.service;

import com.aitaskcenter.dto.AiProviderConfigItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Set;
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
    private static final int MAX_REFERENCE_COUNT = 8;
    private static final long MAX_REFERENCE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_TOTAL_REFERENCE_BYTES = 40L * 1024 * 1024;
    private static final long MAX_RESPONSE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_DECODED_BYTES = 48L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;
    private static final Set<String> SUPPORTED_IMAGE_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/bmp");

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
            validateReferences(safeReferences);
        }

        ImageOptions options = imageOptions(provider.getOptions(), width, height);
        URI baseUri = baseUri(provider.getBaseUrl());
        try {
            HttpRequest request = safeReferences.isEmpty()
                    ? jsonRequest(provider, prompt, negativePrompt, options, baseUri)
                    : multipartRequest(provider, prompt, negativePrompt, options, safeReferences, baseUri);
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream responseBody = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalArgumentException("图片生成调用失败（HTTP " + response.statusCode() + "）");
                }
                return imageResult(response, readBounded(response, responseBody), options);
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("图片生成调用失败，请稍后重试");
        }
    }

    private HttpRequest jsonRequest(AiProviderConfigItem provider, String prompt, String negativePrompt,
                                    ImageOptions options, URI baseUri) throws Exception {
        Map<String, Object> body = commonFields(provider, prompt, negativePrompt, options);
        return requestBuilder(provider, endpoint(baseUri, "/images/generations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
    }

    private HttpRequest multipartRequest(AiProviderConfigItem provider, String prompt, String negativePrompt,
                                         ImageOptions options, List<ImageReference> references, URI baseUri) throws IOException {
        String boundary = "----AiImage" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, Object> field : commonFields(provider, prompt, negativePrompt, options).entrySet()) {
            writeTextPart(body, boundary, field.getKey(), String.valueOf(field.getValue()));
        }
        for (ImageReference reference : references) {
            writeImagePart(body, boundary, reference);
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return requestBuilder(provider, endpoint(baseUri, "/images/edits"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
    }

    private HttpRequest.Builder requestBuilder(AiProviderConfigItem provider, URI endpoint) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(REQUEST_TIMEOUT);
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

    private ImageResult imageResult(HttpResponse<?> response, byte[] responseBody, ImageOptions options) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
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
        if (bytes.length > MAX_DECODED_BYTES) {
            throw new IllegalArgumentException("图片生成返回的图片数据过大");
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
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw new IllegalArgumentException("图片生成返回的图片像素超过限制");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IllegalArgumentException("图片生成返回的图片数据无效");
                }
                return new DecodedImage(mimeType(reader.getFormatName()), width, height);
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

    private void validateReferences(List<ImageReference> references) {
        if (references.size() > MAX_REFERENCE_COUNT) {
            throw new IllegalArgumentException("参考图数量不能超过 " + MAX_REFERENCE_COUNT + " 张");
        }
        long totalBytes = 0;
        for (ImageReference reference : references) {
            validateReference(reference);
            totalBytes += reference.bytes().length;
            if (totalBytes > MAX_TOTAL_REFERENCE_BYTES) {
                throw new IllegalArgumentException("参考图总大小不能超过 40MiB");
            }
        }
    }

    private void validateReference(ImageReference reference) {
        if (reference == null || !StringUtils.hasText(reference.filename()) || !StringUtils.hasText(reference.mimeType())
                || reference.bytes() == null || reference.bytes().length == 0) {
            throw new IllegalArgumentException("参考图数据无效");
        }
        if (reference.bytes().length > MAX_REFERENCE_BYTES) {
            throw new IllegalArgumentException("参考图过大，单张不能超过 10MiB");
        }
        String declaredMimeType = reference.mimeType().trim().toLowerCase(Locale.ROOT);
        if (!reference.mimeType().equals(declaredMimeType) || !SUPPORTED_IMAGE_MIME_TYPES.contains(declaredMimeType)) {
            throw new IllegalArgumentException("参考图 MIME 类型不支持");
        }
        DecodedImage image;
        try {
            image = decodeImage(reference.bytes());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalArgumentException("参考图数据无效");
        }
        if (!declaredMimeType.equals(image.mimeType())) {
            throw new IllegalArgumentException("参考图 MIME 类型与实际图片不匹配");
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

    private URI baseUri(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl == null ? "" : baseUrl.trim());
            if (!uri.isAbsolute() || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (Exception exception) {
            throw new IllegalArgumentException("图片生成服务地址无效");
        }
    }

    private URI endpoint(URI baseUri, String resourcePath) {
        String path = baseUri.getRawPath() == null ? "" : baseUri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String normalizedPath = path.toLowerCase(Locale.ROOT);
        if (normalizedPath.endsWith("/images/generations") || normalizedPath.endsWith("/images/edits")) {
            throw new IllegalArgumentException("图片生成服务地址不能包含 images 接口");
        }
        if (!normalizedPath.endsWith("/v1")) {
            path += "/v1";
        }
        return URI.create(baseUri.getScheme() + "://" + baseUri.getRawAuthority() + path + resourcePath);
    }

    private byte[] readBounded(HttpResponse<?> response, InputStream input) throws IOException {
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (contentLength > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("图片生成响应过大");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_RESPONSE_BYTES) {
                throw new IllegalArgumentException("图片生成响应过大");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
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
