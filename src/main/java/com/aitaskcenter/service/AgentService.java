package com.aitaskcenter.service;

import com.aitaskcenter.dto.AgentDefinitionRequest;
import com.aitaskcenter.dto.AgentTestResult;
import com.aitaskcenter.dto.LocalCliConfigItem;
import com.aitaskcenter.model.AgentDefinition;
import com.aitaskcenter.model.AgentTestRun;
import com.aitaskcenter.repository.AgentDefinitionRepository;
import com.aitaskcenter.repository.AgentTestRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AgentService {
    private static final Set<String> CATEGORIES = Set.of("planning", "creation", "review", "visual", "learning");
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([a-zA-Z0-9_.-]+)}}");
    private final AgentDefinitionRepository definitionRepository;
    private final AgentTestRunRepository runRepository;
    private final AiConfigService aiConfigService;
    private final LocalCliGenerationService generationService;
    private final AgentSchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;

    public AgentService(AgentDefinitionRepository definitionRepository,
                        AgentTestRunRepository runRepository,
                        AiConfigService aiConfigService,
                        LocalCliGenerationService generationService,
                        AgentSchemaValidator schemaValidator,
                        ObjectMapper objectMapper) {
        this.definitionRepository = definitionRepository;
        this.runRepository = runRepository;
        this.aiConfigService = aiConfigService;
        this.generationService = generationService;
        this.schemaValidator = schemaValidator;
        this.objectMapper = objectMapper;
    }

    public List<AgentDefinition> list() {
        return definitionRepository.findAllByOrderBySortOrderAscNameAsc();
    }

    public List<AgentTestResult> listRuns() {
        return runRepository.findTop100ByOrderByCreatedAtDesc().stream().map(this::toResult).toList();
    }

    @Transactional
    public AgentDefinition create(AgentDefinitionRequest request) {
        AgentDefinition definition = new AgentDefinition();
        apply(definition, request);
        return definitionRepository.save(definition);
    }

    @Transactional
    public AgentDefinition update(Long id, AgentDefinitionRequest request) {
        AgentDefinition definition = definitionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Agent 不存在"));
        apply(definition, request);
        return definitionRepository.save(definition);
    }

    @Transactional
    public AgentTestResult test(Long id, String inputJson) {
        AgentDefinition definition = definitionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Agent 不存在"));
        long started = System.currentTimeMillis();
        AgentTestRun run = baseRun(definition, inputJson);
        List<String> issues = new ArrayList<>();
        Map<String, Integer> dimensions = new LinkedHashMap<>();
        String output = "";
        try {
            JsonNode input = schemaValidator.parseJson(inputJson, "测试输入");
            JsonNode inputSchema = schemaValidator.parseJson(definition.getInputSchema(), "输入 Schema");
            List<String> inputErrors = schemaValidator.validate(input, inputSchema);
            if (!inputErrors.isEmpty()) {
                issues.addAll(inputErrors);
                finish(run, "FAILED", false, null, dimensions, issues, output,
                        "测试输入未通过 Schema 校验", started);
                return toResult(runRepository.save(run));
            }

            LocalCliConfigItem cli = aiConfigService.getDefaultLocalCliForExecution();
            run.setAiProviderId(cli.getId());
            String systemPrompt = buildSystemPrompt(definition);
            String userPrompt = renderPrompt(definition.getPromptTemplate(), input, inputJson);
            JsonNode outputSchema = schemaValidator.parseJson(definition.getOutputSchema(), "输出 Schema");
            List<String> outputErrors = List.of();
            int attempts = Math.max(1, definition.getRetryLimit() + 1);
            for (int attempt = 0; attempt < attempts; attempt++) {
                output = generationService.generate(cli, systemPrompt, userPrompt, definition.getMaxTokens());
                try {
                    JsonNode outputJson = schemaValidator.parseJson(output, "Agent 输出");
                    outputErrors = schemaValidator.validate(outputJson, outputSchema);
                } catch (IllegalArgumentException ex) {
                    outputErrors = List.of(ex.getMessage());
                }
                if (outputErrors.isEmpty()) break;
            }
            if (!outputErrors.isEmpty()) {
                issues.addAll(outputErrors);
                finish(run, "FAILED", false, null, dimensions, issues, output,
                        "Agent 输出未通过 Schema 校验", started);
                return toResult(runRepository.save(run));
            }

            Evaluation evaluation = evaluate(definition, cli, inputJson, output);
            dimensions.putAll(evaluation.dimensions());
            issues.addAll(evaluation.issues());
            Integer score = evaluation.overallScore();
            String status = score == null ? "NEEDS_REVIEW" : score >= 85 ? "PASSED" : score >= 70 ? "NEEDS_REVISION" : "FAILED";
            String error = score == null ? "结构校验通过，但自动评分未完成" : "";
            finish(run, status, true, score, dimensions, issues, output, error, started);
        } catch (Exception ex) {
            issues.add(ex.getMessage());
            finish(run, "FAILED", false, null, dimensions, issues, output, ex.getMessage(), started);
        }
        return toResult(runRepository.save(run));
    }

    private void apply(AgentDefinition definition, AgentDefinitionRequest request) {
        if (request == null) throw new IllegalArgumentException("Agent 配置不能为空");
        String key = require(request.agentKey(), "请填写 Agent Key");
        definitionRepository.findByAgentKey(key).ifPresent(existing -> {
            if (definition.getId() == null || !existing.getId().equals(definition.getId())) {
                throw new IllegalArgumentException("Agent Key「" + key + "」已存在");
            }
        });
        String category = require(request.category(), "请选择 Agent 分类");
        if (!CATEGORIES.contains(category)) throw new IllegalArgumentException("Agent 分类不支持");
        definition.setAgentKey(key);
        definition.setName(require(request.name(), "请填写 Agent 名称"));
        definition.setCategory(category);
        definition.setDescription(clean(request.description()));
        definition.setAiProviderId(aiConfigService.getDefaultLocalCliForExecution().getId());
        definition.setSystemPrompt(require(request.systemPrompt(), "请填写 System Prompt"));
        definition.setPromptTemplate(require(request.promptTemplate(), "请填写任务提示词模板"));
        definition.setInputSchema(schemaValidator.normalizeSchema(request.inputSchema(), "输入 Schema"));
        definition.setOutputSchema(schemaValidator.normalizeSchema(request.outputSchema(), "输出 Schema"));
        definition.setHardRules(clean(request.hardRules()));
        definition.setEvaluationRubric(clean(request.evaluationRubric()));
        double temperature = request.temperature() == null ? 0.4 : request.temperature();
        if (temperature < 0 || temperature > 2) throw new IllegalArgumentException("Temperature 必须在 0–2 之间");
        definition.setTemperature(temperature);
        definition.setMaxTokens(request.maxTokens() == null || request.maxTokens() <= 0 ? 4096 : Math.min(request.maxTokens(), 32768));
        definition.setRetryLimit(request.retryLimit() == null ? 1 : Math.max(0, Math.min(request.retryLimit(), 3)));
        definition.setSortOrder(request.sortOrder() == null ? 100 : request.sortOrder());
    }

    private AgentTestRun baseRun(AgentDefinition definition, String inputJson) {
        AgentTestRun run = new AgentTestRun();
        run.setAgentId(definition.getId());
        run.setAgentKey(definition.getAgentKey());
        run.setAgentName(definition.getName());
        run.setAiProviderId(clean(definition.getAiProviderId()));
        run.setStatus("RUNNING");
        run.setInputJson(inputJson == null ? "" : inputJson);
        run.setOutputText("");
        run.setSchemaValid(false);
        run.setDimensionScores("{}");
        run.setIssues("[]");
        run.setDurationMs(0L);
        run.setErrorMessage("");
        return run;
    }

    private void finish(AgentTestRun run, String status, boolean schemaValid, Integer score,
                        Map<String, Integer> dimensions, List<String> issues, String output,
                        String error, long started) {
        run.setStatus(status);
        run.setSchemaValid(schemaValid);
        run.setOverallScore(score);
        run.setDimensionScores(writeJson(dimensions));
        run.setIssues(writeJson(issues));
        run.setOutputText(output == null ? "" : output);
        run.setErrorMessage(clean(error));
        run.setDurationMs(System.currentTimeMillis() - started);
    }

    private Evaluation evaluate(AgentDefinition definition, LocalCliConfigItem cli,
                                String inputJson, String output) {
        String system = "你是独立质量评审。根据评分量表评估候选输出，不重写内容，只返回 JSON："
                + "{\"overallScore\":0,\"dimensions\":{\"任务符合度\":0,\"内容质量\":0,\"教学适配\":0},\"issues\":[\"问题\"]}。"
                + "所有分数为 0 到 100 的整数。";
        String user = "评分量表：\n" + defaultText(definition.getEvaluationRubric(), "任务符合度、准确性、自然度和可执行性。")
                + "\n\n原始输入：\n" + inputJson + "\n\n候选输出：\n" + output;
        try {
            String raw = generationService.generate(cli, system, user, Math.min(1200, definition.getMaxTokens()));
            JsonNode node = schemaValidator.parseJson(raw, "评分结果");
            Integer score = node.has("overallScore") ? Math.max(0, Math.min(100, node.path("overallScore").asInt())) : null;
            Map<String, Integer> dimensions = new LinkedHashMap<>();
            node.path("dimensions").fields().forEachRemaining(entry ->
                    dimensions.put(entry.getKey(), Math.max(0, Math.min(100, entry.getValue().asInt()))));
            List<String> issues = new ArrayList<>();
            node.path("issues").forEach(item -> {
                if (StringUtils.hasText(item.asText())) issues.add(item.asText().trim());
            });
            return new Evaluation(score, dimensions, issues);
        } catch (Exception ex) {
            return new Evaluation(null, Map.of(), List.of("自动评分失败：" + ex.getMessage()));
        }
    }

    private String buildSystemPrompt(AgentDefinition definition) {
        StringBuilder value = new StringBuilder(definition.getSystemPrompt().trim());
        if (StringUtils.hasText(definition.getHardRules())) {
            value.append("\n\n必须遵守的硬性规则：\n").append(definition.getHardRules().trim());
        }
        value.append("\n\n只返回符合输出 Schema 的 JSON，不要添加 Markdown 代码围栏。输出 Schema：\n")
                .append(definition.getOutputSchema());
        return value.toString();
    }

    private String renderPrompt(String template, JsonNode input, String inputJson) {
        String source = template.replace("{{input}}", inputJson);
        Matcher matcher = VARIABLE.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            JsonNode value = input.path(matcher.group(1));
            String replacement = value.isMissingNode() ? matcher.group(0)
                    : value.isTextual() ? value.asText() : value.toString();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private AgentTestResult toResult(AgentTestRun run) {
        Map<String, Integer> dimensions = readJson(run.getDimensionScores(), new TypeReference<>() {}, Map.of());
        List<String> issues = readJson(run.getIssues(), new TypeReference<>() {}, List.of());
        return new AgentTestResult(run.getId(), run.getAgentId(), run.getAgentKey(), run.getAgentName(),
                run.getAiProviderId(), run.getStatus(), run.getInputJson(), run.getOutputText(),
                run.isSchemaValid(), run.getOverallScore(), dimensions, issues, run.getDurationMs(),
                run.getErrorMessage(), run.getCreatedAt());
    }

    private <T> T readJson(String value, TypeReference<T> type, T fallback) {
        try { return StringUtils.hasText(value) ? objectMapper.readValue(value, type) : fallback; }
        catch (Exception ex) { return fallback; }
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ex) { return value.toString(); }
    }

    private static String require(String value, String message) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(message);
        return value.trim();
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String defaultText(String value, String fallback) { return StringUtils.hasText(value) ? value.trim() : fallback; }
    private record Evaluation(Integer overallScore, Map<String, Integer> dimensions, List<String> issues) { }
}
