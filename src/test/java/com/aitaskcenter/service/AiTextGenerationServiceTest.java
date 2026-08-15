package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aitaskcenter.dto.AiProviderConfigItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiTextGenerationServiceTest {
    private HttpServer server;
    private final AtomicReference<byte[]> response = new AtomicReference<>();
    private final AtomicBoolean chunked = new AtomicBoolean();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void readsNormalResponseAndPreservesUsage() {
        response.set(("{\"choices\":[{\"message\":{\"content\":\"hello story\"}}],"
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8,\"total_tokens\":20}}")
                .getBytes(StandardCharsets.UTF_8));

        var result = new AiTextGenerationService(new ObjectMapper())
                .generateWithUsage(provider(), "system", "user", 0.2, 100);

        assertEquals("hello story", result.text());
        assertEquals(12, result.inputTokens());
        assertEquals(8, result.outputTokens());
        assertEquals(20, result.totalTokens());
    }

    @Test
    void rejectsResponseOverTwoMiBWithoutRetainingOrReportingItsBody() {
        String secret = "secret-at-the-end";
        String oversized = "{\"choices\":[{\"message\":{\"content\":\""
                + "x".repeat(2 * 1024 * 1024) + secret + "\"}}]}";
        response.set(oversized.getBytes(StandardCharsets.UTF_8));
        chunked.set(true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AiTextGenerationService(new ObjectMapper())
                        .generateWithUsage(provider(), "system", "user", 0.2, 100));

        assertEquals("AI 响应超过最大长度", error.getMessage());
        assertFalse(error.getMessage().contains(secret));
    }

    @Test
    void saturatesTheEstimatedTotalWhenProviderOmitsIt() {
        response.set(("{\"choices\":[{\"message\":{\"content\":\"hello story\"}}],"
                + "\"usage\":{\"prompt_tokens\":" + Long.MAX_VALUE
                + ",\"completion_tokens\":" + Long.MAX_VALUE + "}}")
                .getBytes(StandardCharsets.UTF_8));

        var result = new AiTextGenerationService(new ObjectMapper())
                .generateWithUsage(provider(), "system", "user", 0.2, 100);

        assertEquals(Long.MAX_VALUE, result.totalTokens());
    }

    private void respond(HttpExchange exchange) throws IOException {
        byte[] body = response.get();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, chunked.get() ? 0 : body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private AiProviderConfigItem provider() {
        AiProviderConfigItem provider = new AiProviderConfigItem();
        provider.setId("text-provider");
        provider.setType("openai-compatible");
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        provider.setApiKey("test-key");
        provider.setModel("text-model");
        provider.setCapabilities(List.of("TEXT_GENERATION"));
        provider.setEnabled(true);
        return provider;
    }
}
