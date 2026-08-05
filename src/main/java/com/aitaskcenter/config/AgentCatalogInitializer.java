package com.aitaskcenter.config;

import com.aitaskcenter.dto.AgentDefinitionRequest;
import com.aitaskcenter.dto.LocalCliConfigItem;
import com.aitaskcenter.model.AgentDefinition;
import com.aitaskcenter.service.AgentService;
import com.aitaskcenter.service.AiConfigService;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AgentCatalogInitializer implements ApplicationRunner {
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "required": ["stage_profile", "target_words"],
              "properties": {
                "stage_profile": { "type": "string" },
                "target_words": { "type": "array", "minItems": 1 },
                "context": { "type": "object" }
              }
            }
            """;
    private static final String OUTPUT_SCHEMA = """
            {
              "type": "object",
              "required": ["result"],
              "properties": {
                "result": { "type": "object" },
                "notes": { "type": "array" }
              }
            }
            """;
    private static final String TEMPLATE = "请根据以下结构化输入完成当前职责：\n{{input}}\n\n严格按照输出 Schema 返回 JSON。";
    private static final String RULES = "不得遗漏输入中的目标词 ID；不得擅自改变目标学段；无法可靠完成时在 notes 中说明，不得编造数据。";
    private static final String RUBRIC = "任务符合度 30%，数据完整性 25%，学段适配 20%，自然度或可执行性 15%，结构清晰度 10%。";

    private final AgentService agentService;
    private final AiConfigService aiConfigService;

    public AgentCatalogInitializer(AgentService agentService, AiConfigService aiConfigService) {
        this.agentService = agentService;
        this.aiConfigService = aiConfigService;
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalCliConfigItem defaultCli = aiConfigService.getDefaultLocalCliForExecution();
        String providerId = defaultCli.getId();
        List<AgentDefinition> existing = agentService.list();
        if (!existing.isEmpty()) {
            for (AgentDefinition definition : existing) {
                if (!providerId.equals(definition.getAiProviderId())) {
                    agentService.update(definition.getId(), copyWithProvider(definition, providerId));
                }
            }
            return;
        }
        List<Seed> seeds = List.of(
                new Seed("semantic-grouping", "单词语义分组 Agent", "planning", "按主题与语义关联均衡划分目标词组。", "你是英语词汇语义分组专家。根据释义、词性、学段和简单主题标签完成均衡分组，优先保证组内词汇能够自然进入同一故事。"),
                new Seed("word-usage", "词义与用法 Agent", "planning", "确定每个目标词在故事中的词义、搭配与适用语境。", "你是英语教学用法规划专家。为每个目标词选择适龄词义、词性、自然搭配和可使用场景。"),
                new Seed("story-portfolio", "故事组总策划 Agent", "planning", "为同一册次的多个故事规划主题和差异化方向。", "你是英语学习故事系列总策划。根据单词分组设计相互区分但适合目标学段的故事主题与角色关系。"),
                new Seed("story-structure", "单故事结构 Agent", "creation", "设计人物、目标、冲突、场景和结局。", "你是适龄英语故事结构编剧。为一个词组设计连贯、有趣、可分镜的多场景故事结构。"),
                new Seed("scene-allocation", "场景分词 Agent", "creation", "将全部目标词精确分配到故事场景。", "你是故事场景词汇调度专家。把目标词按语义和情节需要分配到场景，并保持完整覆盖与场景负担均衡。"),
                new Seed("dialogue-writer", "对话编剧 Agent", "creation", "生成自然、生动且符合学段的场景对白。", "你是英语学习对话编剧。围绕角色目标写自然对话，避免为了塞入单词而突然改变话题。"),
                new Seed("teaching-enhancer", "教学强化 Agent", "creation", "安排语境提示、自然复现和结尾回顾。", "你是英语教学设计师。在不破坏故事节奏的前提下增强目标词的可理解输入、自然复现和回顾。"),
                new Seed("stage-reviewer", "学段难度审核 Agent", "review", "检查词汇、语法、句长和信息量是否适龄。", "你是严格的英语学段审核员。只评估候选内容的词汇、语法、句长和认知负担，列出可定位的问题。"),
                new Seed("naturalness-reviewer", "对话自然度审核 Agent", "review", "检查对白是否真实、连贯并推动情节。", "你是英语对话自然度评审。识别不符合人物语境、强行插词、重复或不推动情节的对白。"),
                new Seed("story-reviewer", "故事趣味与连贯审核 Agent", "review", "评估故事目标、转场、冲突、趣味和可视化程度。", "你是儿童与青年英语故事评审。评估故事连贯性、趣味性、适龄程度和分镜潜力。"),
                new Seed("content-reviser", "内容修订 Agent", "review", "根据结构化问题清单定向修订内容。", "你是谨慎的内容修订编辑。只处理评审指出的问题，保留已正确使用的目标词和既定故事结构。"),
                new Seed("storyboard", "分镜 Agent", "visual", "把场景拆为可生成图片的连续画面。", "你是教学漫画分镜师。将场景拆成清晰画面，描述人物动作、表情、重点物体和对白归属。"),
                new Seed("visual-director", "视觉导演 Agent", "visual", "维护角色、服装、场景和画风一致性。", "你是英语学习图像项目视觉导演。建立可复用的角色表、环境表和镜头规则，确保多场景一致。"),
                new Seed("image-prompt", "生图提示词 Agent", "visual", "将分镜转换为不含文字的结构化生图提示词。", "你是图像生成提示词工程师。根据分镜和视觉规则生成无文字画面提示词，并保留必要的角色与场景约束。"),
                new Seed("image-reviewer", "图片质检 Agent", "visual", "检查图片与分镜、人物和目标物体的一致性。", "你是教学图片质检员。根据分镜逐项检查角色一致性、动作、场景、目标物体、安全性和适龄性。"),
                new Seed("exercise-generator", "练习生成 Agent", "learning", "根据故事生成理解、词义、排序和角色扮演练习。", "你是英语练习设计师。根据已验收故事生成适龄、可判分且覆盖目标词的练习。")
        );
        int order = 10;
        for (Seed seed : seeds) {
            agentService.create(new AgentDefinitionRequest(seed.key(), seed.name(), seed.category(), seed.description(),
                    providerId, seed.systemPrompt(), TEMPLATE, INPUT_SCHEMA, OUTPUT_SCHEMA, RULES, RUBRIC,
                    "creation".equals(seed.category()) ? 0.6 : 0.2, 4096, 1, order));
            order += 10;
        }
    }

    private AgentDefinitionRequest copyWithProvider(AgentDefinition value, String providerId) {
        return new AgentDefinitionRequest(value.getAgentKey(), value.getName(), value.getCategory(),
                value.getDescription(), providerId, value.getSystemPrompt(), value.getPromptTemplate(),
                value.getInputSchema(), value.getOutputSchema(), value.getHardRules(), value.getEvaluationRubric(),
                value.getTemperature(), value.getMaxTokens(), value.getRetryLimit(), value.getSortOrder());
    }

    private record Seed(String key, String name, String category, String description, String systemPrompt) { }
}
