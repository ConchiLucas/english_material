package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aitaskcenter.dto.LocalCliConfigItem;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalCliGenerationServiceTest {
    @Test
    void sendsAgentPromptToConfiguredCliThroughStandardInput() {
        LocalCliConfigItem cli = new LocalCliConfigItem();
        cli.setId("test-cli");
        cli.setCommand("/bin/cat");
        cli.setDefaultArgs(List.of());
        cli.setWorkingDirectory(System.getProperty("java.io.tmpdir"));
        cli.setTimeoutSeconds(5);

        String output = new LocalCliGenerationService().generate(
                cli, "你是测试 Agent。", "只返回 JSON。", 128);

        assertTrue(output.contains("你是测试 Agent。"));
        assertTrue(output.contains("只返回 JSON。"));
    }
}
