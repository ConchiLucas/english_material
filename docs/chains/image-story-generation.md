---
title: 图片故事多 Agent 生成链路
summary: 说明已有英文故事如何经过九个文本 Agent、参考图/分镜图生成和确定性文字合成，形成可人工查看的连续绘本。
---

# 图片故事多 Agent 生成链路

## 入口与产品边界

React 一级导航中的“图片工作台”紧邻“Agent 工作台”。它只接受已有故事批次中非空的 `finalStory`，创建时选择一个已启用画风预设；不能直接输入另一份故事，也不会在图片执行时回读外部材料库。

第一版的交付边界是自动规划、一次性出图和人工查看：没有视觉评审 Agent、候选图比较、自动或手工重绘、图片重试、审核状态写入、暂停、删除或跨重启续跑。页面中的图片记录只用于审计和人工判断，不触发新的模型调用。

## 固定流程

`ImageAgentCatalog` 固定四个阶段，前端不能增删或拖拽节点：

| 阶段 | 节点 | 执行关系 |
| --- | --- | --- |
| 故事理解与视觉约束 | `image-story-analyst`、`image-continuity-designer`、`image-art-director` | 先跑故事分析，再并行生成连续性与画风；后两者读取分析快照 |
| 双分镜提案与决策 | `image-action-storyboarder`、`image-learning-storyboarder`、`image-storyboard-director` | 两个提案 Agent 并行，总监随后生成唯一分镜表 |
| 出图提示词准备 | `image-reference-planner`、`image-shot-prompt-engineer`、`image-prompt-preflight` | 三个文本 Agent 顺序规划参考资产、分镜提示词并执行一次预检 |
| 图片生成与文字合成 | `reference-image-generator`、`shot-image-generator`、`text-compositor` | 三个程序节点顺序生成参考图、分镜底图和最终文字图层 |

九个文本 Agent 都有独立结构化合同。`ImageAgentCatalog` 将不可编辑的 `IMAGE_AGENT_RUNTIME_CONTRACT_V2` 与数据库中当前 Prompt 组合成 effective system prompt。V2 合同具有最终最高优先级，但只覆盖前文与其冲突的 JSON marker、schema、字段、beat 覆盖和精确 reference 要求；人物、画风、叙事等不冲突的业务创作要求仍然保留。真实已持久化的旧版默认 Prompt 和自定义 Prompt 无需被初始化覆盖或迁移，若已包含完全相同的合同则不会重复附加。批次 Agent 快照保存这个实际执行 Prompt；配置页和 Prompt 版本仍直接维护数据库中的 `system_prompt`，不能关闭代码目录持有的当前合同。

后端保存完整输入、模型原始输出和解析结果，拒绝重复/未知 key、缺失 Scene、图片数量越界、参考资产错绑、同镜互斥动作、图片内文字指令、无说话人的对白和无法安全排版的文本。预检只输出一份最终计划，不循环回退。

连续性与覆盖约束在规划阶段逐层收紧：

- `StoryAnalysis` 的每个 Scene 必须有 1—5 个从 1 连续编号的 beat，全篇 beat 总数不超过 20；角色和地点都不能为空，两者合计不超过 20。每个 beat 明确非空 `action`、实际出场 `characters` 集合和唯一 `location`，角色/地点 key 必须来自本次故事分析目录。
- 两份 `StoryboardProposal` 的每个镜头都必须提供安全且稳定的 `shotKey`，各自覆盖全部 beat。Scene、beat、action、characters 集合和 location 以 StoryAnalysis 对应 beat 为准；解析器会把漂移的锁定字段回写为来源 beat，而不是直接失败。
- `FinalStoryboard` 的 `shotKey` 必须来自任一提案，并继续按同一规则回写锁定字段；镜头按 Scene/Shot 严格升序且每个 Scene 的 `shotIndex` 从 1 连续递增，每 Scene 最多 5 镜、全篇最多 20 镜并覆盖全部 beat。
- `ReferencePlan` 只接受 `CHARACTER` 和 `LOCATION`，必须为故事分析中的每个角色和每个地点各生成且仅生成一个参考资产，总数最多 20。
- `ShotPromptPlan` 和 `PreflightPlan` 的每个镜头，其 `referenceAssetKeys` 必须精确等于该镜头所属地点与全部出场角色的参考 key 集合：不能缺少、不能重复，也不能加入未出场角色或其他地点；单镜仍最多 8 个。预检还必须与最终分镜的 key、Scene 和 Shot 完全一致。

解析器在每个 Agent 原始输出落入步骤记录时立即执行对应校验。任何一项失败都会把当前 Agent 步骤和图片批次标记为 `FAILED`；执行不会进入参考图生成，因此不会创建 `PROGRAM` 步骤或产生图片调用费用。

固定图片约束为 `1536×864`（16:9）、每个 Scene 1—5 张、全篇最多 20 张。Scene 会按含义、动作和时间点拆成多张分镜，不是固定一 Scene 一图。

## 创建边界与快照

`GET /api/image-runs/source-stories` 只列出 `COMPLETED` 或 `LIMIT_REACHED`、且最终故事非空的最近故事记录；前端从该列表选择来源。`POST /api/image-runs` 只接收 `storyRunId` 和 `stylePresetId`，`ImageRunExecutionService` 在写入 `QUEUED` 批次前完成以下校验：

- 故事存在，且 `finalStory`、单词和目标年级快照完整，并满足共用的故事长度边界；
- 九个文本 Agent 全部存在、启用，Prompt/版本/温度有效，且引用启用的 `TEXT_GENERATION` Provider；
- 画风预设存在且启用；
- 图片流程保持固定尺寸和数量上限，图片 Provider 启用、配置完整、采用 `openai-compatible` 类型，并同时具有 `IMAGE_GENERATION`、`IMAGE_REFERENCE` 能力；
- 图片文件根目录存在且可安全写入。

画风配置 API 在创建和更新时都要求名称、正向提示词、负向提示词和说明四个文本字段非空；前端也在提交前执行同样的必填校验。

批次持久化最终故事、单词、年级、画风、流程、图片 Provider 安全描述，以及九个 Agent 的 effective system prompt、版本、温度和文本 Provider 安全描述。Provider 快照不保存 API Key 或 Base URL；图片业务表、步骤、资产、错误和日志也不复制密钥。执行所需的 Provider 地址和密钥只由现有 `tb_ai_config` 配置装入当前执行内存。创建后修改原故事、Prompt 或画风不会改变已保存的历史快照。出图阶段会重新读取当前流程绑定的图片 Provider 用于 HTTP 调用和 program-step 字段，但不会改写批次创建时的 `flowSnapshotJson`。

## Provider 与图片文件

`AiImageGenerationService` 是 OpenAI Images-compatible 适配器：

- 没有参考图时请求 Provider Base URL 下的 `/v1/images/generations`；
- 分镜携带已生成参考图时请求 `/v1/images/edits` multipart 接口，图片字段依次命名为 `image`、`image1`、`image2`，兼容 Antigravity 多参考图协议；
- 响应必须包含 `b64_json`，返回图片会被解码并校验类型、像素和大小；尺寸已精确匹配时保留原字节，否则中心裁剪并以高质量插值归一为请求的 `1536×864` PNG；
- 角色参考图先于地点参考图生成；每个分镜只调用一次图片接口，并最多携带 8 张已声明参考图。单张图片调用对瞬时失败（HTTP 429/502/503/504 与连接中断）最多自动重试 3 次，仍串行生成；没有用户侧重试或重绘。

后端 `ImageProviderPolicy` 同时供流程配置保存和运行创建使用，避免“可保存但不可执行”的资格差异。Provider 必须具有非空 ID、模型和 Base URL；Base URL 是带 host 的绝对 HTTP(S) URI，不含用户信息、query 或 fragment，也不能直接指向 generations/edits 端点。可选 `options` 只允许不超过 64 字符的字符串 `responseFormat`、`quality`、`size`；分别限制为 `b64_json`、`auto|low|medium|high|standard|hd` 和 `1536x864`。前端用同一规则决定下拉项和失效状态。

配置管理的“图片模型配置”仍保存到共享 `tb_ai_config`，可维护多个图片 Provider。`POST /api/ai/config/image/bootstrap` 只提交已有 Antigravity 来源 Provider ID，后端在持久化边界内复制地址和密钥，固定创建 `antigravity-gemini-image` / `gemini-3.1-flash-image` 以及双图片能力和固定 options；返回配置不含密钥。该引导和普通配置保存都不调用图片 Provider，也不消耗图片额度；若当前图片流程没有可执行 Provider，前端再以 `updatedAt` 将新配置选为流程图片模型。出图阶段会重新读取当前流程绑定的图片 Provider，因此以当前 AI 配置中的模型为准，而不是仅使用批次启动时的旧快照。单张图片调用对瞬时失败（HTTP 429/502/503/504 与连接中断）最多自动重试 3 次，仍串行生成。

图片模型只接收无字画面提示词。`ImageTextCompositor` 读取分镜底图，在 Java2D 中将带说话人和锚点的对白排成气泡，将叙事排成底部安全区字幕，并输出最终 PNG。

`ImageAssetStore` 将文件保存为私有 MinIO Bucket 中的 `<basePath>/<runId>/<assetKey>.<ext>` 对象，并把创建时的 Bucket 与完整对象键写入资产记录。写入使用 `If-None-Match: *` 原子创建，已有审计对象不能覆盖；读取限制为 25 MiB 并重新校验 SHA-256，数据库写入失败时只有固定对象位置与哈希都匹配才补偿删除。后续修改默认 Bucket/基础路径不影响历史资产；Bucket 缺失时由保存/测试配置或批次前置探测创建，代码不设置公开 Policy。

`tb_image_asset` 只记录受控的两段式相对对象键、MIME、宽高、SHA-256、Provider/模型/请求 ID、提示词和经过投影的元数据。`GET /api/image-assets/{assetId}/content` 通过资产 ID 查元数据，从 MinIO 有界读取并重新校验 SHA-256，只返回 PNG/JPEG，并使用 ETag 与长期不可变缓存；客户端不能提交对象键，也拿不到 MinIO 地址或凭据。

## 状态、审计与失败

状态机为：

```text
QUEUED
  -> PLANNING
  -> GENERATING_REFERENCES
  -> GENERATING_SHOTS
  -> COMPOSITING
  -> COMPLETED
```

任一步骤失败后批次进入 `FAILED`，后续调用停止，已经持久化的步骤和图片仍可查询。应用启动时 `ImageRunRecoveryInitializer` 为每个遗留活动批次开启独立 `REQUIRES_NEW` 事务：先把该批次仍为 `RUNNING` 的步骤以同一中断错误和时间标记为 `FAILED`，再在同一事务中将批次标记为 `FAILED`；已完成步骤不改写，运行无法跨重启继续。

`tb_image_run_step` 最多形成 12 个按固定序号排列的记录：九个 `AGENT` 步骤保存完整输入、原始输出、解析输出、Provider/模型、Prompt 版本、Token、耗时和错误；三个 `PROGRAM` 步骤保存实际输入及生成资产 key/数量或完成分镜 key/数量。图片记录全屏页按批次联动单行输入单词、实际步骤、完整输入/输出，并在底部切换参考设定图和最终分镜图；活动批次轮询，终态停止。

“英语素材项目 / 图片生成结果”是面向最终交付物的独立只读入口，不复用运行审计详情。后端只分页返回 `COMPLETED` 且至少有一项 `FINAL` 资产的批次，当前页批量读取分镜和最终资产后按 Scene/Shot 稳定排序；前端默认每页 10 条，可切换 20 或 100 条。每个批次以全宽容器展示单行摘要和响应式 16:9 最终图片卡片，只通过资产 ID API 加载并预览图片，不暴露 MinIO 地址，也不展示参考图、底图、中间步骤、Token、评分或失败批次。

## 数据表

| 表 | 当前用途 |
| --- | --- |
| `tb_minio_config` | 唯一 MinIO Endpoint、Access/Secret Key、SSL、私有 Bucket、基础路径、启用状态与乐观锁 |
| `tb_image_agent_config` | 九个 Agent 的当前 Prompt、文本 Provider、温度、启用状态、Prompt 版本与乐观锁 |
| `tb_image_agent_prompt_version` | 每次保存/恢复产生的追加式 Prompt 快照 |
| `tb_image_flow_config` | 唯一图片 Provider 和固定尺寸/数量上限 |
| `tb_image_style_preset` | 内置或自定义画风的正向/负向提示词、说明、启用状态与乐观锁 |
| `tb_image_run` | 来源故事 ID 及故事、单词、年级、画风、流程、Agent 快照，状态、数量和时间 |
| `tb_image_run_step` | 九个 Agent 和三个程序步骤的完整运行审计 |
| `tb_image_shot` | Scene/Shot、来源片段、视觉目标、对白/字幕、最终提示词、引用 key 和状态 |
| `tb_image_asset` | 参考图、底图、最终图的受控文件元数据与 SHA-256 |

## HTTP 入口

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/image-agents/flow` | 查询固定流程、节点、当前图片配置和画风 |
| `POST /api/ai/config/image/bootstrap` | 复用服务端已有 Antigravity 凭据创建固定图片 Provider；请求和响应均不传来源密钥 |
| `PUT /api/image-agents/{agentKey}` | 携带 `updatedAt` 保存 Agent 配置并追加版本 |
| `GET /api/image-agents/{agentKey}/versions` | 倒序查询 Prompt 版本 |
| `POST /api/image-agents/{agentKey}/versions/{version}/restore` | 恢复历史版本为新的最新版本 |
| `PUT /api/image-agents/flow/config` | 携带 `updatedAt` 保存已启用的 OpenAI-compatible 双能力图片 Provider |
| `GET/POST /api/image-style-presets` | 查询或创建画风预设 |
| `PUT /api/image-style-presets/{presetId}` | 携带 `updatedAt` 更新或停用画风 |
| `GET /api/image-runs/source-stories` | 查询可用的已有最终故事 |
| `POST /api/image-runs` | 保存快照并创建异步图片批次 |
| `GET /api/image-runs` | 查询最近图片批次 |
| `GET /api/image-runs/results` | 分页查询已完成且含最终图片的批次；分页大小只允许 10、20、100 |
| `GET /api/image-runs/{runId}` | 查询快照、步骤、分镜和资产元数据 |
| `GET /api/image-assets/{assetId}/content` | 按资产 ID 返回校验后的图片内容 |
| `GET /api/minio-config` | 查询脱敏 MinIO 配置 |
| `PUT /api/minio-config` | 验证并保存 MinIO 配置，空 Secret 保留已有值 |
| `POST /api/minio-config/test` | 不持久化地执行 Bucket 写读删探测 |

## 本地与 Context Router 运行

本地开发与 Context Router Fast/Full 都不创建图片目录或挂载图片卷。MinIO 连接信息保存在本地 PostgreSQL 的 `tb_minio_config`，部署替换容器不会改变对象资产；部署前应在“MinIO 配置”页完成脱敏保存与连接测试。

工作空间启动和代码更新仍遵循根 `AGENTS.md`：先准备 Context Router `task_id`，启动使用 `start_workspace`，变更使用一次 `apply_workspace_changes` 并轮询 Workspace operation 到终态。`.env.local`、Provider API Key 和其他本机凭据不得提交、写入文档、发送给 Context Router 或出现在日志中。
