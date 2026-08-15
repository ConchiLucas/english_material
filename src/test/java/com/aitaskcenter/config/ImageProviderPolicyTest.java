package com.aitaskcenter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aitaskcenter.dto.AiProviderConfigItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ImageProviderPolicyTest {

    @Test
    void acceptsAndNormalizesTheOnlyExecutableImageProviderShape() {
        AiProviderConfigItem provider = provider();
        provider.setId("  image-provider  ");
        provider.setModel("  image-model  ");
        provider.setType("  OPENAI-COMPATIBLE  ");
        provider.setBaseUrl("  https://provider.invalid/proxy/v1  ");
        provider.setCapabilities(List.of(" image_generation ", "Image_Reference"));
        provider.setOptions(Map.of(
                "responseFormat", " B64_JSON ",
                "quality", " HIGH ",
                "size", " 1536x864 "));

        Map<String, Object> normalized = ImageProviderPolicy.requireExecutable(provider);

        assertEquals(Map.of(
                "responseFormat", "b64_json",
                "quality", "high",
                "size", "1536x864"), normalized);
        assertTrue(ImageProviderPolicy.isExecutable(provider));
    }

    @Test
    void acceptsNullOrEmptyOptions() {
        AiProviderConfigItem withNull = provider();
        withNull.setOptions(null);
        assertEquals(Map.of(), ImageProviderPolicy.requireExecutable(withNull));

        AiProviderConfigItem withEmpty = provider();
        withEmpty.setOptions(Map.of());
        assertEquals(Map.of(), ImageProviderPolicy.requireExecutable(withEmpty));
    }

    @Test
    void rejectsProvidersThatCannotReachTheSupportedImageAdapter() {
        List<Consumer<AiProviderConfigItem>> invalid = List.of(
                value -> value.setEnabled(false),
                value -> value.setType("anthropic-compatible"),
                value -> value.setCapabilities(List.of("IMAGE_GENERATION")),
                value -> value.setId("   "),
                value -> value.setModel("   "),
                value -> value.setBaseUrl("   "),
                value -> value.setBaseUrl("/relative/v1"),
                value -> value.setBaseUrl("ftp://provider.invalid/v1"),
                value -> value.setBaseUrl("https:///v1"),
                value -> value.setBaseUrl("https://user:hidden-url-secret@provider.invalid/v1"),
                value -> value.setBaseUrl("https://provider.invalid/v1?token=hidden-url-secret"),
                value -> value.setBaseUrl("https://provider.invalid/v1#hidden-url-secret"),
                value -> value.setBaseUrl("https://provider.invalid/v1/images/generations"),
                value -> value.setBaseUrl("https://provider.invalid/v1/images/edits/"));

        for (Consumer<AiProviderConfigItem> mutation : invalid) {
            AiProviderConfigItem provider = provider();
            mutation.accept(provider);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> ImageProviderPolicy.requireExecutable(provider));
            assertFalse(error.getMessage().contains("hidden-url-secret"));
            assertFalse(error.getMessage().contains("hidden-api-key"));
            assertFalse(ImageProviderPolicy.isExecutable(provider));
        }
    }

    @Test
    void rejectsUnknownNestedOrUnsupportedImageOptionsWithoutEchoingValues() {
        List<Map<String, Object>> invalid = List.of(
                Map.of("unknown", "hidden-option-secret"),
                Map.of("responseFormat", "url"),
                Map.of("quality", "ultra"),
                Map.of("size", "1024x1024"),
                Map.of("quality", "x".repeat(65)),
                Map.of("quality", Map.of("secret", "hidden-option-secret")),
                Map.of("quality", "   "));
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("quality", null);

        for (Map<String, Object> options : concat(invalid, withNull)) {
            AiProviderConfigItem provider = provider();
            provider.setOptions(options);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> ImageProviderPolicy.requireExecutable(provider));
            assertFalse(error.getMessage().contains("hidden-option-secret"));
            assertFalse(error.getMessage().contains("hidden-api-key"));
            assertFalse(ImageProviderPolicy.isExecutable(provider));
        }
    }

    private static List<Map<String, Object>> concat(List<Map<String, Object>> values,
                                                     Map<String, Object> last) {
        java.util.ArrayList<Map<String, Object>> result = new java.util.ArrayList<>(values);
        result.add(last);
        return result;
    }

    private static AiProviderConfigItem provider() {
        AiProviderConfigItem value = new AiProviderConfigItem();
        value.setId("image-provider");
        value.setLabel("Image Provider");
        value.setType("openai-compatible");
        value.setBaseUrl("https://provider.invalid/v1");
        value.setApiKey("hidden-api-key");
        value.setModel("image-model");
        value.setCapabilities(List.of("IMAGE_GENERATION", "IMAGE_REFERENCE"));
        value.setEnabled(true);
        return value;
    }
}
