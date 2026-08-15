package com.aitaskcenter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aitaskcenter.model.ImageAgentConfig;
import com.aitaskcenter.model.ImageAgentPromptVersion;
import com.aitaskcenter.model.ImageFlowConfig;
import com.aitaskcenter.model.ImageStylePreset;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.Immutable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ImageAgentCatalogTest {
    private static final Pattern PROMPT_VARIABLE = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}");

    @Test
    void exposesExactEditableAgentsAndProgramNodesInFixedStageOrder() {
        List<ImageAgentCatalog.StageDefinition> stages = ImageAgentCatalog.stages();
        List<ImageAgentCatalog.NodeDefinition> nodes = stages.stream()
                .flatMap(stage -> stage.nodes().stream())
                .toList();

        assertEquals(
                List.of("understanding", "storyboarding", "prompting", "generation"),
                stages.stream().map(ImageAgentCatalog.StageDefinition::key).toList());
        assertEquals(List.of(1, 2, 3, 4), stages.stream().map(ImageAgentCatalog.StageDefinition::order).toList());
        assertEquals(
                List.of(
                        "image-story-analyst",
                        "image-continuity-designer",
                        "image-art-director",
                        "image-action-storyboarder",
                        "image-learning-storyboarder",
                        "image-storyboard-director",
                        "image-reference-planner",
                        "image-shot-prompt-engineer",
                        "image-prompt-preflight"),
                nodes.stream().filter(ImageAgentCatalog.NodeDefinition::editable)
                        .map(ImageAgentCatalog.NodeDefinition::key).toList());
        assertEquals(
                List.of("reference-image-generator", "shot-image-generator", "text-compositor"),
                nodes.stream().filter(node -> "PROGRAM".equals(node.nodeKind()))
                        .map(ImageAgentCatalog.NodeDefinition::key).toList());
        assertEquals(9, ImageAgentCatalog.agents().size());
        assertEquals("image-story-analyst", ImageAgentCatalog.require("image-story-analyst").key());
    }

    @Test
    void assignsParallelGroupsOnlyToIndependentAgentProposals() {
        assertEquals(
                List.of("image-story-analyst", "image-continuity-designer", "image-art-director"),
                ImageAgentCatalog.stages().get(0).nodes().stream()
                        .map(ImageAgentCatalog.NodeDefinition::key).toList());
        assertTrue(ImageAgentCatalog.stages().get(0).nodes().stream()
                .allMatch(node -> "image-foundation".equals(node.parallelGroup())));
        assertEquals(
                List.of("image-action-storyboarder", "image-learning-storyboarder"),
                ImageAgentCatalog.stages().get(1).nodes().stream().limit(2)
                        .map(ImageAgentCatalog.NodeDefinition::key).toList());
        assertTrue(ImageAgentCatalog.stages().get(1).nodes().stream().limit(2)
                .allMatch(node -> "image-storyboards".equals(node.parallelGroup())));

        List<ImageAgentCatalog.NodeDefinition> orderedAgents = ImageAgentCatalog.stages().stream()
                .flatMap(stage -> stage.nodes().stream())
                .filter(ImageAgentCatalog.NodeDefinition::editable)
                .skip(5)
                .toList();
        assertEquals(
                List.of(
                        "image-storyboard-director",
                        "image-reference-planner",
                        "image-shot-prompt-engineer",
                        "image-prompt-preflight"),
                orderedAgents.stream().map(ImageAgentCatalog.NodeDefinition::key).toList());
        assertTrue(orderedAgents.stream().allMatch(node -> node.parallelGroup() == null));
        assertNull(ImageAgentCatalog.require("reference-image-generator").parallelGroup());
    }

    @Test
    void givesEveryEditablePromptItsCompleteStructuredImageContract() {
        Map<String, String> expectedSchemas = Map.of(
                "image-story-analyst", "StoryAnalysis",
                "image-continuity-designer", "ContinuityBible",
                "image-art-director", "StyleBible",
                "image-action-storyboarder", "StoryboardProposal",
                "image-learning-storyboarder", "StoryboardProposal",
                "image-storyboard-director", "FinalStoryboard",
                "image-reference-planner", "ReferencePlan",
                "image-shot-prompt-engineer", "ShotPromptPlan",
                "image-prompt-preflight", "PreflightPlan");
        Map<String, List<String>> expectedVariables = Map.ofEntries(
                Map.entry(
                        "image-story-analyst",
                        List.of("storySnapshot", "targetGrade", "targetWords", "imageSettings")),
                Map.entry(
                        "image-continuity-designer",
                        List.of("storySnapshot", "targetGrade", "targetWords", "imageSettings")),
                Map.entry(
                        "image-art-director",
                        List.of("storySnapshot", "targetGrade", "stylePreset", "imageSettings")),
                Map.entry(
                        "image-action-storyboarder",
                        List.of("storySnapshot", "storyAnalysis", "continuityBible", "styleBible", "imageSettings")),
                Map.entry(
                        "image-learning-storyboarder",
                        List.of("storySnapshot", "storyAnalysis", "continuityBible", "styleBible", "imageSettings")),
                Map.entry(
                        "image-storyboard-director",
                        List.of(
                                "storySnapshot",
                                "storyAnalysis",
                                "continuityBible",
                                "styleBible",
                                "actionStoryboardProposal",
                                "learningStoryboardProposal",
                                "imageSettings")),
                Map.entry(
                        "image-reference-planner",
                        List.of("storyAnalysis", "continuityBible", "styleBible", "finalStoryboard", "imageSettings")),
                Map.entry(
                        "image-shot-prompt-engineer",
                        List.of(
                                "storySnapshot",
                                "continuityBible",
                                "styleBible",
                                "finalStoryboard",
                                "referencePlan",
                                "imageSettings")),
                Map.entry(
                        "image-prompt-preflight",
                        List.of(
                                "storySnapshot",
                                "storyAnalysis",
                                "continuityBible",
                                "styleBible",
                                "finalStoryboard",
                                "referencePlan",
                                "shotPromptPlan",
                                "imageSettings")));
        Map<String, SchemaContract> expectedContracts = Map.ofEntries(
                Map.entry(
                        "image-story-analyst",
                        new SchemaContract(
                                "StoryAnalysis",
                                "STORY_ANALYSIS",
                                List.of(
                                        arrayField("scenes"),
                                        arrayField("beats"),
                                        arrayField("characters"),
                                        arrayField("locations"),
                                        arrayField("props"),
                                        arrayField("dialogues"),
                                        arrayField("narration")),
                                Map.ofEntries(
                                        Map.entry("scenes", objectArray("sceneIndex", "title", "sourceExcerpt", "summary")),
                                        Map.entry("beats", objectArray("beatKey", "sceneIndex", "order", "action", "temporalMoment")),
                                        Map.entry("characters", objectArray("characterKey", "name", "description")),
                                        Map.entry("locations", objectArray("locationKey", "name", "description")),
                                        Map.entry("props", objectArray("propKey", "name", "description")),
                                        Map.entry("dialogues", objectArray("sceneIndex", "speaker", "text")),
                                        Map.entry("narration", objectArray("sceneIndex", "text"))))),
                Map.entry(
                        "image-continuity-designer",
                        new SchemaContract(
                                "ContinuityBible",
                                "CONTINUITY_BIBLE",
                                List.of(arrayField("characters"), arrayField("props"), arrayField("invariants"), arrayField("forbiddenChanges")),
                                Map.ofEntries(
                                        Map.entry(
                                                "characters",
                                                objectArray(
                                                        "characterKey",
                                                        "name",
                                                        "visualDescription",
                                                        "clothing",
                                                        "colors",
                                                        "proportions",
                                                        "expressionRules")),
                                        Map.entry("props", objectArray("propKey", "visualDescription", "colors", "invariants")),
                                        Map.entry("invariants", stringArray()),
                                        Map.entry("forbiddenChanges", stringArray())))),
                Map.entry(
                        "image-art-director",
                        new SchemaContract(
                                "StyleBible",
                                "STYLE_BIBLE",
                                List.of(field("palette"), field("renderingStyle"), field("lighting"), field("cameraRules"), field("environmentRules"), arrayField("negativeRules")),
                                Map.of("negativeRules", stringArray()))),
                Map.entry(
                        "image-action-storyboarder",
                        storyboardProposalContract()),
                Map.entry(
                        "image-learning-storyboarder",
                        storyboardProposalContract()),
                Map.entry(
                        "image-storyboard-director",
                        new SchemaContract(
                                "FinalStoryboard",
                                "FINAL_STORYBOARD",
                                List.of(arrayField("shots")),
                                Map.ofEntries(
                                        Map.entry(
                                                "shots",
                                                objectArray(
                                                        "shotKey",
                                                        "sceneIndex",
                                                        "shotIndex",
                                                        "beat",
                                                        "action",
                                                        "characters",
                                                        "location",
                                                        "sourceExcerpt",
                                                        "visualGoal",
                                                        "dialogue",
                                                        "narration",
                                                        "speaker",
                                                        "textAnchor")),
                                        Map.entry("shots.characters", stringArray())))),
                Map.entry(
                        "image-reference-planner",
                        new SchemaContract(
                                "ReferencePlan",
                                "REFERENCE_PLAN",
                                List.of(arrayField("referenceAssets")),
                                Map.of(
                                        "referenceAssets",
                                        objectArray("assetKey", "type", "target", "prompt", "negativePrompt")))),
                Map.entry(
                        "image-shot-prompt-engineer",
                        new SchemaContract(
                                "ShotPromptPlan",
                                "SHOT_PROMPT_PLAN",
                                List.of(arrayField("shots")),
                                Map.ofEntries(
                                        Map.entry(
                                                "shots",
                                                objectArray("shotKey", "prompt", "negativePrompt", "referenceAssetKeys")),
                                        Map.entry("shots.referenceAssetKeys", stringArray())))),
                Map.entry(
                        "image-prompt-preflight",
                        new SchemaContract(
                                "PreflightPlan",
                                "PREFLIGHT_PLAN",
                                List.of(arrayField("referenceAssets"), arrayField("shots"), field("auditSummary")),
                                Map.ofEntries(
                                        Map.entry(
                                                "referenceAssets",
                                                objectArray("assetKey", "type", "target", "prompt", "negativePrompt")),
                                        Map.entry(
                                                "shots",
                                                objectArray(
                                                        "shotKey",
                                                        "sceneIndex",
                                                        "shotIndex",
                                                        "prompt",
                                                        "negativePrompt",
                                                        "referenceAssetKeys",
                                                        "speaker",
                                                        "dialogue",
                                                        "narration",
                                                        "textAnchor")),
                                        Map.entry("shots.referenceAssetKeys", stringArray())))));

        for (ImageAgentCatalog.NodeDefinition agent : ImageAgentCatalog.agents()) {
            String prompt = agent.defaultPrompt();
            assertEquals(expectedVariables.get(agent.key()), agent.variables(), agent.key());
            assertTrue(prompt.contains("输入变量："), agent.key());
            assertPromptVariablesExactlyMatch(agent, prompt);
            assertTrue(prompt.contains("严格 JSON 输出边界"), agent.key());
            assertTrue(prompt.contains("小学三年级"), agent.key());
            assertTrue(prompt.contains("图片模型不得生成文字"), agent.key());
            assertTrue(prompt.contains("角色、服装、道具、场景和画风必须跨图连续"), agent.key());
            SchemaContract contract = expectedContracts.get(agent.key());
            assertEquals(expectedSchemas.get(agent.key()), contract.schemaName(), agent.key());
            String begin = "<" + contract.markerKey() + "_JSON_BEGIN>";
            String end = "<" + contract.markerKey() + "_JSON_END>";
            assertEquals(1, countOccurrences(prompt, begin), agent.key());
            assertEquals(1, countOccurrences(prompt, end), agent.key());
            assertTrue(prompt.contains("BEGIN/END 外不得有文字或 Markdown"), agent.key());
            assertTrue(prompt.contains("BEGIN 与 END 之间只能是 JSON object"), agent.key());
            assertFalse(prompt.contains("```"), agent.key());
            assertTrue(prompt.contains("输出 schema：" + contract.schemaName() + "。"), agent.key());
            assertTrue(
                    prompt.contains("顶层字段必须且只能包含 " + contract.topLevelDeclaration() + "。"),
                    agent.key());
            assertFalse(prompt.contains("["), agent.key());
            assertFalse(prompt.contains("]"), agent.key());
            assertTrue(prompt.contains("所有 object 字段均为必填；数组不得省略，且仅在不违反上述完整覆盖和非空要求时可为空。"), agent.key());
            assertTrue(prompt.contains("禁止添加未声明的顶层字段。"), agent.key());
            if ("image-story-analyst".equals(agent.key())) {
                assertTrue(prompt.contains("每个 Scene 必须拆出 1 到 5 个连续节拍"), agent.key());
                assertTrue(prompt.contains("characters 和 locations 都不得为空"), agent.key());
            }
            if ("image-action-storyboarder".equals(agent.key())
                    || "image-learning-storyboarder".equals(agent.key())) {
                assertTrue(prompt.contains("每个 beatKey 至少对应一个独立分镜"), agent.key());
                assertTrue(prompt.contains("不得跨 Scene"), agent.key());
            }
            if ("image-storyboard-director".equals(agent.key())) {
                assertTrue(prompt.contains("不得合并或遗漏 beat"), agent.key());
                assertTrue(prompt.contains("shotKey 必须来自输入提案"), agent.key());
            }
            if ("image-reference-planner".equals(agent.key())) {
                assertTrue(prompt.contains("每个角色和每个地点各生成且仅生成一个参考资产"), agent.key());
            }
            if ("image-shot-prompt-engineer".equals(agent.key())
                    || "image-prompt-preflight".equals(agent.key())) {
                assertTrue(prompt.contains("每个镜头必须引用其地点和全部出场角色的参考资产"), agent.key());
            }
            if ("FINAL_STORYBOARD".equals(contract.markerKey()) || "PREFLIGHT_PLAN".equals(contract.markerKey())) {
                assertTrue(
                        prompt.contains("字段 shots.textAnchor 必须为 null 或 object，object 必须且只能包含 x、y；x、y 为 0 到 1 的归一化数字。"),
                        agent.key());
            }
            if ("REFERENCE_PLAN".equals(contract.markerKey()) || "PREFLIGHT_PLAN".equals(contract.markerKey())) {
                assertTrue(prompt.contains("字段 referenceAssets.type 必须是 CHARACTER 或 LOCATION。"), agent.key());
            }
            assertFalse(contract.arrayItemContracts().isEmpty(), agent.key());
            for (Map.Entry<String, ArrayItemContract> arrayContract : contract.arrayItemContracts().entrySet()) {
                if (arrayContract.getValue().scalarString()) {
                    assertTrue(
                            prompt.contains("数组字段 " + arrayContract.getKey() + " 的每项必须是 string。"),
                            agent.key() + " " + arrayContract.getKey());
                } else {
                    assertTrue(
                            prompt.contains("数组字段 " + arrayContract.getKey() + " 的每项必须且只能包含 "
                                    + String.join("、", arrayContract.getValue().objectFields()) + "。"),
                            agent.key() + " " + arrayContract.getKey());
                }
            }
        }
    }

    private static SchemaContract storyboardProposalContract() {
        return new SchemaContract(
                "StoryboardProposal",
                "STORYBOARD_PROPOSAL",
                List.of(arrayField("shots")),
                Map.ofEntries(
                        Map.entry(
                                "shots",
                                objectArray(
                                        "shotKey",
                                        "sceneIndex",
                                        "beat",
                                        "action",
                                        "characters",
                                        "location",
                                        "dialogue",
                                        "narration",
                                        "splitReason")),
                        Map.entry("shots.characters", stringArray())));
    }

    private static ArrayItemContract objectArray(String... fields) {
        return new ArrayItemContract(false, List.of(fields));
    }

    private static FieldContract field(String name) {
        return new FieldContract(name, false);
    }

    private static FieldContract arrayField(String name) {
        return new FieldContract(name, true);
    }

    private static ArrayItemContract stringArray() {
        return new ArrayItemContract(true, List.of());
    }

    private static int countOccurrences(String value, String target) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }

    private static void assertPromptVariablesExactlyMatch(ImageAgentCatalog.NodeDefinition agent, String prompt) {
        List<String> placeholders = new ArrayList<>();
        Matcher matcher = PROMPT_VARIABLE.matcher(prompt);
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        Set<String> declared = new LinkedHashSet<>(agent.variables());
        Set<String> found = new LinkedHashSet<>(placeholders);
        assertEquals(agent.variables().size(), declared.size(), agent.key() + " declares duplicate variables");
        assertEquals(placeholders.size(), found.size(), agent.key() + " repeats a prompt placeholder");
        assertEquals(declared, found, agent.key() + " prompt variables");
    }

    @Test
    void locksImageGenerationConstants() {
        assertEquals(1536, ImageAgentCatalog.DEFAULT_WIDTH);
        assertEquals(864, ImageAgentCatalog.DEFAULT_HEIGHT);
        assertEquals(5, ImageAgentCatalog.DEFAULT_MAX_SHOTS_PER_SCENE);
        assertEquals(20, ImageAgentCatalog.DEFAULT_MAX_SHOTS_PER_STORY);
        assertEquals("default", ImageFlowConfig.defaults().getFlowKey());
        assertEquals(1536, ImageFlowConfig.defaults().getWidth());
        assertEquals(864, ImageFlowConfig.defaults().getHeight());
        assertEquals(5, ImageFlowConfig.defaults().getMaxShotsPerScene());
        assertEquals(20, ImageFlowConfig.defaults().getMaxShotsPerStory());
    }

    @Test
    void preservesTheJpaPersistenceInvariants() throws Exception {
        assertTable(ImageAgentConfig.class, "tb_image_agent_config");
        assertColumn(ImageAgentConfig.class, "agentKey", "agent_key", false, true, false);
        assertColumn(ImageAgentConfig.class, "systemPrompt", "system_prompt", false, false, true);
        assertColumn(ImageAgentConfig.class, "aiProviderId", "ai_provider_id", true, false, false);
        assertColumn(ImageAgentConfig.class, "temperature", "temperature", false, false, false);
        assertColumn(ImageAgentConfig.class, "enabled", "enabled", false, false, false);
        assertColumn(ImageAgentConfig.class, "promptVersion", "prompt_version", false, false, false);
        assertVersion(ImageAgentConfig.class);

        assertTable(ImageAgentPromptVersion.class, "tb_image_agent_prompt_version");
        assertUniqueConstraint(ImageAgentPromptVersion.class, "agent_key", "prompt_version");
        assertColumn(ImageAgentPromptVersion.class, "systemPrompt", "system_prompt", false, false, true);
        assertColumn(ImageAgentPromptVersion.class, "aiProviderId", "ai_provider_id", true, false, false);
        assertColumn(ImageAgentPromptVersion.class, "temperature", "temperature", false, false, false);
        assertColumn(ImageAgentPromptVersion.class, "enabled", "enabled", false, false, false);
        assertTrue(ImageAgentPromptVersion.class.isAnnotationPresent(Immutable.class));
        assertSnapshotColumnIsImmutable(ImageAgentPromptVersion.class, "agentKey");
        assertSnapshotColumnIsImmutable(ImageAgentPromptVersion.class, "promptVersion");
        assertSnapshotColumnIsImmutable(ImageAgentPromptVersion.class, "systemPrompt");
        assertSnapshotColumnIsImmutable(ImageAgentPromptVersion.class, "aiProviderId");
        assertSnapshotColumnIsImmutable(ImageAgentPromptVersion.class, "temperature");
        assertSnapshotColumnIsImmutable(ImageAgentPromptVersion.class, "enabled");

        assertTable(ImageFlowConfig.class, "tb_image_flow_config");
        assertColumn(ImageFlowConfig.class, "flowKey", "flow_key", false, true, false);
        assertColumn(ImageFlowConfig.class, "imageProviderId", "image_provider_id", true, false, false);
        assertColumn(ImageFlowConfig.class, "width", "width", false, false, false);
        assertColumn(ImageFlowConfig.class, "height", "height", false, false, false);
        assertColumn(ImageFlowConfig.class, "maxShotsPerScene", "max_shots_per_scene", false, false, false);
        assertColumn(ImageFlowConfig.class, "maxShotsPerStory", "max_shots_per_story", false, false, false);
        assertVersion(ImageFlowConfig.class);

        assertTable(ImageStylePreset.class, "tb_image_style_preset");
        assertColumn(ImageStylePreset.class, "presetKey", "preset_key", false, true, false);
        assertColumn(ImageStylePreset.class, "positivePrompt", "positive_prompt", false, false, true);
        assertColumn(ImageStylePreset.class, "negativePrompt", "negative_prompt", false, false, true);
        assertColumn(ImageStylePreset.class, "description", "description", false, false, true);
        assertColumn(ImageStylePreset.class, "enabled", "enabled", false, false, false);
        assertColumn(ImageStylePreset.class, "builtIn", "built_in", false, false, false);
        assertVersion(ImageStylePreset.class);
    }

    private static void assertTable(Class<?> type, String expectedName) {
        assertEquals(expectedName, type.getAnnotation(Table.class).name());
    }

    private static void assertUniqueConstraint(Class<?> type, String... expectedColumns) {
        UniqueConstraint[] constraints = type.getAnnotation(Table.class).uniqueConstraints();
        assertTrue(List.of(constraints).stream()
                .anyMatch(constraint -> List.of(constraint.columnNames()).equals(List.of(expectedColumns))));
    }

    private static void assertColumn(
            Class<?> type,
            String fieldName,
            String expectedName,
            boolean nullable,
            boolean unique,
            boolean text) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);
        assertEquals(expectedName, column.name());
        assertEquals(nullable, column.nullable());
        assertEquals(unique, column.unique());
        assertEquals(text, "text".equalsIgnoreCase(column.columnDefinition()));
    }

    private static void assertVersion(Class<?> type) {
        assertTrue(List.of(type.getDeclaredFields()).stream()
                .anyMatch(field -> field.getType() == long.class && field.isAnnotationPresent(Version.class)));
    }

    private static void assertSnapshotColumnIsImmutable(Class<?> type, String fieldName) throws Exception {
        assertFalse(type.getDeclaredField(fieldName).getAnnotation(Column.class).updatable(), fieldName);
    }

    private record SchemaContract(
            String schemaName,
            String markerKey,
            List<FieldContract> topLevelFields,
            Map<String, ArrayItemContract> arrayItemContracts) {
        private String topLevelDeclaration() {
            return topLevelFields.stream()
                    .map(field -> field.name() + (field.array() ? "（array）" : ""))
                    .collect(java.util.stream.Collectors.joining("、"));
        }
    }

    private record FieldContract(String name, boolean array) {
    }

    private record ArrayItemContract(boolean scalarString, List<String> objectFields) {
    }
}
