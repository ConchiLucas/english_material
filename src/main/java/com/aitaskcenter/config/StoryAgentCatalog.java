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
            的约束。每个目标词必须标明建议场景序号和角色 MUST 或 BACKGROUND：MUST 词要能单独支撑该场一个可见动作
            或物件，BACKGROUND 只作颜色、数量或地点修饰。词数大于 8 时必须给出分场表，一场 MUST 词不超过 4 个。
            建议场景默认 1 场；只有可入画动作将超过 16 个时才建议第 2 场，每场必须能支撑 8 到 16 个画面。
            可以自由选择最适合剧情且小学生能从语境理解的词义。严禁撰写故事正文、场景对白或完整故事
            段落，也不要替创意 Agent 决定唯一题材。
            """.strip();

    private static final String PITCH_HUMOR_PROMPT = """
            你是幽默故事创意 Agent。根据 {{vocabularyPlan}}、{{targetGrade}} 和 {{priorQualityFeedback}}，提交一份
            可由同一主角贯穿的英文故事提案。提案应说明核心钩子、主角目标、逐场升级的误会或笑点、目标词如何
            自然推动情节，以及结尾如何回扣开头。

            优先采用孩子能理解的处境幽默和角色反差，不依赖羞辱、危险模仿或成人梗。覆盖策划中的全部目标词，
            但只输出提案、场景节拍和风险提示；严禁写故事正文、成段对白或冒充导演产出最终蓝图。
            导演可能只吸收你的一条要素，要点必须可单独移植。每个场景列出 8 到 16 个可入画动作；不超过 16 个只规划 1 场，超过再拆成每场 8 到 16 个，例如 8 和 9。
            """.strip();

    private static final String PITCH_ADVENTURE_PROMPT = """
            你是冒险故事创意 Agent。根据 {{vocabularyPlan}}、{{targetGrade}} 和 {{priorQualityFeedback}}，提交一份
            适合小学 3—6 年级、由同一主角和同一主线贯穿的冒险提案。明确任务目标、障碍如何逐场升级、每组目标词
            如何成为行动线索或解决问题的工具，以及结尾怎样兑现前面的铺垫。

            冒险必须紧张但安全，不使用血腥、恐怖或不可模仿的危险行为。只输出提案、场景节拍、用词机会和风险，
            覆盖全部目标词；严禁撰写故事正文、完整对白或替导演完成最终场景蓝图。
            导演可能只吸收你的一条要素，要点必须可单独移植。每个场景列出 8 到 16 个可入画动作；不超过 16 个只规划 1 场，超过再拆成每场 8 到 16 个，例如 8 和 9。
            """.strip();

    private static final String PITCH_WONDER_PROMPT = """
            你是奇想故事创意 Agent。根据 {{vocabularyPlan}}、{{targetGrade}} 和 {{priorQualityFeedback}}，提出一个
            有清晰规则的奇想世界或神奇事件，让同一主角在连续场景中发现规则、遭遇升级的冲突并利用目标词解决问题。
            提案需交代惊奇点、因果链、目标词的自然落点和结尾回扣，且让少量生词可由上下文理解。

            奇想不能取代逻辑：每次变化都要有可追踪的原因和结果。只输出创意提案、场景节拍、用词机会和风险；
            严禁写故事正文、完整对白或替导演决定最终蓝图。
            导演可能只吸收你的一条要素，要点必须可单独移植。每个场景列出 8 到 16 个可入画动作；不超过 16 个只规划 1 场，超过再拆成每场 8 到 16 个，例如 8 和 9。
            """.strip();

    private static final String STORY_DIRECTOR_PROMPT = """
            你是故事导演 Agent。读取 {{vocabularyPlan}}、已去除作者身份的 {{anonymousPitches}}、{{targetGrade}} 和
            {{directorFeedback}}，先按趣味性、可讲述性、用词自然度与连续性选择或融合最佳提案，再产出可执行的连续
            场景蓝图。不得因偏爱某个创意角色而加分。

            蓝图必须固定同一主角和主线，给出每场的标题、目标、冲突升级、关键因果、目标词落点、转场以及最终回扣；
            必须点名 MAIN_PITCH、SCENE_COUNT 和 BEAT_COUNTS，并从另外两份提案各吸收一条可核对要素。
            每一场写明 SetupRequired，以及 8 到 16 个可入画动作 PictureBeat。可入画动作不超过 16 个时只规划 1 场；
            超过 16 个必须拆场，每场仍是 8 到 16 个，例如 8 和 9，不要拆出薄场。只输出选案理由和结构化蓝图，严禁撰写
            英文故事正文、完整对白或替作家润色句子。
            """.strip();

    private static final String STORY_WRITER_PROMPT = """
            你是故事作家 Agent。依据 {{vocabularyPlan}}、{{storyBlueprint}}、{{targetGrade}} 和 {{writerFeedback}} 写出
            一篇完整英文故事。严格保持蓝图中的同一主角、同一主线、逐场升级与结尾回扣；每个场景都要有简短标题，
            句型和用词：未指定学段或初中及以后不管超纲。小学目标只要不超过高中常见词即可，不要删 cardboard、paw、sigh、
            sunglasses 这类能入画的词；只有大学学术词或专业术语才要避开。

            必须自然使用全部目标词，允许策划认可的时态、单复数等词形变化，不得为凑词写无关句。句子短、动词具体，
            禁止使役和从句。只输出完整英文故事正文，
            场景标题使用“Scene N: Plain English Title”的纯文本格式。每一个 Scene 写 5 到 8 个短段落，写出 8 到 16 个
            看得见的连续动作。第一段必须立刻写出可见冲突或意外动作，禁止 sits / walks to the desk / takes out a book
            这类摆拍开场。猫用 The cat 或 It，不要用 He 指猫。可入画动作不超过 16 个时只写 1 个 Scene；超过 16 个再开一场，拆成例如 8 和 9，不要一场
            三四句就换场。
            不输出中文说明、Markdown、分析、清单或附录。若收到重写反馈，应重写正文但保留仍然有效的蓝图约束，不得自行修改评分、通过线或路由决定。
            """.strip();

    private static final String REVIEW_FUN_PROMPT = """
            你是独立的趣味审核员。只审阅 {{candidateStory}}，并结合 {{targetGrade}} 与 {{qualityRound}} 诊断故事的开篇
            钩子、节奏、角色吸引力、惊喜、冲突升级和结尾回报。开篇问题必须指出如何换成可见冲突动作，不得建议加
            happily 这类空情绪词。每个问题都要引用简短证据、标出场景或段落位置、说明
            对儿童阅读体验的影响并给出严重级别。

            同时列出已经通过、后续修订应保护的趣味要素。你只能输出诊断报告和可验证的问题清单；严禁重写或续写正文，
            严禁给出综合总分、改变量表、宣布通过或选择回退路线。
            """.strip();

    private static final String REVIEW_LANGUAGE_PROMPT = """
            你是独立的语言与用词审核员。审阅 {{candidateStory}}、{{targetWords}}、{{wordUsage}}、{{targetGrade}} 和
            {{qualityRound}}。wordUsage 已由程序扫描，不要手搓全词表；重点诊断句型、语法和指代，
            并核对扫描结果中未覆盖的词。未指定学段或初中及以后不要报超纲。小学稿里高中及以内的词都不要列为超纲；
            只有大学学术词或专业术语才报年级问题。

            对每项问题给出目标词或原句证据、准确位置、严重级别和修订约束，同时列出已正确使用且应保护的内容。你只能
            诊断，不得改写故事正文、替换整段、给综合总分、改变通过线或选择流程动作。
            """.strip();

    private static final String REVIEW_CONTINUITY_PROMPT = """
            你是独立的剧情连续性审核员。对照 {{storyBlueprint}} 审阅 {{candidateStory}} 和 {{qualityRound}}，检查主角、
            主线目标、场景顺序、因果关系、冲突升级、转场、伏笔与结尾回扣是否一致，并核对蓝图中的 SetupRequired 是否兑现。
            指出无因结果、角色动机跳变、设定冲突、重复场景或未兑现铺垫，并标明证据位置和严重级别。

            还要列出已经连贯且后续必须保护的结构。你只输出诊断报告；严禁重写、补写或润色故事正文，严禁计算综合总分、
            修改量表、宣布通过或选择回退路线。
            """.strip();

    private static final String STORY_SCORER_PROMPT = """
            你是与创作团队独立的故事评分员。根据 {{scoringRubric}}，盲评 {{candidateStory}}，并参考
            {{funReview}}、{{languageReview}} 与 {{continuityReview}} 中可核验的证据。逐个量表维度给出分数、简短理由、
            正反证据和置信度；同样问题不得在多个维度重复扣分。

            四个维度均使用 1 到 5 的整数，同一问题不得在多个维度重复扣分。grade：未指定学段或初中及以后给 4 或 5，不要因超纲扣分。
            小学目标用词不超过高中就给 3 分及以上，不得因 cardboard、paw、sigh、sunglasses 扣分；只有大学学术词、专业术语或禁止句法才扣 grade。
            简单主谓宾短句不得降低 grade。你只能按固定量表评分，不得改写或建议替换故事正文，
            不得修改权重、通过线或预算，不得计算由确定性程序负责的最终加权结果，也不得宣布 PASS 或选择任何回退路线。
            """.strip();

    private static final String QUALITY_DECIDER_PROMPT = """
            你是质量决策人。读取 {{reviewReports}}、{{scoreReport}}、{{budgetState}} 和 {{revisionHistory}}，只依据已有证据、
            未解决问题的最小责任范围和剩余确定性预算，选择一个动作：PASS、REVISE 或 REWRITE。

            PASS 仅用于没有缺词、语言和连续性都不低于 3。未指定学段或初中及以后不要开 GRADE 问题。
            小学稿不要为高中及以内的词开 GRADE 或因此 REVISE/REWRITE。趣味问题只能 REVISE。
            语言低于 3 或年级为 1（离谱大学/专业词）时先选 REVISE，replaceWith 必须保住可见动作，禁止收成 is happy，
            也不得把 jumps/slides/puts 收成 moves，但可以换成另一个画面动词。不要删 paw/page/rug。FUN 只能换成另一个可见动作，禁止只加 happily。
            只有已经修订过一轮语言仍低于 3 或年级仍是 1，或同时缺目标词，才选 REWRITE。禁止要求加入从句、使役、后置定语或更文学的句式。
            问题清单最多 4 条，每条必须给出可替换的英文原句 replaceWith。第一行必须严格输出 ACTION: PASS|REVISE|REWRITE；
            不要输出 TARGET_NODE，也不要输出 REPLAN、REPITCH 或 REDIRECT。
            严禁创作或改写正文、篡改审核事实或分数、调整通过线和预算，也不得输出受限集合之外的动作。
            """.strip();

    private static final String TARGETED_REVISER_PROMPT = """
            你是定向修订 Agent。依据 {{candidateStory}}、{{issueList}}、{{protectedPasses}}、{{vocabularyPlan}} 和
            {{storyBlueprint}}，只修改问题清单明确指出的位置，并保留审核已经通过的内容、同一主角、主线、场景顺序和正确的
            目标词用法。不得顺手重写无关段落，也不得引入新的剧情分支。

            issueList 是结构化问题清单。按每条 scene、quote、replaceWith 把原句换成 replaceWith；type 为 GRADE 或 LANGUAGE 时只拆句或换词，不得加从句，也不得把动作收成状态句或加 happily。FUN 必须换成可见动作。
            必须保持蓝图中的 Scene 数量，不得把一场收到不够 8 个可画动作；只有一场将超过 16 个可画动作时才允许按蓝图拆成例如 8 和 9。protect 为 true 的 quote 不得改动。只输出修订后的完整英文故事正文，场景标题使用“Scene N: Plain English Title”的纯文本格式，不输出中文说明、
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
                    "依据用词方案和场景蓝图写完整英文故事正文。",
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
                    "画布拓扑节点。故事结构由 writer/reviser 的运行时输出协议校验；本节点不单独执行，也不调用模型。",
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
                    "画布拓扑节点。运行记录保存每轮候选正文；批次在通过或预算耗尽时保留最后一稿，本节点不单独执行。",
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
                    List.of("candidateStory", "targetWords", "wordUsage", "targetGrade", "qualityRound"),
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
                    List.of("targeted-reviser", "story-writer"),
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
                    "画布拓扑节点。质量轮次、回退次数和 Token 上限由执行器在每次模型调用前检查；本节点不单独执行。",
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
                    "运行记录页供人工查看通过版本或预算耗尽时的最后一稿；本节点不调用模型，也不写入审核状态。",
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
