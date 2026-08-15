package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aitaskcenter.dto.AiProviderConfigItem;
import com.aitaskcenter.service.AiImageGenerationService.ImageReference;
import com.aitaskcenter.service.AiImageGenerationService.ImageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiImageGenerationServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private final List<CapturedRequest> requests = new ArrayList<>();
    private byte[] generatedImage;

    @BeforeEach
    void setUp() throws Exception {
        generatedImage = png(3, 2, Color.BLUE);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsJsonToGenerationsAndDecodesB64Image() throws Exception {
        AiImageGenerationService service = service();

        ImageResult result = service.generate(provider(baseUrl()), "a blue square", "no text", 3, 2, List.of());

        CapturedRequest request = requests.get(0);
        assertEquals("/v1/images/generations", request.path());
        assertEquals("POST", request.method());
        assertEquals("Bearer test-api-key", request.authorization());
        assertTrue(request.contentType().startsWith("application/json"));
        JsonNode body = objectMapper.readTree(request.body());
        assertEquals("image-model", body.path("model").asText());
        assertEquals("a blue square", body.path("prompt").asText());
        assertEquals("no text", body.path("negative_prompt").asText());
        assertEquals("b64_json", body.path("response_format").asText());
        assertEquals("high", body.path("quality").asText());
        assertEquals("3x2", body.path("size").asText());
        assertArrayEquals(generatedImage, result.bytes());
        assertEquals("image/png", result.mimeType());
        assertEquals(3, result.width());
        assertEquals(2, result.height());
        assertEquals("provider-request", result.providerRequestId());
        assertFalse(result.metadata().toString().contains("test-api-key"));
    }

    @Test
    void postsTwoReferenceImagesAsImagePartsToEdits() {
        AiImageGenerationService service = service();
        ImageReference first = new ImageReference("first.png", "image/png", png(1, 1, Color.RED));
        ImageReference second = new ImageReference("second.png", "image/png", png(1, 1, Color.GREEN));

        service.generate(provider(baseUrl() + "/v1"), "combine", "", 3, 2, List.of(first, second));

        CapturedRequest request = requests.get(0);
        assertEquals("/v1/images/edits", request.path());
        assertTrue(request.contentType().startsWith("multipart/form-data; boundary="));
        String body = new String(request.bodyBytes(), StandardCharsets.ISO_8859_1);
        assertEquals(2, occurrences(body, "name=\"image\""));
        assertTrue(body.contains("filename=\"first.png\""));
        assertTrue(body.contains("filename=\"second.png\""));
        assertTrue(body.contains("name=\"response_format\""));
    }

    @Test
    void usesSpecifiedDefaultSizeWhenDimensionsAreNotPositive() throws Exception {
        service().generate(provider(baseUrl()), "a", "", 0, 0, List.of());

        JsonNode body = objectMapper.readTree(requests.get(0).body());
        assertEquals("1536x864", body.path("size").asText());
    }

    @Test
    void rejectsMissingImageDataWithoutLeakingApiKey() {
        AiImageGenerationService service = service();
        server.removeContext("/");
        server.createContext("/", exchange -> write(exchange, 200, "{\"data\":[{}]}"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.generate(provider(baseUrl()), "a", "", 3, 2, List.of()));

        assertTrue(exception.getMessage().contains("图片数据为空"));
        assertFalse(exception.getMessage().contains("test-api-key"));
    }

    @Test
    void reportsBoundedNon2xxErrorWithoutResponseOrApiKey() {
        AiImageGenerationService service = service();
        server.removeContext("/");
        server.createContext("/", exchange -> write(exchange, 429,
                "provider response including test-api-key that must not be exposed"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.generate(provider(baseUrl()), "a", "", 3, 2, List.of()));

        assertEquals("图片生成调用失败（HTTP 429）", exception.getMessage());
        assertFalse(exception.getMessage().contains("test-api-key"));
    }

    @Test
    void requiresImageGenerationCapability() {
        AiProviderConfigItem provider = provider(baseUrl());
        provider.setCapabilities(List.of("TEXT_GENERATION"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service().generate(provider, "a", "", 3, 2, List.of()));

        assertTrue(exception.getMessage().contains("不支持图片生成"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void requiresImageReferenceCapabilityForEdits() {
        AiProviderConfigItem provider = provider(baseUrl());
        provider.setCapabilities(List.of("IMAGE_GENERATION"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service().generate(provider, "a", "", 3, 2,
                        List.of(new ImageReference("reference.png", "image/png", png(1, 1, Color.RED)))));

        assertTrue(exception.getMessage().contains("不支持参考图"));
        assertTrue(requests.isEmpty());
    }

    private AiImageGenerationService service() {
        return new AiImageGenerationService(objectMapper, HttpClient.newHttpClient());
    }

    private AiProviderConfigItem provider(String baseUrl) {
        AiProviderConfigItem provider = new AiProviderConfigItem();
        provider.setBaseUrl(baseUrl);
        provider.setApiKey("test-api-key");
        provider.setModel("image-model");
        provider.setCapabilities(List.of("IMAGE_GENERATION", "IMAGE_REFERENCE"));
        provider.setOptions(Map.of());
        return provider;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange) throws IOException {
        requests.add(new CapturedRequest(exchange));
        String body = "{\"id\":\"provider-request\",\"data\":[{\"b64_json\":\""
                + Base64.getEncoder().encodeToString(generatedImage) + "\"}]}";
        write(exchange, 200, body);
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private byte[] png(int width, int height, Color color) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, color.getRGB());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int occurrences(String text, String search) {
        return text.split(java.util.regex.Pattern.quote(search), -1).length - 1;
    }

    private record CapturedRequest(String method, String path, String contentType, String authorization, byte[] bodyBytes) {
        CapturedRequest(HttpExchange exchange) throws IOException {
            this(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestBody().readAllBytes());
        }

        String body() {
            return new String(bodyBytes, StandardCharsets.UTF_8);
        }
    }
}
