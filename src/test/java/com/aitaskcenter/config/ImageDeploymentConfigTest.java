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
            assertTrue(!compose.contains("IMAGE_STORY_ALLOW_PORTABLE_STORAGE: true"));
            assertTrue(compose.contains("english-material-image-story:/app/runtime/image-story"));
            assertTrue(compose.contains("english-material-image-story:"));
            assertTrue(compose.contains("image-story-volume-init:"));
            assertTrue(compose.contains("user: \"0:0\""));
            assertTrue(compose.contains("read_only: true"));
            assertTrue(compose.contains("network_mode: none"));
            assertTrue(compose.contains("no-new-privileges:true"));
            assertTrue(compose.contains("cap_drop:"));
            assertTrue(compose.contains("- ALL"));
            assertTrue(compose.contains("- CHOWN"));
            assertTrue(compose.contains("- FOWNER"));
            assertTrue(compose.contains("- DAC_OVERRIDE"));
            assertTrue(compose.contains("IMAGE_STORY_APP_UID: ${IMAGE_STORY_APP_UID:?Set IMAGE_STORY_APP_UID"));
            assertTrue(compose.contains("condition: service_completed_successfully"));
        }
        String fastDeploy = Files.readString(Path.of("deploy/context-router/fast/deploy.sh"));
        assertTrue(fastDeploy.contains("em_image_label \"$BASE_IMAGE\" \"$EM_LABEL_APP_UID\""));
        assertTrue(fastDeploy.contains("export IMAGE_STORY_APP_UID"));
        String fullDeploy = Files.readString(Path.of("deploy/context-router/full/deploy.sh"));
        assertTrue(fullDeploy.contains("export IMAGE_STORY_APP_UID=\"$APP_UID\""));
        String dockerfile = Files.readString(Path.of("deploy/context-router/full/Dockerfile.base"));
        assertTrue(dockerfile.contains("/app/runtime/image-story"));
        assertTrue(dockerfile.contains("chown -R app:app /app"));
        String start = Files.readString(Path.of("scripts/start-dev.sh"));
        assertTrue(start.contains("IMAGE_STORY_STORAGE_ROOT"));
        assertTrue(start.contains("IMAGE_STORY_STORAGE_ROOT=\"$IMAGE_STORY_STORAGE_ROOT\""));
        assertTrue(start.contains("IMAGE_STORY_ALLOW_PORTABLE_STORAGE=true"));
        String application = Files.readString(Path.of("src/main/resources/application.yml"));
        assertTrue(application.contains("allow-portable-storage: ${IMAGE_STORY_ALLOW_PORTABLE_STORAGE:false}"));
        String environmentTemplate = Files.readString(Path.of(".env.local.example"));
        assertTrue(environmentTemplate.contains("IMAGE_STORY_ALLOW_PORTABLE_STORAGE=false"));
    }
}
