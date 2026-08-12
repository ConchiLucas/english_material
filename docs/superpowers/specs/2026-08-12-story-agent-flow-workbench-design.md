---
title: 英文故事 Agent 流转工作台第一版设计
summary: 恢复 Agent 工作台菜单，以固定可点击流程图和 Prompt 中心详情面板管理多 Agent 故事流水线配置。
---

# 英文故事 Agent 流转工作台第一版设计

## 1. 目标

在刚删除的“Agent 工作台”一级菜单位置实现一版新的英文故事 Agent 流转工作台。页面首先解决两个问题：

1. 直观展示目标单词从策划、创意、导演、写作到审核、评分、决策、修订和人工审核的完整流转关系。
2. 用户点击任意 Agent 后，可以立即查看和编辑该 Agent 的 System Prompt，并保存独立版本。

第一版是可持久化的流程配置工作台，不执行真实故事生成，不恢复旧的通用 Agent CRUD、在线测试或运行记录功能。后续执行引擎直接消费本版保存的固定节点配置。

## 2. 已确认的故事业务约束

- 目标读者为小学 3–6 年级。
- 目标词数量不固定，不设硬上限；超过 30 个词时仅提示趣味性和自然度风险。
- 一个生成任务只产出一篇故事；根据目标词数量自动增加连续场景。
- 所有场景使用同一主角、同一主线，冲突逐场升级并在结尾形成回扣。
- 目标词允许自然的时态、单复数等词形变化；Agent 可根据剧情选择最自然的词义。
- 故事以小学词汇和句型为主，允许少量可以从语境理解的生词。
- 流程最终全自动运行；质量不达标时由独立决策人定向回退上游，但必须受确定性预算上限约束。
- 最终成品显示英文故事、场景标题、目标词高亮和单词使用位置清单，并由用户评分、批注和人工改稿。

## 3. 第一版范围

### 3.1 本版实现

- 在顶部一级导航恢复“Agent 工作台”。
- 固定展示四个流程区段，不允许拖拽、增删或重连节点。
- 点击 Agent 节点后，在页面右侧固定详情面板查看和编辑配置。
- 每个 Agent 持久化 System Prompt、AI Provider、Temperature、启用状态和 Prompt 版本。
- 页面展示 Agent 的固定上游、下游、职责说明和可用 Prompt 变量。
- 支持保存单个 Agent；保存成功后继续停留在当前节点。
- 支持查看单个 Agent 的 Prompt 版本列表，并将历史版本恢复成一个新的最新版本。
- 支持保存流程级质量预算：最大质量轮次、最大局部修订次数、最大正文重写次数、最大导演回退次数、最大创意重做次数、最大用词重做次数和最大总 Token 预算。
- 程序节点显示在流程中，但只读且明确标记“非 Agent / 0 Token”。
- 初始化缺失的固定流程节点；初始化只补缺，不覆盖用户已编辑的 Prompt。

### 3.2 本版不实现

- 不调用模型生成故事。
- 不运行审核、评分、决策或自动回退。
- 不实现任务队列、暂停、取消、断点恢复和运行记录。
- 不实现 Agent 新增、删除、拖拽排序或自定义连线。
- 不写入外部单词材料库。
- 不自动修改 Prompt。

## 4. 固定流程拓扑

### 4.1 策划与创意

```text
Word Pack（程序输入）
  -> 用词策划 Agent
  -> [幽默创意 Agent | 冒险创意 Agent | 奇想创意 Agent]（并行）
  -> 故事导演 Agent
```

### 4.2 写作与候选

```text
故事导演 Agent
  -> 故事作家 Agent
  -> 硬规则校验（程序节点）
  -> 候选版本快照（程序节点）
```

### 4.3 独立质量委员会

```text
候选版本快照
  -> [趣味审核员 | 语言用词审核员 | 剧情连续性审核员]（并行）
  -> 独立评分员
  -> 质量决策人
```

审核员只诊断，不改正文、不计算总分。评分员使用固定量表盲评，不修改故事或通过线。最终加权分由后续执行引擎中的确定性代码计算。决策人只从受限动作集合中选择 `PASS`、`REVISE`、`REWRITE`、`REDIRECT`、`REPITCH` 或 `REPLAN`。

### 4.4 修订与交付

```text
质量决策人
  -> PASS -> 人工审核
  -> REVISE -> 定向修订 Agent -> 硬规则校验 -> 质量委员会
  -> REWRITE -> 故事作家 Agent
  -> REDIRECT -> 故事导演 Agent
  -> REPITCH -> 三个创意 Agent
  -> REPLAN -> 用词策划 Agent
```

确定性预算控制器不是 Agent。后续运行时任一上限耗尽即停止，并交付历史最高分版本与未解决问题。

## 5. 固定节点目录

### 5.1 可编辑 Agent 节点

| Key | 名称 | 默认模型建议 | 职责边界 |
| --- | --- | --- | --- |
| `vocabulary-planner` | 用词策划 Agent | Flash Medium | 分组、自由选义、词形和剧情机会；不写故事 |
| `pitch-humor` | 幽默创意 Agent | Flash High | 提交幽默故事提案；不写正文 |
| `pitch-adventure` | 冒险创意 Agent | Flash High | 提交冒险故事提案；不写正文 |
| `pitch-wonder` | 奇想创意 Agent | Flash High | 提交奇想故事提案；不写正文 |
| `story-director` | 故事导演 Agent | Pro | 匿名选案并输出连续场景蓝图；不写正文 |
| `story-writer` | 故事作家 Agent | Pro | 根据蓝图写完整故事和用词映射 |
| `review-fun` | 趣味审核员 | Flash High | 诊断钩子、节奏、惊喜和结尾回报；不重写、不打总分 |
| `review-language` | 语言用词审核员 | Flash Medium | 诊断目标词自然度、语法与年龄适配；不重写、不打总分 |
| `review-continuity` | 剧情连续性审核员 | Flash High | 诊断主线、因果、升级和场景衔接；不重写、不打总分 |
| `story-scorer` | 独立评分员 | Pro | 使用固定量表盲评并提供证据；不改正文、不改通过线 |
| `quality-decider` | 质量决策人 | Pro | 根据证据和预算选择受限路由动作；不创作、不改分 |
| `targeted-reviser` | 定向修订 Agent | Pro | 只修问题清单指出的位置，保留已通过内容 |

### 5.2 只读程序节点

- Word Pack 输入。
- 硬规则校验。
- 候选版本快照 / 历史最高分版本仓。
- 确定性预算控制器。
- 人工审核节点。

## 6. 页面布局

### 6.1 顶部

- 标题“英文故事生成流程”。
- 辅助说明“小学 3–6 年级 · 自动质量闭环”。
- 统计 Agent 数量。
- “质量预算”按钮打开轻量配置弹窗。
- “保存流程”只保存预算配置；Agent Prompt 使用详情面板中的独立保存按钮。

“运行记录”在第一版不显示，避免暗示尚未实现的执行能力。

### 6.2 左侧流程画布

- 使用四个纵向区段：策划与创意、写作与候选、独立质量委员会、修订与交付。
- 每个 Agent 是可点击卡片，显示角色名、角色类型、启用状态、Prompt 版本和模型简称。
- 并行节点使用同一个虚线分组框。
- 决策人的允许回退路径在质量区下方以标签直接展示。
- 当前选中节点以紫色边框和背景强调。
- 第一版画布只允许滚动和点击，不提供拖拽或缩放。

### 6.3 右侧固定详情面板

右侧详情不使用覆盖式 Drawer，避免打开节点时遮挡流程。桌面端宽度约 420–460px；窄屏降为流程下方的详情区。

字段按重要性排列：

1. Agent 名称、职责说明和启用开关。
2. System Prompt 大文本框，必填，始终处于最显眼位置。
3. 可用 Prompt 变量，只读标签。
4. AI Provider 下拉框，来源为现有 `/api/ai/providers`。
5. Temperature，范围 `0–2`。
6. 固定上游和下游，只读。
7. 当前 Prompt 版本与历史版本入口。
8. 保存 Prompt 按钮。

Prompt 修改后切换节点或离开工作台时必须提示未保存更改。程序节点被点击时，详情区显示其职责、规则和“该节点不使用 Prompt”，不显示可编辑字段。

## 7. 数据模型

### 7.1 `tb_story_agent_config`

每个固定 Agent 一条当前配置：

- `agent_key`：唯一固定 Key。
- `name`：固定显示名。
- `role_type`：`PLANNER`、`PITCH`、`DIRECTOR`、`WRITER`、`REVIEWER`、`SCORER`、`DECIDER` 或 `REVISER`。
- `description`：职责说明。
- `system_prompt`：当前 System Prompt。
- `ai_provider_id`：引用现有 AI Provider ID，不复制密钥。
- `temperature`。
- `enabled`。
- `prompt_version`：从 1 单调递增。
- 通用创建、更新时间字段。

拓扑、坐标、上下游和 Prompt 变量由后端固定目录定义，不持久化为任意图数据，防止第一版复杂化和出现无效流程。

### 7.2 `tb_story_agent_prompt_version`

每次 Prompt 或执行参数发生变化时保存不可变快照：

- `agent_key`。
- `version`。
- `system_prompt`。
- `ai_provider_id`。
- `temperature`。
- `enabled`。
- `created_at`。

恢复旧版本不覆盖历史；它将旧快照内容保存为一个新的最新版本。

### 7.3 `tb_story_flow_config`

工作空间只有一条默认流程配置：

- `config_key = default-story-flow`。
- `max_quality_rounds`，默认 3。
- `max_local_revisions`，默认 2。
- `max_writer_rewrites`，默认 1。
- `max_director_returns`，默认 1。
- `max_pitch_returns`，默认 1。
- `max_plan_returns`，默认 1。
- `max_total_tokens`，默认 120000。

默认值仅用于建立可运行边界，用户可在第一版界面修改。所有数值必须为非负，质量轮次至少为 1；全局预算优先于局部上限。

## 8. 后端 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/story-agents/flow` | 返回固定拓扑、程序节点、当前 Agent 配置和质量预算 |
| `PUT` | `/api/story-agents/{agentKey}` | 保存单个 Agent 配置；有实质变化时创建下一 Prompt 版本 |
| `GET` | `/api/story-agents/{agentKey}/versions` | 返回该 Agent 的 Prompt 版本摘要和内容 |
| `POST` | `/api/story-agents/{agentKey}/versions/{version}/restore` | 将指定历史快照恢复为新的最新版本 |
| `PUT` | `/api/story-agents/flow/config` | 保存流程质量预算 |

后端只接受固定 Agent Key。名称、角色类型、拓扑和变量以服务端目录为准；客户端不能创建任意 Agent 或连线。AI Provider 必须存在、已启用并声明 `TEXT_GENERATION` 能力。

## 9. 初始化与兼容

- 使用启动初始化器按固定目录补齐缺失 Agent 和默认流程配置。
- 已存在的 Agent 配置不被初始化器覆盖。
- 不复用刚删除的 `tb_agent_definition` 与 `tb_agent_test_run`，新表使用 `story_agent` 命名，避免与旧通用工作台混淆。
- 外部单词数据库保持只读；本版新增表只位于本地 `english_material` 配置库。
- AI 密钥仍只保存在现有 `tb_ai_config`，Agent 表只保存 Provider ID。

## 10. 错误处理

- 初次加载失败：在工作台内展示重试，不影响配置管理和去重单词页面。
- 保存失败：保留当前编辑内容和选中节点，显示错误消息。
- Provider 已删除或停用：详情显示无效状态，禁止保存，要求重新选择。
- Prompt 为空：前端与后端同时拒绝保存。
- 并发编辑：更新请求携带当前 `UpdatedAt`；服务端检测版本不一致时返回冲突，前端提示重新加载，避免静默覆盖。
- 版本恢复失败：不改变当前配置。

## 11. 测试与验收

### 11.1 后端

- 固定目录包含 12 个唯一 Agent Key 和正确拓扑。
- 初始化只补缺，不覆盖已编辑 Prompt。
- 保存实质变化会增加 Prompt 版本；相同内容重复保存不增加版本。
- 只允许固定 Key、有效文本生成 Provider、合法 Temperature 和非空 Prompt。
- 历史恢复创建新版本且保留旧快照。
- 预算配置校验边界值。

### 11.2 前端

- 顶部导航重新出现“Agent 工作台”。
- 页面展示四个区段、12 个 Agent 和只读程序节点。
- 点击不同 Agent 会更新右侧名称、Prompt、模型、温度、上下游和版本。
- 点击程序节点显示只读说明。
- 保存 Prompt 后保持当前节点选中且版本号更新。
- 未保存时切换节点或离开页面会确认。
- 质量预算可加载、编辑并保存。
- 桌面和窄屏都能访问流程及详情。

### 11.3 第一版完成标准

- 用户从刚删除的菜单位置进入新的 Agent 工作台。
- 用户无需打开多层弹窗即可看清 Agent 流转。
- 用户点击任意 Agent 后立即看到其 Prompt，并能保存、查看版本和恢复历史版本。
- 固定拓扑、Prompt、Provider、温度、启用状态和预算均从后端读取并持久化。
- 页面不展示尚未实现的运行或生成能力。
- 配置管理、AI/CLI 配置和去重单词功能无回归。
