package com.aitaskcenter.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ImageDeploymentConfigTest {
    @Test
    void persistsImageStorageForFastFullAndHostDevelopment() throws Exception {
        for (String mode : new String[] {"fast", "full"}) {
            String compose = Files.readString(Path.of("deploy/context-router", mode, "compose.yml"));
            assertTrue(compose.contains("IMAGE_STORY_STORAGE_ROOT: /app/runtime/image-story"));
            assertTrue(compose.contains("english-material-image-story:/app/runtime/image-story"));
            assertTrue(compose.contains("english-material-image-story:"));
        }
        String start = Files.readString(Path.of("scripts/start-dev.sh"));
        assertTrue(start.contains("IMAGE_STORY_STORAGE_ROOT"));
        assertTrue(start.contains("IMAGE_STORY_STORAGE_ROOT=\"$IMAGE_STORY_STORAGE_ROOT\""));
    }
}
