package com.aitaskcenter.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ImageAgentCatalog {
    public static final int DEFAULT_WIDTH = 1536;
    public static final int DEFAULT_HEIGHT = 864;
    public static final int DEFAULT_MAX_SHOTS_PER_SCENE = 5;
    public static final int DEFAULT_MAX_SHOTS_PER_STORY = 20;

    private static final SchemaContract STORY_ANALYSIS_CONTRACT = new SchemaContract(
            "StoryAnalysis",
            "STORY_ANALYSIS",
            List.of(arrayField("scenes"), arrayField("beats"), arrayField("characters"), arrayField("locations"), arrayField("props"), arrayField("dialogues"), arrayField("narration")),
            Map.ofEntries(
                    Map.entry("scenes", objectArray("sceneIndex", "title", "sourceExcerpt", "summary")),
                    Map.entry("beats", objectArray("beatKey", "sceneIndex", "order", "action", "temporalMoment")),
                    Map.entry("characters", objectArray("characterKey", "name", "description")),
                    Map.entry("locations", objectArray("locationKey", "name", "description")),
                    Map.entry("props", objectArray("propKey", "name", "description")),
                    Map.entry("dialogues", objectArray("sceneIndex", "speaker", "text")),
                    Map.entry("narration", objectArray("sceneIndex", "text"))));
    private static final SchemaContract CONTINUITY_BIBLE_CONTRACT = new SchemaContract(
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
                    Map.entry("forbiddenChanges", stringArray())));
    private static final SchemaContract STYLE_BIBLE_CONTRACT = new SchemaContract(
            "StyleBible",
            "STYLE_BIBLE",
            List.of(field("palette"), field("renderingStyle"), field("lighting"), field("cameraRules"), field("environmentRules"), arrayField("negativeRules")),
            Map.of("negativeRules", stringArray()));
    private static final SchemaContract STORYBOARD_PROPOSAL_CONTRACT = new SchemaContract(
            "StoryboardProposal",
            "STORYBOARD_PROPOSAL",
            List.of(arrayField("shots")),
            Map.ofEntries(
                    Map.entry(
                            "shots",
                            objectArray("sceneIndex", "beat", "action", "characters", "location", "dialogue", "narration", "splitReason")),
                    Map.entry("shots.characters", stringArray())));
    private static final SchemaContract FINAL_STORYBOARD_CONTRACT = new SchemaContract(
            "FinalStoryboard",
            "FINAL_STORYBOARD",
            List.of(arrayField("shots")),
            Map.of(
                    "shots",
                    objectArray(
                            "shotKey",
                            "sceneIndex",
                            "shotIndex",
                            "sourceExcerpt",
                            "visualGoal",
                            "dialogue",
                            "narration",
                            "speaker",
                            "textAnchor")));
    private static final SchemaContract REFERENCE_PLAN_CONTRACT = new SchemaContract(
            "ReferencePlan",
            "REFERENCE_PLAN",
            List.of(arrayField("referenceAssets")),
            Map.of("referenceAssets", objectArray("assetKey", "type", "target", "prompt", "negativePrompt")));
    private static final SchemaContract SHOT_PROMPT_PLAN_CONTRACT = new SchemaContract(
            "ShotPromptPlan",
            "SHOT_PROMPT_PLAN",
            List.of(arrayField("shots")),
            Map.ofEntries(
                    Map.entry("shots", objectArray("shotKey", "prompt", "negativePrompt", "referenceAssetKeys")),
                    Map.entry("shots.referenceAssetKeys", stringArray())));
    private static final SchemaContract PREFLIGHT_PLAN_CONTRACT = new SchemaContract(
            "PreflightPlan",
            "PREFLIGHT_PLAN",
            List.of(arrayField("referenceAssets"), arrayField("shots"), field("auditSummary")),
            Map.ofEntries(
                    Map.entry("referenceAssets", objectArray("assetKey", "type", "target", "prompt", "negativePrompt")),
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
                    Map.entry("shots.referenceAssetKeys", stringArray())));

    private static final List<StageDefinition> STAGES = List.of(
            new StageDefinition(
                    "understanding",
                    "故事理解与视觉约束",
                    "并行理解故事、连续性与画风约束。",
                    1,
                    List.of(
                            agent(
                                    "image-story-analyst",
                                    "故事结构分析 Agent",
                                    "ANALYST",
                                    10,
                                    "image-foundation",
                                    "分析故事场景、节拍、动作、角色、地点、道具、对白和旁白。",
                                    List.of("storySnapshot", "targetGrade", "targetWords", "imageSettings"),
                                    STORY_ANALYSIS_CONTRACT,
                                    "从故事中拆出可追踪的场景、节拍和原始文本，不增写剧情。"),
                            agent(
                                    "image-continuity-designer",
                                    "角色连续性设计 Agent",
                                    "CONTINUITY_DESIGNER",
                                    20,
                                    "image-foundation",
                                    "建立角色和关键道具的不可变视觉说明书。",
                                    List.of("storySnapshot", "targetGrade", "targetWords", "imageSettings"),
                                    CONTINUITY_BIBLE_CONTRACT,
                                    "为角色和道具定义稳定外貌、比例、服装、颜色与表情边界。"),
                            agent(
                                    "image-art-director",
                                    "美术导演 Agent",
                                    "ART_DIRECTOR",
                                    30,
                                    "image-foundation",
                                    "将选定画风预设扩展为可执行的美术规则。",
                                    List.of("storySnapshot", "targetGrade", "stylePreset", "imageSettings"),
                                    STYLE_BIBLE_CONTRACT,
                                    "定义色板、线条、材质、光线、镜头语言、环境与禁止元素。"))),
            new StageDefinition(
                    "storyboarding",
                    "双分镜提案与决策",
                    "两个提案并行，随后合并为唯一分镜表。",
                    2,
                    List.of(
                            agent(
                                    "image-action-storyboarder",
                                    "动作分镜 Agent",
                                    "ACTION_STORYBOARDER",
                                    10,
                                    "image-storyboards",
                                    "按动作、视点和时间推进提出互不冲突的画面。",
                                    List.of("storySnapshot", "storyAnalysis", "continuityBible", "styleBible", "imageSettings"),
                                    STORYBOARD_PROPOSAL_CONTRACT,
                                    "按动作变化、视点变化和时间推进拆镜，禁止单镜包含互斥时间点。"),
                            agent(
                                    "image-learning-storyboarder",
                                    "儿童叙事分镜 Agent",
                                    "LEARNING_STORYBOARDER",
                                    20,
                                    "image-storyboards",
                                    "按三年级儿童理解顺序提出带短文本的画面。",
                                    List.of("storySnapshot", "storyAnalysis", "continuityBible", "styleBible", "imageSettings"),
                                    STORYBOARD_PROPOSAL_CONTRACT,
                                    "按儿童可理解的因果顺序拆镜，并分配短对白或一到两句旁白。"),
                            agent(
                                    "image-storyboard-director",
                                    "分镜总监 Agent",
                                    "STORYBOARD_DIRECTOR",
                                    30,
                                    null,
                                    "合并双分镜提案并生成唯一、连续且受数量限制的分镜表。",
                                    List.of(
                                            "storySnapshot",
                                            "storyAnalysis",
                                            "continuityBible",
                                            "styleBible",
                                            "actionStoryboardProposal",
                                            "learningStoryboardProposal",
                                            "imageSettings"),
                                    FINAL_STORYBOARD_CONTRACT,
                                    "确保每个 Scene 一到五镜、全篇最多二十镜、节拍完整覆盖，并为每镜给稳定 shotKey。"))),
            new StageDefinition(
                    "prompting",
                    "出图提示词准备",
                    "规划参考资产、工程化分镜提示词并完成一次预检。",
                    3,
                    List.of(
                            agent(
                                    "image-reference-planner",
                                    "参考资产规划 Agent",
                                    "REFERENCE_PLANNER",
                                    10,
                                    null,
                                    "规划角色设定图与主要场景设定图的参考资产。",
                                    List.of("storyAnalysis", "continuityBible", "styleBible", "finalStoryboard", "imageSettings"),
                                    REFERENCE_PLAN_CONTRACT,
                                    "定义稳定 assetKey、资产类型、目标、无字提示词与负向约束。"),
                            agent(
                                    "image-shot-prompt-engineer",
                                    "分镜提示词工程 Agent",
                                    "SHOT_PROMPT_ENGINEER",
                                    20,
                                    null,
                                    "为每个分镜生成完整且可复现的图片提示词。",
                                    List.of(
                                            "storySnapshot",
                                            "continuityBible",
                                            "styleBible",
                                            "finalStoryboard",
                                            "referencePlan",
                                            "imageSettings"),
                                    SHOT_PROMPT_PLAN_CONTRACT,
                                    "为每个 shotKey 写入角色描述、构图、动作、镜头、光线、负向约束和引用资产。"),
                            agent(
                                    "image-prompt-preflight",
                                    "出图前校对 Agent",
                                    "PROMPT_PREFLIGHT",
                                    30,
                                    null,
                                    "校验并输出最终参考资产与分镜生成计划，不发起循环修订。",
                                    List.of(
                                            "storySnapshot",
                                            "storyAnalysis",
                                            "continuityBible",
                                            "styleBible",
                                            "finalStoryboard",
                                            "referencePlan",
                                            "shotPromptPlan",
                                            "imageSettings"),
                                    PREFLIGHT_PLAN_CONTRACT,
                                    "检查故事覆盖、连续性、16:9 构图、参考绑定、无字限制、动作可视化和提示词冲突。"))),
            new StageDefinition(
                    "generation",
                    "图片生成与文字合成",
                    "按最终计划生成无字图片，再由程序确定性排版文字。",
                    4,
                    List.of(
                            program(
                                    "reference-image-generator",
                                    "参考图生成器",
                                    "REFERENCE_IMAGE_GENERATOR",
                                    10,
                                    "依据预检后的参考资产计划生成无字角色与场景设定图。"),
                            program(
                                    "shot-image-generator",
                                    "分镜图生成器",
                                    "SHOT_IMAGE_GENERATOR",
                                    20,
                                    "按场景与镜头顺序生成每个分镜的一张无字底图。"),
                            program(
                                    "text-compositor",
                                    "文字合成器",
                                    "TEXT_COMPOSITOR",
                                    30,
                                    "以确定性规则叠加角色气泡和底部字幕，不调用图片模型。"))));

    private static final List<NodeDefinition> NODES = STAGES.stream()
            .flatMap(stage -> stage.nodes().stream())
            .toList();

    private static final List<NodeDefinition> AGENTS = NODES.stream()
            .filter(NodeDefinition::editable)
            .toList();

    private static final Map<String, NodeDefinition> NODES_BY_KEY = NODES.stream()
            .collect(Collectors.toUnmodifiableMap(NodeDefinition::key, Function.identity()));

    private ImageAgentCatalog() {
    }

    public static List<StageDefinition> stages() {
        return STAGES;
    }

    public static List<NodeDefinition> nodes() {
        return NODES;
    }

    public static List<NodeDefinition> agents() {
        return AGENTS;
    }

    public static NodeDefinition require(String key) {
        NodeDefinition node = NODES_BY_KEY.get(Objects.requireNonNull(key, "key"));
        if (node == null) {
            throw new IllegalArgumentException("Unknown image flow node: " + key);
        }
        return node;
    }

    private static NodeDefinition agent(
            String key,
            String name,
            String roleType,
            int order,
            String parallelGroup,
            String description,
            List<String> variables,
            SchemaContract schemaContract,
            String responsibility) {
        return new NodeDefinition(
                key,
                name,
                "AGENT",
                roleType,
                order,
                parallelGroup,
                description,
                variables,
                structuredPrompt(name, variables, schemaContract, responsibility),
                "Pro",
                0.2,
                true);
    }

    private static NodeDefinition program(String key, String name, String roleType, int order, String description) {
        return new NodeDefinition(
                key,
                name,
                "PROGRAM",
                roleType,
                order,
                null,
                description,
                List.of(),
                "",
                "",
                0.0,
                false);
    }

    private static String structuredPrompt(
            String agentName, List<String> variables, SchemaContract schemaContract, String responsibility) {
        String inputVariables = variables.stream()
                .map(variable -> "{{" + variable + "}}")
                .collect(Collectors.joining("、"));
        String arrayContracts = schemaContract.arrayItemContracts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getValue().scalarString()
                        ? "数组字段 " + entry.getKey() + " 的每项必须是 string。"
                        : "数组字段 " + entry.getKey() + " 的每项必须且只能包含 "
                                + String.join("、", entry.getValue().objectFields()) + "。")
                .collect(Collectors.joining("\n"));
        String beginMarker = "<" + schemaContract.markerKey() + "_JSON_BEGIN>";
        String endMarker = "<" + schemaContract.markerKey() + "_JSON_END>";
        String textAnchorContract = schemaContract.requiresTextAnchor()
                ? "字段 shots.textAnchor 必须为 null 或 object，object 必须且只能包含 x、y；x、y 为 0 到 1 的归一化数字。"
                : "";
        String referenceTypeContract = schemaContract.requiresReferenceType()
                ? "字段 referenceAssets.type 必须是 CHARACTER 或 LOCATION。"
                : "";
        return ("""
                你是%s。%s

                输入变量：%s。
                面向小学三年级英语读者：仅保留孩子能理解的因果、动作、短对白和一到两句短旁白；不得改变故事主线。
                图片模型不得生成文字：任何图片提示词、负向提示词、角色设定图或分镜图都不得要求渲染字母、单词、对话、字幕、标牌文字或水印。
                角色、服装、道具、场景和画风必须跨图连续；固定角色外貌、比例、颜色、随身物与环境规则，除非故事明确发生可解释的变化。

                严格 JSON 输出边界：只能出现一次 %s 和一次 %s。BEGIN/END 外不得有文字或 Markdown；BEGIN 与 END 之间只能是 JSON object。JSON 必须有效、字段完整、key 稳定且不重复。
                输出 schema：%s。
                顶层字段必须且只能包含 %s。
                %s
                %s
                %s
                所有 object 字段均为必填；数组可为空但不得省略。禁止添加未声明的顶层字段。
                """).formatted(
                agentName,
                responsibility,
                inputVariables,
                beginMarker,
                endMarker,
                schemaContract.schemaName(),
                schemaContract.topLevelDeclaration(),
                arrayContracts,
                textAnchorContract,
                referenceTypeContract).strip();
    }

    private static ArrayItemContract objectArray(String... fields) {
        return new ArrayItemContract(false, List.of(fields));
    }

    private static ArrayItemContract stringArray() {
        return new ArrayItemContract(true, List.of());
    }

    private static FieldContract field(String name) {
        return new FieldContract(name, false);
    }

    private static FieldContract arrayField(String name) {
        return new FieldContract(name, true);
    }

    public record StageDefinition(String key, String name, String note, int order, List<NodeDefinition> nodes) {
        public StageDefinition {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(note, "note");
            nodes = List.copyOf(nodes);
        }
    }

    public record NodeDefinition(
            String key,
            String name,
            String nodeKind,
            String roleType,
            int order,
            String parallelGroup,
            String description,
            List<String> variables,
            String defaultPrompt,
            String modelPreference,
            double defaultTemperature,
            boolean editable) {
        public NodeDefinition {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(nodeKind, "nodeKind");
            Objects.requireNonNull(roleType, "roleType");
            Objects.requireNonNull(description, "description");
            variables = List.copyOf(variables);
            Objects.requireNonNull(defaultPrompt, "defaultPrompt");
            Objects.requireNonNull(modelPreference, "modelPreference");
        }
    }

    private record SchemaContract(
            String schemaName,
            String markerKey,
            List<FieldContract> topLevelFields,
            Map<String, ArrayItemContract> arrayItemContracts) {
        private SchemaContract {
            Objects.requireNonNull(schemaName, "schemaName");
            Objects.requireNonNull(markerKey, "markerKey");
            topLevelFields = List.copyOf(topLevelFields);
            arrayItemContracts = Map.copyOf(arrayItemContracts);
        }

        private String topLevelDeclaration() {
            return topLevelFields.stream()
                    .map(field -> field.name() + (field.array() ? "（array）" : ""))
                    .collect(Collectors.joining("、"));
        }

        private boolean requiresTextAnchor() {
            return "FINAL_STORYBOARD".equals(markerKey) || "PREFLIGHT_PLAN".equals(markerKey);
        }

        private boolean requiresReferenceType() {
            return "REFERENCE_PLAN".equals(markerKey) || "PREFLIGHT_PLAN".equals(markerKey);
        }
    }

    private record FieldContract(String name, boolean array) {
    }

    private record ArrayItemContract(boolean scalarString, List<String> objectFields) {
        private ArrayItemContract {
            objectFields = List.copyOf(objectFields);
            if (scalarString && !objectFields.isEmpty()) {
                throw new IllegalArgumentException("scalar string array cannot declare object fields");
            }
            if (!scalarString && objectFields.isEmpty()) {
                throw new IllegalArgumentException("object array must declare object fields");
            }
        }
    }
}
