package com.aitaskcenter.service;

import com.aitaskcenter.dto.AiProviderConfigItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AiImageGenerationService {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 1_000L;
    private static final int DEFAULT_WIDTH = 1536;
    private static final int DEFAULT_HEIGHT = 864;
    private static final int MAX_REFERENCE_COUNT = 8;
    private static final int GROK_MAX_EDIT_REFERENCES = 3;
    private static final long MAX_REFERENCE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_TOTAL_REFERENCE_BYTES = 40L * 1024 * 1024;
    private static final long MAX_RESPONSE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_DECODED_BYTES = 48L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;
    private static final Set<String> SUPPORTED_IMAGE_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/bmp");
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 502, 503, 504);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Sleeper sleeper;

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Autowired
    public AiImageGenerationService(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    AiImageGenerationService(ObjectMapper objectMapper, HttpClient httpClient) {
        this(objectMapper, httpClient, Thread::sleep);
    }

    AiImageGenerationService(ObjectMapper objectMapper, HttpClient httpClient, Sleeper sleeper) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.sleeper = sleeper == null ? Thread::sleep : sleeper;
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
        IllegalArgumentException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return generateOnce(provider, prompt, negativePrompt, options, safeReferences, baseUri);
            } catch (IllegalArgumentException exception) {
                lastFailure = exception;
                if (!isRetryable(exception) || attempt >= MAX_ATTEMPTS) {
                    throw exception;
                }
                sleepBeforeRetry(attempt);
            }
        }
        throw lastFailure == null
                ? new IllegalArgumentException("图片生成调用失败，请稍后重试")
                : lastFailure;
    }

    private ImageResult generateOnce(AiProviderConfigItem provider, String prompt, String negativePrompt,
                                     ImageOptions options, List<ImageReference> safeReferences, URI baseUri) {
        try {
            HttpRequest request;
            if (safeReferences.isEmpty()) {
                request = jsonRequest(provider, prompt, negativePrompt, options, baseUri);
            } else if (isGrokImagine(provider)) {
                request = grokEditJsonRequest(provider, prompt, negativePrompt, options, safeReferences, baseUri);
            } else {
                request = multipartRequest(provider, prompt, negativePrompt, options, safeReferences, baseUri);
            }
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream responseBody = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalArgumentException("图片生成调用失败（HTTP " + response.statusCode() + "）");
                }
                return imageResult(response, readBounded(response, responseBody), options);
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("图片生成调用失败，请稍后重试（InterruptedException）", exception);
        } catch (Exception exception) {
            String detail = exception.getClass().getSimpleName();
            if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
                detail = detail + ": " + exception.getMessage().replaceAll("[\\r\\n\\t]+", " ").trim();
                if (detail.length() > 180) {
                    detail = detail.substring(0, 180);
                }
            }
            throw new IllegalArgumentException("图片生成调用失败，请稍后重试（" + detail + "）", exception);
        }
    }

    private boolean isRetryable(IllegalArgumentException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        for (int status : RETRYABLE_STATUS_CODES) {
            if (message.contains("HTTP " + status)) {
                return true;
            }
        }
        if (message.contains("请稍后重试")) {
            return true;
        }
        Throwable cause = exception.getCause();
        while (cause != null) {
            if (cause instanceof IOException || cause instanceof InterruptedException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            sleeper.sleep(RETRY_BACKOFF_MILLIS * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("图片生成调用失败，请稍后重试（InterruptedException）", exception);
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
        for (int index = 0; index < references.size(); index++) {
            writeImagePart(body, boundary, index == 0 ? "image" : "image" + index, references.get(index));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return requestBuilder(provider, endpoint(baseUri, "/images/edits"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
    }

    private HttpRequest grokEditJsonRequest(AiProviderConfigItem provider, String prompt, String negativePrompt,
                                            ImageOptions options, List<ImageReference> references, URI baseUri)
            throws Exception {
        Map<String, Object> body = commonFields(provider, prompt, negativePrompt, options);
        List<ImageReference> effectiveReferences = references.size() <= GROK_MAX_EDIT_REFERENCES
                ? references
                : List.of(compositeReferenceBoard(references, options.size()));
        List<Map<String, String>> encodedReferences = effectiveReferences.stream().map(this::grokReference).toList();
        if (encodedReferences.size() == 1) {
            body.put("image", encodedReferences.get(0));
        } else {
            body.put("images", encodedReferences);
        }
        return requestBuilder(provider, endpoint(baseUri, "/images/edits"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
    }

    private Map<String, String> grokReference(ImageReference reference) {
        return Map.of(
                "type", "image_url",
                "url", "data:" + reference.mimeType() + ";base64,"
                        + Base64.getEncoder().encodeToString(reference.bytes()));
    }

    private ImageReference compositeReferenceBoard(List<ImageReference> references, String requestedSize) {
        Dimensions dimensions = dimensions(requestedSize);
        int columns = (int) Math.ceil(Math.sqrt(references.size()));
        int rows = (int) Math.ceil((double) references.size() / columns);
        BufferedImage board = new BufferedImage(dimensions.width(), dimensions.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = board.createGraphics();
        try {
            graphics.setColor(new Color(245, 245, 245));
            graphics.fillRect(0, 0, board.getWidth(), board.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            for (int index = 0; index < references.size(); index++) {
                BufferedImage source = decodeReferenceImage(references.get(index));
                drawLetterboxed(graphics, source, index % columns, index / columns,
                        columns, rows, board.getWidth(), board.getHeight());
            }
        } finally {
            graphics.dispose();
        }
        byte[] encoded = encodePng(board);
        if (encoded.length > MAX_REFERENCE_BYTES) {
            throw new IllegalArgumentException("Grok 参考图板过大");
        }
        return new ImageReference("grok-reference-board.png", "image/png", encoded);
    }

    private Dimensions dimensions(String size) {
        try {
            int separator = size.indexOf('x');
            int width = Integer.parseInt(size.substring(0, separator));
            int height = Integer.parseInt(size.substring(separator + 1));
            if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
                throw new IllegalArgumentException();
            }
            return new Dimensions(width, height);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("图片尺寸配置无效");
        }
    }

    private BufferedImage decodeReferenceImage(ImageReference reference) {
        try {
            return decodeImage(reference.bytes()).image();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalArgumentException("参考图数据无效");
        }
    }

    private void drawLetterboxed(Graphics2D graphics, BufferedImage source, int column, int row,
                                 int columns, int rows, int boardWidth, int boardHeight) {
        int left = column * boardWidth / columns;
        int right = (column + 1) * boardWidth / columns;
        int top = row * boardHeight / rows;
        int bottom = (row + 1) * boardHeight / rows;
        double scale = Math.min((double) (right - left) / source.getWidth(),
                (double) (bottom - top) / source.getHeight());
        int width = Math.max(1, (int) Math.floor(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.floor(source.getHeight() * scale));
        int x = left + (right - left - width) / 2;
        int y = top + (bottom - top - height) / 2;
        graphics.drawImage(source, x, y, width, height, null);
    }

    private byte[] encodePng(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output) || output.size() > MAX_DECODED_BYTES) {
                throw new IllegalArgumentException("Grok 参考图板生成失败");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Grok 参考图板生成失败");
        }
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
        boolean grokImagine = isGrokImagine(provider);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.getModel());
        String effectivePrompt = prompt == null ? "" : prompt;
        if (grokImagine && StringUtils.hasText(negativePrompt)) {
            effectivePrompt += "\n\nAvoid: " + negativePrompt.trim();
        }
        body.put("prompt", effectivePrompt);
        if (!grokImagine && StringUtils.hasText(negativePrompt)) {
            body.put("negative_prompt", negativePrompt);
        }
        body.put("response_format", options.responseFormat());
        if (grokImagine) {
            body.put("aspect_ratio", aspectRatio(options.size()));
        } else {
            body.put("quality", options.quality());
            body.put("size", options.size());
        }
        return body;
    }

    private boolean isGrokImagine(AiProviderConfigItem provider) {
        return provider != null
                && StringUtils.hasText(provider.getModel())
                && provider.getModel().trim().toLowerCase(Locale.ROOT).startsWith("grok-imagine-image");
    }

    private String aspectRatio(String size) {
        try {
            int separator = size.indexOf('x');
            int width = Integer.parseInt(size.substring(0, separator));
            int height = Integer.parseInt(size.substring(separator + 1));
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException();
            }
            int divisor = greatestCommonDivisor(width, height);
            return width / divisor + ":" + height / divisor;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("图片尺寸配置无效");
        }
    }

    private int greatestCommonDivisor(int first, int second) {
        int left = first;
        int right = second;
        while (right != 0) {
            int remainder = left % right;
            left = right;
            right = remainder;
        }
        return left;
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
        ImageResult source = new ImageResult(bytes, image.mimeType(), image.width(), image.height(),
                root.path("id").asText(response.headers().firstValue("x-request-id").orElse("")), metadata);
        return normalizeDimensions(source, image.image(), options.size());
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
                return new DecodedImage(mimeType(reader.getFormatName()), width, height, image);
            } finally {
                reader.dispose();
            }
        }
    }

    private void writeTextPart(ByteArrayOutputStream body, String boundary, String name, String value) throws IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private void writeImagePart(ByteArrayOutputStream body, String boundary, String fieldName,
                                ImageReference reference) throws IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fieldName
                + "\"; filename=\""
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

    private ImageResult normalizeDimensions(ImageResult source, BufferedImage decoded, String requestedSize) {
        int separator = requestedSize.indexOf('x');
        try {
            int width = Integer.parseInt(requestedSize.substring(0, separator));
            int height = Integer.parseInt(requestedSize.substring(separator + 1));
            if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
                throw new IllegalArgumentException();
            }
            if (source.width() == width && source.height() == height) {
                return source;
            }

            double targetRatio = (double) width / height;
            int cropWidth = decoded.getWidth();
            int cropHeight = decoded.getHeight();
            if ((double) cropWidth / cropHeight > targetRatio) {
                cropWidth = Math.max(1, (int) Math.round(cropHeight * targetRatio));
            } else {
                cropHeight = Math.max(1, (int) Math.round(cropWidth / targetRatio));
            }
            int cropX = (decoded.getWidth() - cropWidth) / 2;
            int cropY = (decoded.getHeight() - cropHeight) / 2;
            BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = output.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(decoded, 0, 0, width, height,
                        cropX, cropY, cropX + cropWidth, cropY + cropHeight, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!ImageIO.write(output, "png", encoded) || encoded.size() > MAX_DECODED_BYTES) {
                throw new IllegalArgumentException();
            }
            return new ImageResult(encoded.toByteArray(), "image/png", width, height,
                    source.providerRequestId(), source.metadata());
        } catch (RuntimeException | IOException exception) {
            throw new IllegalArgumentException("图片尺寸归一化失败");
        }
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

    private record Dimensions(int width, int height) {
    }

    private record DecodedImage(String mimeType, int width, int height, BufferedImage image) {
    }

    public record ImageReference(String filename, String mimeType, byte[] bytes) {
    }

    public record ImageResult(byte[] bytes, String mimeType, int width, int height,
                              String providerRequestId, Map<String, Object> metadata) {
    }
}
