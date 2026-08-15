package com.aitaskcenter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

    @Test
    void boundsFullDeploymentBaseImagePullsAndOnlyFallsBackToAnExistingLocalImage() throws Exception {
        String fullDeploy = Files.readString(Path.of("deploy/context-router/full/deploy.sh"));

        assertTrue(fullDeploy.contains("pull_image_with_timeout()"));
        assertTrue(fullDeploy.contains("${ENGLISH_MATERIAL_IMAGE_PULL_TIMEOUT:-120}"));
        assertTrue(fullDeploy.contains("kill -TERM \"$pull_pid\""));
        assertTrue(fullDeploy.contains("kill -KILL \"$pull_pid\""));
        assertTrue(fullDeploy.contains("wait \"$pull_pid\""));
        assertTrue(fullDeploy.contains("normalize_platform()"));
        assertTrue(fullDeploy.contains("image_cache_matches_platform()"));
        assertTrue(fullDeploy.contains("docker image inspect --format"));
        assertTrue(fullDeploy.contains("{{.Os}}/{{.Architecture}}"));
        assertTrue(fullDeploy.contains("{{if .Variant}}/{{.Variant}}{{end}}"));
        assertTrue(fullDeploy.contains("pull_image_with_timeout \"$JAVA_IMAGE\""));
        assertTrue(fullDeploy.contains("pull_image_with_timeout \"$NODE_IMAGE\""));
        assertTrue(fullDeploy.contains("registry_docker()"));
        assertTrue(fullDeploy.contains("DOCKER_CONFIG=\"$ANONYMOUS_DOCKER_CONFIG\""));
        assertTrue(fullDeploy.contains("DOCKER_HOST=\"$DOCKER_ENDPOINT\""));
        assertTrue(fullDeploy.contains("registry_docker build"));
    }

    @Test
    void failedPullUsesCacheWhenNormalizedPlatformMatches() throws Exception {
        PullResult result = runPullHelper("fail", "linux/aarch64", "linux/arm64");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("使用已存在的本地缓存镜像"));
        assertTrue(result.dockerLog().contains("config=/tmp/anonymous-docker-config"));
        assertTrue(result.dockerLog().contains("host=unix:///tmp/docker.sock"));
    }

    @Test
    void failedPullRejectsCacheWhenArchitectureDoesNotMatch() throws Exception {
        PullResult result = runPullHelper("fail", "linux/arm64", "linux/amd64");

        assertNotEquals(0, result.exitCode());
        assertTrue(result.output().contains("本地缓存镜像不存在或平台不匹配"));
    }

    @Test
    void timedOutPullTerminatesAndReapsBeforeUsingCompatibleCache() throws Exception {
        PullResult result = runPullHelper("timeout", "linux/arm64/v8", "linux/arm64");

        assertEquals(0, result.exitCode());
        assertTrue(result.elapsedMillis() < 5_000, result.output());
        assertTrue(result.dockerLog().contains("TERM"));
        assertTrue(result.output().contains("发送 KILL"));
        assertTrue(result.output().contains("使用已存在的本地缓存镜像"));
    }

    private PullResult runPullHelper(String pullMode, String targetPlatform, String cachePlatform)
            throws Exception {
        String fullDeploy = Files.readString(Path.of("deploy/context-router/full/deploy.sh"));
        int helperStart = fullDeploy.indexOf("pull_image_with_timeout() {");
        int helperEnd = fullDeploy.indexOf("remove_legacy_container() {");
        assertTrue(helperStart >= 0 && helperEnd > helperStart);
        String helperFunctions = fullDeploy.substring(helperStart, helperEnd);

        Path tempDir = Files.createTempDirectory("image-pull-test-");
        Path dockerLog = tempDir.resolve("docker.log");
        Path fakeDocker = tempDir.resolve("docker");
        Files.writeString(fakeDocker, """
                #!/bin/sh
                printf 'config=%s host=%s args=%s\\n' "$DOCKER_CONFIG" "$DOCKER_HOST" "$*" >> "$FAKE_DOCKER_LOG"
                if [ "$1" = "pull" ]; then
                  if [ "$FAKE_PULL_MODE" = "timeout" ]; then
                    trap 'printf "%s\\n" TERM >> "$FAKE_DOCKER_LOG"' TERM
                    while :; do :; done
                  fi
                  exit 7
                fi
                if [ "$1" = "image" ] && [ "$2" = "inspect" ]; then
                  printf '%s\\n' "$FAKE_CACHE_PLATFORM"
                  exit 0
                fi
                exit 9
                """);
        assertTrue(fakeDocker.toFile().setExecutable(true));

        Path harness = tempDir.resolve("harness.sh");
        Files.writeString(harness, """
                #!/bin/sh
                set -eu
                """ + helperFunctions + """
                DOCKER_PLATFORM="$TEST_DOCKER_PLATFORM"
                IMAGE_PULL_TIMEOUT=1
                IMAGE_PULL_TERMINATION_GRACE=1
                ANONYMOUS_DOCKER_CONFIG=/tmp/anonymous-docker-config
                DOCKER_ENDPOINT=unix:///tmp/docker.sock
                pull_image_with_timeout test/image:latest
                """);

        ProcessBuilder builder = new ProcessBuilder("sh", harness.toString());
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("PATH", tempDir + File.pathSeparator + environment.getOrDefault("PATH", ""));
        environment.put("FAKE_DOCKER_LOG", dockerLog.toString());
        environment.put("FAKE_PULL_MODE", pullMode);
        environment.put("FAKE_CACHE_PLATFORM", cachePlatform);
        environment.put("TEST_DOCKER_PLATFORM", targetPlatform);

        long startedAt = System.nanoTime();
        Process process = builder.start();
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        assertTrue(completed, "pull helper did not finish within test bound");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        String output = new String(process.getInputStream().readAllBytes());
        String log = Files.exists(dockerLog) ? Files.readString(dockerLog) : "";
        return new PullResult(process.exitValue(), elapsedMillis, output, log);
    }

    private record PullResult(int exitCode, long elapsedMillis, String output, String dockerLog) {}
}
