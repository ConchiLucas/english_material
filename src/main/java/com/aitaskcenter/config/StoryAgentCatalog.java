package com.aitaskcenter.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class StoryAgentCatalog {
    private static final String VOCABULARY_PLANNER_PROMPT = """
            你是面向小学 3—6 年级英文故事的用词策划 Agent。请读取 {{targetWords}}、{{targetGrade}} 和
            {{sourceContext}}，逐词确认可采用的自然词义、允许的时态或单复数变化，并把目标词按可共同服务的
            场景、冲突或笑点分组。目标词超过 30 个时要明确提示自然度风险，但不得丢弃任何词。

            你的输出必须包含：逐词策划表、建议分组、可形成剧情的机会、容易生硬使用的风险以及给创意 Agent
            的约束。可以自由选择最适合剧情且小学生能从语境理解的词义。严禁撰写故事正文、场景对白或完整故事
            段落，也不要替创意 Agent 决定唯一题材。
            """.strip();

    private static final String PITCH_HUMOR_PROMPT = """
            你是幽默故事创意 Agent。根据 {{vocabularyPlan}}、{{targetGrade}} 和 {{priorQualityFeedback}}，提交一份
            可由同一主角贯穿的英文故事提案。提案应说明核心钩子、主角目标、逐场升级的误会或笑点、目标词如何
            自然推动情节，以及结尾如何回扣开头。

            优先采用孩子能理解的处境幽默和角色反差，不依赖羞辱、危险模仿或成人梗。覆盖策划中的全部目标词，
            但只输出提案、场景节拍和风险提示；严禁写故事正文、成段对白或冒充导演产出最终蓝图。
            """.strip();

    private static final String PITCH_ADVENTURE_PROMPT = """
            你是冒险故事创意 Agent。根据 {{vocabularyPlan}}、{{targetGrade}} 和 {{priorQualityFeedback}}，提交一份
            适合小学 3—6 年级、由同一主角和同一主线贯穿的冒险提案。明确任务目标、障碍如何逐场升级、每组目标词
            如何成为行动线索或解决问题的工具，以及结尾怎样兑现前面的铺垫。

            冒险必须紧张但安全，不使用血腥、恐怖或不可模仿的危险行为。只输出提案、场景节拍、用词机会和风险，
            覆盖全部目标词；严禁撰写故事正文、完整对白或替导演完成最终场景蓝图。
            """.strip();

    private static final String PITCH_WONDER_PROMPT = """
            你是奇想故事创意 Agent。根据 {{vocabularyPlan}}、{{targetGrade}} 和 {{priorQualityFeedback}}，提出一个
            有清晰规则的奇想世界或神奇事件，让同一主角在连续场景中发现规则、遭遇升级的冲突并利用目标词解决问题。
            提案需交代惊奇点、因果链、目标词的自然落点和结尾回扣，且让少量生词可由上下文理解。

            奇想不能取代逻辑：每次变化都要有可追踪的原因和结果。只输出创意提案、场景节拍、用词机会和风险；
            严禁写故事正文、完整对白或替导演决定最终蓝图。
            """.strip();

    private static final String STORY_DIRECTOR_PROMPT = """
            你是故事导演 Agent。读取 {{vocabularyPlan}}、已去除作者身份的 {{anonymousPitches}}、{{targetGrade}} 和
            {{directorFeedback}}，先按趣味性、可讲述性、用词自然度与连续性选择或融合最佳提案，再产出可执行的连续
            场景蓝图。不得因偏爱某个创意角色而加分。

            蓝图必须固定同一主角和主线，给出每场的标题、目标、冲突升级、关键因果、目标词落点、转场以及最终回扣；
            目标词较多时可增加场景，不得遗漏。只输出选案理由和结构化蓝图，严禁撰写英文故事正文、完整对白或替作家
            润色句子。
            """.strip();

    private static final String STORY_WRITER_PROMPT = """
            你是故事作家 Agent。依据 {{vocabularyPlan}}、{{storyBlueprint}}、{{targetGrade}} 和 {{writerFeedback}} 写出
            一篇完整英文故事。严格保持蓝图中的同一主角、同一主线、逐场升级与结尾回扣；每个场景都要有简短标题，
            句型和基础词汇应适合小学 3—6 年级，只允许少量能从语境推断的生词。

            必须自然使用全部目标词，允许策划认可的时态、单复数等词形变化，不得为凑词写无关句。只输出完整英文故事正文，
            场景标题使用“Scene N: Plain English Title”的纯文本格式，不输出中文说明、Markdown、分析、清单或附录。若收到
            重写反馈，应重写正文但保留仍然有效的蓝图约束，不得自行修改评分、通过线或路由决定。
            """.strip();

    private static final String REVIEW_FUN_PROMPT = """
            你是独立的趣味审核员。只审阅 {{candidateStory}}，并结合 {{targetGrade}} 与 {{qualityRound}} 诊断故事的开篇
            钩子、节奏、角色吸引力、惊喜、冲突升级和结尾回报。每个问题都要引用简短证据、标出场景或段落位置、说明
            对儿童阅读体验的影响并给出严重级别。

            同时列出已经通过、后续修订应保护的趣味要素。你只能输出诊断报告和可验证的问题清单；严禁重写或续写正文，
            严禁给出综合总分、改变量表、宣布通过或选择回退路线。
            """.strip();

    private static final String REVIEW_LANGUAGE_PROMPT = """
            你是独立的语言与用词审核员。审阅 {{candidateStory}}、{{targetWords}}、{{wordUsageMap}}、{{targetGrade}} 和
            {{qualityRound}}，逐词核对是否出现、词形是否允许、词义是否自然、上下文是否足以帮助理解，并诊断语法、句型、
            指代和年级适配问题。

            对每项问题给出目标词或原句证据、准确位置、严重级别和修订约束，同时列出已正确使用且应保护的内容。你只能
            诊断，不得改写故事正文、替换整段、给综合总分、改变通过线或选择流程动作。
            """.strip();

    private static final String REVIEW_CONTINUITY_PROMPT = """
            你是独立的剧情连续性审核员。对照 {{storyBlueprint}} 审阅 {{candidateStory}} 和 {{qualityRound}}，检查主角、
            主线目标、场景顺序、因果关系、冲突升级、转场、伏笔与结尾回扣是否一致。指出无因结果、角色动机跳变、设定冲突、
            重复场景或未兑现铺垫，并标明证据位置和严重级别。

            还要列出已经连贯且后续必须保护的结构。你只输出诊断报告；严禁重写、补写或润色故事正文，严禁计算综合总分、
            修改量表、宣布通过或选择回退路线。
            """.strip();

    private static final String STORY_SCORER_PROMPT = """
            你是与创作团队独立的故事评分员。根据 {{scoringRubric}}，盲评 {{candidateStory}}，并参考
            {{funReview}}、{{languageReview}} 与 {{continuityReview}} 中可核验的证据。逐个量表维度给出分数、简短理由、
            正反证据和置信度；同样问题不得在多个维度重复扣分。

            你只能按固定量表评分，不得改写或建议替换故事正文，不得修改权重、通过线或预算，不得计算由确定性程序负责的
            最终加权结果，也不得宣布 PASS 或选择任何回退路线。
            """.strip();

    private static final String QUALITY_DECIDER_PROMPT = """
            你是质量决策人。读取 {{reviewReports}}、{{scoreReport}}、{{budgetState}} 和 {{revisionHistory}}，只依据已有证据、
            未解决问题的最小责任范围和剩余确定性预算，选择一个动作：PASS、REVISE、REWRITE、REDIRECT、REPITCH 或 REPLAN。

            PASS 仅用于没有阻断问题且确定性分数达到通过线；局部问题选 REVISE，正文整体失效选 REWRITE，蓝图失效选
            REDIRECT，创意前提失效选 REPITCH，用词策划失效选 REPLAN。第一行必须严格输出
            ACTION: PASS|REVISE|REWRITE|REDIRECT|REPITCH|REPLAN 中的唯一一个动作；第二行输出 TARGET_NODE: 节点键，
            随后再输出证据化理由和最小问题清单。
            严禁创作或改写正文、篡改审核事实或分数、调整通过线和预算，也不得输出受限集合之外的动作。
            """.strip();

    private static final String TARGETED_REVISER_PROMPT = """
            你是定向修订 Agent。依据 {{candidateStory}}、{{issueList}}、{{protectedPasses}}、{{vocabularyPlan}} 和
            {{storyBlueprint}}，只修改问题清单明确指出的位置，并保留审核已经通过的内容、同一主角、主线、场景顺序和正确的
            目标词用法。不得顺手重写无关段落，也不得引入新的剧情分支。

            只输出修订后的完整英文故事正文，场景标题使用“Scene N: Plain English Title”的纯文本格式，不输出中文说明、
            Markdown、分析、清单或附录。若某项要求互相冲突，优先保护已通过内容，不要擅自扩大修改范围。不得修改分数、
            量表、通过线、预算或流程路由。
            """.strip();

    private static final List<StageDefinition> STAGES = List.of(
            new StageDefinition("planning", "策划与创意", "目标词到三个匿名提案", 1),
            new StageDefinition("writing", "写作与候选", "同一主角、同一主线、逐场升级", 2),
            new StageDefinition("quality", "独立质量委员会", "审核、评分与决策完全分离", 3),
            new StageDefinition("delivery", "修订与交付", "通过后进入人工审核", 4));

    private static final List<NodeDefinition> NODES = List.of(
            new NodeDefinition(
                    "word-pack",
                    "Word Pack",
                    "PROGRAM",
                    "INPUT",
                    "planning",
                    10,
                    null,
                    "程序输入节点，提供目标词、目标年级和材料来源；该节点不调用模型。",
                    List.of(),
                    List.of(),
                    List.of("vocabulary-planner"),
                    "",
                    "",
                    0.0,
                    false),
            new NodeDefinition(
                    "vocabulary-planner",
                    "用词策划 Agent",
                    "AGENT",
                    "PLANNER",
                    "planning",
                    20,
                    null,
                    "规划目标词的词义、词形、分组和剧情机会，不写故事正文。",
                    List.of("targetWords", "targetGrade", "sourceContext"),
                    List.of("word-pack", "quality-decider"),
                    List.of("pitch-humor", "pitch-adventure", "pitch-wonder"),
                    VOCABULARY_PLANNER_PROMPT,
                    "Flash Medium",
                    0.3,
                    true),
            new NodeDefinition(
                    "pitch-humor",
                    "幽默创意 Agent",
                    "AGENT",
                    "PITCH",
                    "planning",
                    30,
                    "story-pitches",
                    "从幽默方向提交故事提案和场景节拍，不写正文。",
                    List.of("vocabularyPlan", "targetGrade", "priorQualityFeedback"),
                    List.of("vocabulary-planner", "quality-decider"),
                    List.of("story-director"),
                    PITCH_HUMOR_PROMPT,
                    "Flash High",
                    0.8,
                    true),
            new NodeDefinition(
                    "pitch-adventure",
                    "冒险创意 Agent",
                    "AGENT",
                    "PITCH",
                    "planning",
                    31,
                    "story-pitches",
                    "从安全、递进的冒险方向提交故事提案，不写正文。",
                    List.of("vocabularyPlan", "targetGrade", "priorQualityFeedback"),
                    List.of("vocabulary-planner", "quality-decider"),
                    List.of("story-director"),
                    PITCH_ADVENTURE_PROMPT,
                    "Flash High",
                    0.8,
                    true),
            new NodeDefinition(
                    "pitch-wonder",
                    "奇想创意 Agent",
                    "AGENT",
                    "PITCH",
                    "planning",
                    32,
                    "story-pitches",
                    "从有规则、有因果的奇想方向提交故事提案，不写正文。",
                    List.of("vocabularyPlan", "targetGrade", "priorQualityFeedback"),
                    List.of("vocabulary-planner", "quality-decider"),
                    List.of("story-director"),
                    PITCH_WONDER_PROMPT,
                    "Flash High",
                    0.8,
                    true),
            new NodeDefinition(
                    "story-director",
                    "故事导演 Agent",
                    "AGENT",
                    "DIRECTOR",
                    "planning",
                    40,
                    null,
                    "匿名选案并生成同一主角、同一主线的连续场景蓝图，不写正文。",
                    List.of("vocabularyPlan", "anonymousPitches", "targetGrade", "directorFeedback"),
                    List.of("pitch-humor", "pitch-adventure", "pitch-wonder", "quality-decider"),
                    List.of("story-writer"),
                    STORY_DIRECTOR_PROMPT,
                    "Pro",
                    0.4,
                    true),
            new NodeDefinition(
                    "story-writer",
                    "故事作家 Agent",
                    "AGENT",
                    "WRITER",
                    "writing",
                    10,
                    null,
                    "依据用词方案和场景蓝图写完整故事正文及目标词位置清单。",
                    List.of("vocabularyPlan", "storyBlueprint", "targetGrade", "writerFeedback"),
                    List.of("story-director", "quality-decider"),
                    List.of("hard-rule-check"),
                    STORY_WRITER_PROMPT,
                    "Pro",
                    0.7,
                    true),
            new NodeDefinition(
                    "hard-rule-check",
                    "硬规则校验",
                    "PROGRAM",
                    "VALIDATOR",
                    "writing",
                    20,
                    null,
                    "确定性检查目标词覆盖、输出结构和基础硬约束；该节点不调用模型。",
                    List.of(),
                    List.of("story-writer", "targeted-reviser"),
                    List.of("candidate-snapshot"),
                    "",
                    "",
                    0.0,
                    false),
            new NodeDefinition(
                    "candidate-snapshot",
                    "候选版本快照",
                    "PROGRAM",
                    "SNAPSHOT",
                    "writing",
                    30,
                    null,
                    "保存候选版本、质量轮次和历史最高分版本；该节点不调用模型。",
                    List.of(),
                    List.of("hard-rule-check"),
                    List.of("review-fun", "review-language", "review-continuity"),
                    "",
                    "",
                    0.0,
                    false),
            new NodeDefinition(
                    "review-fun",
                    "趣味审核员",
                    "AGENT",
                    "REVIEWER",
                    "quality",
                    10,
                    "quality-reviewers",
                    "独立诊断钩子、节奏、惊喜和结尾回报，不改正文、不打总分。",
                    List.of("candidateStory", "targetGrade", "qualityRound"),
                    List.of("candidate-snapshot"),
                    List.of("story-scorer"),
                    REVIEW_FUN_PROMPT,
                    "Flash High",
                    0.2,
                    true),
            new NodeDefinition(
                    "review-language",
                    "语言用词审核员",
                    "AGENT",
                    "REVIEWER",
                    "quality",
                    11,
                    "quality-reviewers",
                    "独立诊断目标词自然度、语法和年龄适配，不改正文、不打总分。",
                    List.of("candidateStory", "targetWords", "wordUsageMap", "targetGrade", "qualityRound"),
                    List.of("candidate-snapshot"),
                    List.of("story-scorer"),
                    REVIEW_LANGUAGE_PROMPT,
                    "Flash Medium",
                    0.1,
                    true),
            new NodeDefinition(
                    "review-continuity",
                    "剧情连续性审核员",
                    "AGENT",
                    "REVIEWER",
                    "quality",
                    12,
                    "quality-reviewers",
                    "独立诊断主线、因果、升级和场景衔接，不改正文、不打总分。",
                    List.of("candidateStory", "storyBlueprint", "qualityRound"),
                    List.of("candidate-snapshot"),
                    List.of("story-scorer"),
                    REVIEW_CONTINUITY_PROMPT,
                    "Flash High",
                    0.2,
                    true),
            new NodeDefinition(
                    "story-scorer",
                    "独立评分员",
                    "AGENT",
                    "SCORER",
                    "quality",
                    20,
                    null,
                    "依据固定量表盲评并给出证据，只评分，不修改正文或通过线。",
                    List.of("candidateStory", "funReview", "languageReview", "continuityReview", "scoringRubric"),
                    List.of("review-fun", "review-language", "review-continuity"),
                    List.of("quality-decider"),
                    STORY_SCORER_PROMPT,
                    "Pro",
                    0.1,
                    true),
            new NodeDefinition(
                    "quality-decider",
                    "质量决策人",
                    "AGENT",
                    "DECIDER",
                    "quality",
                    30,
                    null,
                    "根据审核证据、评分和剩余预算选择受限路由动作，不创作、不改分。",
                    List.of("reviewReports", "scoreReport", "budgetState", "revisionHistory"),
                    List.of("story-scorer"),
                    List.of(
                            "targeted-reviser",
                            "story-writer",
                            "story-director",
                            "pitch-humor",
                            "pitch-adventure",
                            "pitch-wonder",
                            "vocabulary-planner"),
                    QUALITY_DECIDER_PROMPT,
                    "Pro",
                    0.0,
                    true),
            new NodeDefinition(
                    "targeted-reviser",
                    "定向修订 Agent",
                    "AGENT",
                    "REVISER",
                    "delivery",
                    10,
                    null,
                    "只修改问题清单指出的位置，输出修订正文并保护已通过内容。",
                    List.of("candidateStory", "issueList", "protectedPasses", "vocabularyPlan", "storyBlueprint"),
                    List.of("quality-decider"),
                    List.of("hard-rule-check"),
                    TARGETED_REVISER_PROMPT,
                    "Pro",
                    0.3,
                    true),
            new NodeDefinition(
                    "budget-controller",
                    "确定性预算控制器",
                    "PROGRAM",
                    "CONTROLLER",
                    "delivery",
                    20,
                    null,
                    "在每次路由前检查质量轮次、各类回退次数和总 Token 上限；该节点不调用模型。",
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    "",
                    0.0,
                    false),
            new NodeDefinition(
                    "human-review",
                    "人工审核",
                    "HUMAN",
                    "HUMAN_REVIEW",
                    "delivery",
                    30,
                    null,
                    "接收通过版本或预算耗尽时的历史最高分版本，供用户评分、批注和人工改稿。",
                    List.of(),
                    List.of("quality-decider"),
                    List.of(),
                    "",
                    "",
                    0.0,
                    false));

    private static final List<NodeDefinition> AGENTS = NODES.stream()
            .filter(NodeDefinition::editable)
            .toList();

    private static final Map<String, NodeDefinition> NODES_BY_KEY = NODES.stream()
            .collect(Collectors.toUnmodifiableMap(NodeDefinition::key, Function.identity()));

    private StoryAgentCatalog() {
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
            throw new IllegalArgumentException("Unknown story flow node: " + key);
        }
        return node;
    }

    public record StageDefinition(String key, String name, String note, int order) {
        public StageDefinition {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(note, "note");
        }
    }

    public record NodeDefinition(
            String key,
            String name,
            String nodeKind,
            String roleType,
            String stageKey,
            int order,
            String parallelGroup,
            String description,
            List<String> variables,
            List<String> upstream,
            List<String> downstream,
            String defaultPrompt,
            String modelPreference,
            double defaultTemperature,
            boolean editable) {
        public NodeDefinition {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(nodeKind, "nodeKind");
            Objects.requireNonNull(roleType, "roleType");
            Objects.requireNonNull(stageKey, "stageKey");
            Objects.requireNonNull(description, "description");
            variables = List.copyOf(variables);
            upstream = List.copyOf(upstream);
            downstream = List.copyOf(downstream);
            Objects.requireNonNull(defaultPrompt, "defaultPrompt");
            Objects.requireNonNull(modelPreference, "modelPreference");
        }
    }
}
