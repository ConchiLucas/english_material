package com.aitaskcenter.service;

import com.aitaskcenter.dto.LocalCliConfigItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LocalCliGenerationService {
    private static final int MAX_DIAGNOSTIC_LENGTH = 1200;

    public String generate(LocalCliConfigItem cli, String systemPrompt, String userPrompt, int maxTokens) {
        if (cli == null || !cli.isEnabled()) {
            throw new IllegalArgumentException("默认本地 CLI 未启用");
        }
        Path stdout = null;
        Path stderr = null;
        Path finalOutput = null;
        Process process = null;
        try {
            stdout = Files.createTempFile("generation-cli-stdout-", ".log");
            stderr = Files.createTempFile("generation-cli-stderr-", ".log");
            finalOutput = Files.createTempFile("generation-cli-result-", ".txt");
            List<String> command = buildCommand(cli, finalOutput);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(resolveWorkingDirectory(cli).toFile())
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile());
            process = builder.start();
            String prompt = systemPrompt.trim()
                    + "\n\n用户任务：\n" + userPrompt.trim()
                    + "\n\n输出规模请控制在约 " + Math.max(1, maxTokens) + " tokens 以内。";
            process.getOutputStream().write(prompt.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();

            long timeout = cli.getTimeoutSeconds() == null || cli.getTimeoutSeconds() <= 0
                    ? Duration.ofMinutes(5).toSeconds()
                    : cli.getTimeoutSeconds();
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalArgumentException("本地 CLI 调用超时（" + timeout + " 秒）");
            }

            String standardOutput = read(stdout);
            String errorOutput = read(stderr);
            if (process.exitValue() != 0) {
                throw new IllegalArgumentException("本地 CLI 调用失败（退出码 " + process.exitValue() + "）："
                        + bounded(StringUtils.hasText(errorOutput) ? errorOutput : standardOutput));
            }
            String result = read(finalOutput);
            if (!StringUtils.hasText(result)) {
                result = standardOutput;
            }
            if (!StringUtils.hasText(result)) {
                throw new IllegalArgumentException("本地 CLI 返回内容为空");
            }
            return result.trim();
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("本地 CLI 调用失败：" + ex.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteQuietly(stdout);
            deleteQuietly(stderr);
            deleteQuietly(finalOutput);
        }
    }

    private List<String> buildCommand(LocalCliConfigItem cli, Path finalOutput) {
        String configured = require(cli.getCommand(), "默认本地 CLI 缺少命令路径");
        String commandName = Path.of(configured).getFileName().toString().toLowerCase(Locale.ROOT);
        boolean codex = "codex".equalsIgnoreCase(cli.getId()) || commandName.equals("codex");
        List<String> command = new ArrayList<>();
        command.add(resolveCommand(configured, commandName));
        if (cli.getDefaultArgs() != null) {
            cli.getDefaultArgs().stream().filter(StringUtils::hasText).map(String::trim).forEach(command::add);
        }
        if (codex) {
            if (command.stream().noneMatch("exec"::equals)) {
                command.add("exec");
            }
            command.add("--sandbox");
            command.add("read-only");
            command.add("--skip-git-repo-check");
            command.add("--output-last-message");
            command.add(finalOutput.toString());
            if (StringUtils.hasText(cli.getModel())) {
                command.add("--model");
                command.add(cli.getModel().trim());
            }
            if (StringUtils.hasText(cli.getReasoningEffort())) {
                command.add("-c");
                command.add("model_reasoning_effort=" + cli.getReasoningEffort().trim().toLowerCase(Locale.ROOT));
            }
            command.add("-");
        }
        return command;
    }

    private String resolveCommand(String configured, String commandName) {
        Path path = Path.of(configured);
        if (path.isAbsolute() && Files.isExecutable(path)) {
            return configured;
        }
        if ("codex".equals(commandName)) {
            return "codex";
        }
        return configured;
    }

    private Path resolveWorkingDirectory(LocalCliConfigItem cli) throws IOException {
        if (StringUtils.hasText(cli.getWorkingDirectory())) {
            Path configured = Path.of(cli.getWorkingDirectory().trim());
            if (Files.isDirectory(configured)) {
                return configured;
            }
        }
        Path containerWorkspace = Path.of("/workspace");
        return Files.isDirectory(containerWorkspace) ? containerWorkspace : Path.of(System.getProperty("java.io.tmpdir"));
    }

    private static String read(Path path) throws IOException {
        return path == null || !Files.exists(path) ? "" : Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String bounded(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= MAX_DIAGNOSTIC_LENGTH ? text : text.substring(0, MAX_DIAGNOSTIC_LENGTH) + "…";
    }

    private static String require(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
