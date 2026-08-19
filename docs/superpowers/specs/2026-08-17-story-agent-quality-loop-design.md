---
title: 故事 Agent 终稿质量闭环设计
summary: 在不删减有效创作与审核 Agent 的前提下，让策划、写作、审核、评分和回退真正改善最终英文故事，而不是只烧满预算。
---

# 故事 Agent 终稿质量闭环设计

本文是开发规格。审核通过后按本文实现，不以精简画布为目的。三个创意 Agent 和三个审核员保留；评分员保留但必须能卡住回退；程序节点不新增模型调用。

## 1. 背景与证据

实跑与历史批次表明：策划和蓝图大体可用，审核能指出正确问题，但终稿经常停在 `LIMIT_REACHED`，且修订没有把已诊断问题改掉。

| 批次 | 状态 | 终稿质量观察 |
| --- | --- | --- |
| `b39d0a59`（3 词，2026-08-17 新跑） | `LIMIT_REACHED`，23 步 | 四场完整，词全覆盖。超纲句（`sighs`、`makes the book his bed`）和红颜料/红脚印无铺垫，两轮 `REVISE` 后仍在。第 3 轮决策要 `REWRITE`，轮次先耗尽，作家未再写。 |
| `b9196743`（20 词） | `LIMIT_REACHED` | 20 词全出现，但 `eight crayons`、`four thick legs` 等为凑词。开篇教材腔、冲突同质。同样两轮微调后要重写却未执行。 |
| `9405d5d7`（旧完成） | `COMPLETED` | 修订把中文清单写进 `finalStory`。现有 `extractStory` 已堵住这类交付事故，本规格继续保留该协议。 |

根因不是 Agent 太少，而是：

1. 作家/修订没有年级句法硬约束，审核指出超纲后仍用微调。
2. 决策偏软：年级/语言已是阻断仍先 `REVISE` 两次。
3. 执行器在应用动作前用 `qualityRound >= maxQualityRounds` 截断，最后一轮 `REWRITE` 不会发生。
4. 修订拿到的是整篇决策散文，又被要求“只改点名位置”，只能改开篇皮毛。
5. `wordUsageMap` 是固定中文句子，语言审核把篇幅耗在手搓词表上。
6. 评分分数不被解析，不决定 `PASS`/`REWRITE`，分数还会在轮次间漂移。
7. 导演常整份采用一份提案，另外两份创意不进蓝图。
8. 策划对多词任务没有分场与“必现/背景”分工，作家只能清单式塞词。

## 2. 目标

- 终稿仍只保存纯英文 `Scene N: Title` + 段落，由现有 `extractStory` 保证。
- 三个 pitch 的创意必须在蓝图里留痕，不能只留一份赢家。
- 作家和修订默认写出目标年级能读的短句；超纲结构视为未完成职责。
- 三个审核员继续分工，但输入不再雷同；语言审核使用程序扫描的词表。
- 评分员输出可解析分数；年级或语言低于通过线时，执行器不得把动作落成 `REVISE`。
- `REVISE` 吃结构化问题清单；`REWRITE` 在本轮决策之后必须能真正调用作家。
- 缺目标词不得 `PASS`。`LIMIT_REACHED` 交付已评分候选中的最高分稿；若最后一次动作刚写出尚未再评分的正文，交付该正文。
- 不新增 Agent、不新增表、不新增 HTTP 接口。不覆盖数据库里已有用户 Prompt。

## 3. 非目标

- 不实现人工审核写入、暂停、删除、跨重启续跑。
- 本版不复活 `REPLAN` / `REPITCH` / `REDIRECT`。代码路径可保留，决策合同与解析不再把它们当作合法动作。
- 不把五个程序/人工节点做成模型 Agent。`hard-rule-check` 的词覆盖由执行器函数完成，并写入作家/修订之后的审核输入。
- 不改图片工作台。
- 不修改历史批次记录。

## 4. 锁定决策

| 议题 | 决定 |
| --- | --- |
| 三个 pitch | 保留。导演必须点名主提案，并从另外两份各吸收一条可追踪要素。 |
| 三个审核员 | 保留。各自只收 Catalog 声明的变量。 |
| 评分员 | 保留。必须输出可解析分数，并作为阻断升级的硬输入。 |
| 决策动作 | 本版只允许 `PASS`、`REVISE`、`REWRITE`。 |
| 程序节点 | 画布保留，说明保持“不调用模型”。词覆盖与最高分选择在执行器内完成，不落独立 `PROGRAM` 步骤。 |
| 已有 Prompt | `StoryAgentInitializer` 仍只补缺。格式、评分块、决策行、年级句法由运行时协议追加，不改写 `tb_story_agent_config.system_prompt`。 |
| 年级 | 以批次 `targetGrade` 为准。运行时协议按该字符串约束句式，不写死“三年级上册”以外的词表。 |

## 5. 目标执行图

```text
vocabulary-planner
  -> pitch-humor ‖ pitch-adventure ‖ pitch-wonder
  -> story-director（主提案 + 两份吸收项 + 分场铺垫）
  -> story-writer
  -> extractStory + scanTargetWords
  -> review-fun ‖ review-language ‖ review-continuity
  -> story-scorer（可解析分数）
  -> quality-decider（首行 ACTION）
       PASS 且无缺词且无阻断分 -> COMPLETED
       REVISE 且仍有局部修订额度 -> targeted-reviser -> 再审
       阻断分或 REWRITE 且仍有重写额度 -> story-writer -> 再审
       动作无法执行 -> LIMIT_REACHED（最高分或刚写出的未评分稿）
```

可编辑 Agent 仍是现在这 12 个。`word-pack`、`hard-rule-check`、`candidate-snapshot`、`budget-controller`、`human-review` 仍只出现在画布上。

## 6. 运行时合同

所有运行时合同由 `StoryRunExecutionService` 追加到对应 Agent 的数据库 Prompt 之后，优先级高于前文输出格式。已包含完全相同文本则不重复追加。

### 6.1 纯故事协议（已有，保留并扩展年级句法）

仍只追加给 `story-writer` 和 `targeted-reviser`。在现有 `STORY_TEXT_BEGIN` / `STORY_TEXT_END` 之外增加年级句法段：

```text
[运行时最终输出协议：此协议优先于前文的输出结构要求]
只输出以下边界之间的纯英文故事，不得在边界外输出任何内容：
STORY_TEXT_BEGIN
Scene 1: Plain English Title

Plain English story paragraphs only.
STORY_TEXT_END
故事块内禁止 Markdown、中文说明、目标词清单、评分信息、变更记录、表格或代码围栏。
句子必须适合本批次目标年级。禁止使役结构 make + 宾语 + 名词补语，禁止宾语从句，禁止定语从句或后置定语从句，禁止分词短语充当后置修饰。每句以常见动作动词或 be 为主，一词一义，不堆抽象心理动词。
```

`extractStory` 现有失败规则不变。年级句法本版只靠 Prompt/合同约束，不做语法树解析；是否超纲仍由语言审核与评分卡住。

### 6.2 导演吸收与铺垫合同

追加给 `story-director`：

```text
[运行时蓝图协议]
第一段必须用固定标题列出：
MAIN_PITCH: humor|adventure|wonder
TAKE_FROM_HUMOR: 一条不超过 40 词的中文或英文要点
TAKE_FROM_ADVENTURE: 一条不超过 40 词的要点
TAKE_FROM_WONDER: 一条不超过 40 词的要点
主提案对应的 TAKE_FROM_* 写“主提案本身”。另外两个不得写“无”或“不采用”，必须是终稿蓝图里能核对的具体要素。
每一场必须包含 SetupRequired: 该场或更早必须先出现、供后场回收的具体物件或信息。
只输出选案说明和蓝图，不写英文故事正文。
```

执行器在导演步骤完成后做弱校验：三个 `TAKE_FROM_*` 行都存在且非空。缺行则该步骤与批次 `FAILED`，不进入作家。不解析创意质量。

### 6.3 评分块

追加给 `story-scorer`：

```text
[运行时评分协议]
先按现有职责写简短证据，最后必须且只能出现一次：
SCORE_BEGIN
fun: <1-5整数>
language: <1-5整数>
continuity: <1-5整数>
grade: <1-5整数>
SCORE_END
不得在块外重复这四行。不得输出小数或等级字母。
```

解析失败则评分步骤 `FAILED`，批次失败。通过线：`language >= 3` 且 `grade >= 3`。`fun`、`continuity` 无论高低都不得单独触发 `REWRITE`。`grade` 只衡量是否好读：超纲才扣分，简单短句重复不扣 `grade`。执行器忽略决策文中的 `BLOCKING` 行，只认本评分块。

综合排序分（仅用于最高分快照）：

```text
total = fun + language + continuity + grade
```

同分保留先产生的候选。

### 6.4 决策行与问题清单

追加给 `quality-decider`：

```text
[运行时决策协议]
第一行必须是：
ACTION: PASS|REVISE|REWRITE
第二行必须是：
BLOCKING: NONE
或
BLOCKING: GRADE
或
BLOCKING: LANGUAGE
或
BLOCKING: GRADE,LANGUAGE
若 ACTION 不是 PASS，随后必须且只能出现一次：
ISSUES_JSON_BEGIN
[{"scene":1,"quote":"原句","type":"GRADE|LANGUAGE|CONTINUITY|FUN|COVERAGE","instruction":"这一句怎么改","replaceWith":"替换后的英文原句","protect":false}]
ISSUES_JSON_END
scene 为正整数。quote、instruction、replaceWith 非空。type 只能是上述枚举。数组至少 1 项、至多 4 项。
禁止要求从句、使役、后置定语或更文学的句式。GRADE/LANGUAGE 只许拆句或换词。
不要输出 TARGET_NODE。不要输出 REPLAN、REPITCH、REDIRECT。执行器忽略 BLOCKING 行。
```

解析规则：

- 跳过开头空行，第一行非空行必须匹配 `ACTION:`。
- 找不到合法 `ACTION`、或 `ACTION` 不是三选一：决策步骤与批次失败。
- 决策里的 `BLOCKING` 可有可无，执行器不采用。
- `ISSUES_JSON` 在 `REVISE`/`REWRITE` 时必填，缺 `replaceWith` 或超过 4 条则决策步骤失败。
- `PASS` 时忽略问题清单。

### 6.5 策划分场合同

追加给 `vocabulary-planner`：

```text
[运行时用词策划协议]
必须为每个目标词给出：词义、允许词形、建议场景序号、角色 MUST 或 BACKGROUND。
MUST 词必须能单独支撑该场一个可见动作或物件；BACKGROUND 词只能作颜色、数量或地点修饰，不得单独成场。
不得丢词。词数大于 8 时必须给出分场表，一场的 MUST 词不超过 4 个。
```

本版不对策划 JSON 做硬解析，只改合同与默认 Prompt。词是否落进正文仍由扫描函数判定。

## 7. 执行器行为

实现集中在 `StoryRunExecutionService`。类名、表名、HTTP 路径不改。

### 7.1 目标词扫描

新增纯函数 `scanTargetWords(List<StoryWord> words, String story)`，大小写不敏感，按单词边界匹配目标词原形及其常见词形（原形、加 `s`/`es`、去 `y` 加 `ies`、动词 `ing`/`ed` 的简单规则）。结果：

```text
[{ "word": "cat", "formsFound": ["cat"], "count": 4, "covered": true }]
```

`covered` 为 `count > 0`。该列表作为 `wordUsage` 传给 `review-language`，不再传那句固定中文。`review-fun` 与 `review-continuity` 不再接收 `wordUsageMap`、`targetWords`（连续性仍收蓝图）。

缺词：`covered == false` 的 MUST 语义本版无法从自由文本策划里可靠区分，因此 **所有目标词都按 MUST 处理**：任一 `covered == false` 即缺词。

### 7.2 审核输入拆分

| Agent | 输入 |
| --- | --- |
| `review-fun` | `candidateStory`, `targetGrade`, `qualityRound` |
| `review-language` | `candidateStory`, `targetWords`, `wordUsage`, `targetGrade`, `qualityRound` |
| `review-continuity` | `candidateStory`, `storyBlueprint`, `qualityRound` |

Catalog 的 `variables` 必须改成与上表一致。前端继续渲染 Catalog 变量，不再依赖执行器多塞字段。

### 7.3 评分解析与阻断升级

`story-scorer` 返回全文仍写入 `output_text`。执行器解析 `SCORE_BEGIN` 块得到四整数。任一分不在 1–5：步骤失败。

```text
blocking = language < 3 || grade < 3
missingWords = any !covered
```

决策解析后（忽略决策自报的 BLOCKING）：

```text
if action == PASS && (blocking || missingWords):
    action = missingWords && !blocking ? REVISE : REWRITE
if blocking && action == REVISE:
    action = REWRITE
if !blocking && action == REWRITE:
    action = REVISE
```

即：只有语言或年级低于 3 才重写；趣味/连续再差也只修订。仅缺词且分数过线则定向修订补词。

### 7.4 回退与轮次顺序

`maxQualityRounds` 表示最多完整审核循环次数（三审 + 评分 + 决策算一轮）。

```text
cycle = 1
bestScored = null          # (total, candidate)
bestFirst = true

writer/reviser 成功抽出 candidate 后：
    本轮尚未评分，不更新 bestScored

每次评分成功后：
    if bestScored == null or total > bestScored.total:
        bestScored = (total, candidate)

循环：
    扫描 wordUsage
    并行三审（拆分输入）
    评分并解析
    决策并解析
    若非 blocking 且非 missingWords，且（ACTION 为 PASS，或已经修订/重写过一轮）:
        COMPLETED，交付评分不低于历史最高的当前稿
    若 cycle > 1 且本轮评分后将要结束，见下
    尝试执行动作：
        REWRITE 且 writerRewrites < maxWriterRewrites
            -> story-writer(writerFeedback = issuesJson + 决策理由)
        REVISE 且 revisions < maxLocalRevisions
            -> targeted-reviser(issueList = issuesJson, protectedPasses = 三审原文)
        否则
            -> LIMIT_REACHED，交付 finishCandidate()
    成功写出新 candidate 后：
        if cycle >= maxQualityRounds:
            LIMIT_REACHED，交付当前 candidate（刚写出、可能未再评分）
        else:
            cycle += 1，继续循环
```

`finishCandidate()`：

- 若因“动作无法执行”或 `PASS` 被升级后仍无法执行而结束：交付 `bestScored.candidate`，若还没有评分快照则交付当前稿。
- 若因“本轮已执行动作且 cycle >= max”结束：交付刚写出的当前稿。

禁止在应用动作之前仅因 `cycle >= maxQualityRounds` 直接结束。第 3 轮决策 `REWRITE` 时，只要还剩重写额度，必须先写再停。

本版决策合同不再产生 `REPLAN`/`REPITCH`/`REDIRECT`。执行器里若仍解析到这些字（旧测试或旧 Prompt），视为非法决策并失败，不再走对应回退。

### 7.5 修订/重写输入

`targeted-reviser.issueList` 为 6.4 的 JSON 数组字符串，不再传整篇决策。`protectedPasses` 仍是三审原文 map，供修订遵守“已通过保护”。

`story-writer` 在重写轮次的 `writerFeedback` 为：

```text
{
  "reason": "决策理由纯文本",
  "issues": [ ...同一 JSON 数组... ],
  "wordUsage": [ ...扫描结果... ]
}
```

首轮作家的 `writerFeedback` 仍是空字符串。

### 7.6 导演校验

`story-director` 完成后，对输出做：

- 存在 `MAIN_PITCH:` 且值为 `humor|adventure|wonder`
- 存在三行 `TAKE_FROM_HUMOR:` / `TAKE_FROM_ADVENTURE:` / `TAKE_FROM_WONDER:`
- 每行冒号后 trim 非空，长度不超过 80（合同写 40 词，校验按字符 80 封顶以免过严）

失败信息：`故事导演蓝图缺少主提案或落选吸收项`。

## 8. Catalog 与默认 Prompt

文件：`src/main/java/com/aitaskcenter/config/StoryAgentCatalog.java`。

初始化仍只补缺失 Agent。本版仍更新 Catalog 默认 Prompt 和节点 `variables`/`description`，供新环境和文档一致。已有库中的 Prompt 不被覆盖，靠第 6 节运行时合同生效。

必须改的默认 Prompt 要点：

| Agent | 增补要点 |
| --- | --- |
| `vocabulary-planner` | MUST/BACKGROUND、分场、一场最多 4 个 MUST |
| 三个 pitch | 不改核心方向；结尾加一句：导演可能只吸收你的一条要素，要点必须可单独移植 |
| `story-director` | 与 6.2 一致的选案/吸收/SetupRequired |
| `story-writer` | 只写纯英文故事；适合 `targetGrade`；禁止使役和从句；自然使用全部目标词 |
| `review-fun` | 变量仅故事/年级/轮次 |
| `review-language` | 使用 `wordUsage`，不要手搓全词表；重点写句型和年级 |
| `review-continuity` | 对照蓝图的 SetupRequired 是否兑现 |
| `story-scorer` | 四维 1–5 整数，不得在多个维度重复扣同一问题 |
| `quality-decider` | 只有三动作；年级或语言未过线必须 REWRITE；输出 ISSUES JSON |
| `targeted-reviser` | 按 JSON 逐条改；`type=GRADE` 或 `LANGUAGE` 允许整句替换；`protect=true` 的 quote 不得改 |

节点描述：

- 作家：去掉“目标词位置清单”。
- 程序节点：维持“画布拓扑 / 不调用模型”；硬规则与最高分由执行器内联完成，不在描述里承诺单独落步骤。

## 9. 前端

- `GET /api/story-agents/flow` 的 `variables` 随 Catalog 变化后，详情页自动显示新变量，无需新接口。
- 不删节点，不改四阶段。
- 运行记录仍按实际 `tb_story_run_step` 展示。词扫描结果出现在语言审核的 `inputJson` 里，记录页已能看见，不必新 UI。
- `StoryAgentFlowPage.test.tsx` 里的 mock 节点 `variables` 按第 7.2 节更新，避免断言旧字段。

## 10. 测试

先写失败测试再改实现。

### 10.1 扫描与审核输入

- `scanTargetWords`：`cat`/`cats` 算覆盖；`catalog` 不算覆盖 `cat`；缺词 `covered=false`。
- 创建运行时 mock 生成器按现有 `StoryRunExecutionServiceTest` 风格走完一轮 PASS：
  - `review-fun` 的 `inputJson` 不含 `wordUsage`/`storyBlueprint`
  - `review-language` 含 `wordUsage` 且含扫描到的目标词
  - `review-continuity` 含 `storyBlueprint`，不含 `wordUsage`

### 10.2 决策与升级

- 首行 `ACTION: PASS` 且四维都是 4、词全覆盖 → `COMPLETED`
- 首行 `this cannot PASS` 或 JSON `{"action":"REVISE"}` → 批次 `FAILED`，不再误判
- 评分 `grade=2`，决策 `ACTION: REVISE` → 执行器升级为 `REWRITE`，出现第二次 `story-writer`
- 词缺一个且分数过线、决策 `PASS` → 升级为 `REVISE`，调用 `targeted-reviser`
- 第 3 轮决策 `ACTION: REWRITE` 且仍有重写额度 → 必须出现重写作家步骤，不得在写之前 `LIMIT_REACHED`

### 10.3 导演与评分块

- 导演输出缺 `TAKE_FROM_WONDER` → 导演步骤 `FAILED`
- 评分缺 `SCORE_BEGIN` → 评分步骤 `FAILED`
- 两轮评分 `total` 先 16 后 12，第二轮后动作无法执行 → `finalStory` 为第一轮候选

### 10.4 故事协议

保留现有 `extractStory` 用例。作家/修订系统 Prompt 仍包含 `STORY_TEXT_BEGIN`，并包含使役/从句禁令。

### 10.5 回归

- `mvn -Dtest=StoryRunExecutionServiceTest,StoryAgentServiceTest,StoryAgentControllerTest,StoryRun*Test test`
- `web-react`：`StoryAgentFlowPage.test.tsx`、`StoryRunHistory.test.tsx`

## 11. 验收

用正在开发的后端，以三年级上册、3 个目标词跑一轮，满足：

1. 步骤里仍能看到 12 个可编辑 Agent 的调用（评分员仍在）。
2. 导演输出含 `MAIN_PITCH` 与三个 `TAKE_FROM_*`。
3. 语言审核输入里的 `wordUsage` 是数组，不是固定中文。
4. 若语言或年级分 < 3，下一步是作家重写而不是第二次无关微调。
5. 若第 3 轮要重写且额度还在，步骤表里有对应 `story-writer`。
6. `finalStory` 仍是纯英文场景，无中文清单。
7. 决策原始输出仍完整留在步骤里，便于对照 ISSUES JSON。

二十词批次作为人工对照：分场后不应再出现“数量词 + 身体部位”这种明显清单句占满一场。不作为自动断言。

## 12. 实现顺序

1. `scanTargetWords` 与拆分审核输入（测试先红）。
2. 评分块解析、最高分快照、`PASS` 门禁。
3. 决策首行三动作 + ISSUES JSON + 阻断升级。
4. 调整循环顺序，保证最后一轮 `REWRITE` 能落地。
5. 导演吸收行校验。
6. 扩展故事运行时合同与 Catalog 默认 Prompt / 变量。
7. 更新链路文档 `docs/chains/story-agent-flow-config.md` 与后端 `docs/backend/java_server/AGENTS.md` 中与决策、评分、回退不符的句子。

## 13. 主要改动文件

| 文件 | 职责 |
| --- | --- |
| `src/main/java/com/aitaskcenter/service/StoryRunExecutionService.java` | 合同、扫描、解析、循环、升级 |
| `src/main/java/com/aitaskcenter/config/StoryAgentCatalog.java` | 默认 Prompt、变量、描述 |
| `src/test/java/com/aitaskcenter/service/StoryRunExecutionServiceTest.java` | 行为测试 |
| `src/test/java/com/aitaskcenter/config/StoryAgentCatalogTest.java` | 若已有 Catalog 合同测试则同步 |
| `web-react/src/StoryAgentFlowPage.test.tsx` | mock 变量 |
| `docs/chains/story-agent-flow-config.md` | 与实现一致 |
| `docs/backend/java_server/AGENTS.md` | 与实现一致 |

不改表结构，不改 `StoryRunController` 路径。

## 14. 审核清单

实现前请确认：

- [ ] 三个 pitch、三个审核员、评分员都保留
- [ ] 评分低于语言/年级通过线时强制 `REWRITE`
- [ ] 最后一轮 `REWRITE` 必须先写再停
- [ ] 修订只吃 ISSUES JSON
- [ ] 导演必须吸收两份落选提案
- [ ] 不覆盖已有数据库 Prompt
- [ ] 不新增 Agent / 表 / 接口
- [ ] `REPLAN`/`REPITCH`/`REDIRECT` 本版不作为合法决策

审核通过后按第 12 节开发。
