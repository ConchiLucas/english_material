---
title: 英语材料 Spring Boot 后端
summary: 维护数据库、AI、故事与图片 Agent 配置及有界执行，并只读查询外部英语材料数据。
---

# 英语材料 Spring Boot 后端

## 项目身份

- 源码根：工作空间根目录 `.`。
- 技术栈：Java 17、Spring Boot 3.3、Spring Web、Spring Data JPA。
- 启动类：`com.aitaskcenter.AiTaskCenterApplication`。
- 默认端口：`18744`。
- 构建入口：根 `pom.xml`。

## 当前职责

- `ConnectionConfigController` 维护可访问的 PostgreSQL、MySQL、SQL Server、Oracle 或 SQLite 连接配置，并提供连接测试和表清单。
- `AiConfigController` 维护 AI Provider、当前 Provider和本地 CLI 配置；`POST /api/ai/config/image/bootstrap` 只接收来源 Provider ID，在后端从同一 `tb_ai_config` 安全复制 Antigravity 地址与密钥并创建固定图片 Provider，响应继续脱敏且不会调用外部模型。
- `MinioConfigController` 维护唯一 MinIO 配置；保存或测试时执行私有 Bucket 建桶与有界写读删探测，Secret Key 只入库且查询永不回传。
- `StoryAgentController` 提供故事 Agent 流程、Prompt 版本和质量预算的配置接口；`StoryAgentService` 负责拼装固定流程、校验可编辑节点与文本生成 Provider、保存 Agent 配置、生成 Prompt 版本快照、恢复历史版本和维护流程预算。
- `StoryAgentCatalog` 固定定义 4 个阶段、12 个可编辑 Agent 和 5 个只读程序/人工节点；`StoryAgentInitializer` 启动时只在某个 Agent 配置缺失时创建该配置及其 v1 快照，已有 Agent 即使缺少历史快照也不补建；默认流程预算缺失时才创建，且不覆盖已有配置。
- `StoryRunController`、`StoryRunExecutionService` 与 `StoryRunQueryService` 创建异步故事运行批次，按固定 Agent 链路执行创作、审核、评分和决策，保存每次实际模型调用的完整输入/输出，并提供批次与详情查询。
- `StoryRunExecutionService` 为 `story-writer` 和 `targeted-reviser` 追加不可编辑的运行时输出协议：步骤表保留模型原始响应，但只有唯一边界内通过纯文本校验的英文场景故事会进入后续审核及 `tb_story_run.final_story`。模型在结束边界外追加的清单或修订说明仅留在原始步骤详情；边界缺失/歧义或故事块内部出现 Markdown、中文说明或清单会使该步骤和批次失败，不会把完整报告冒充最终故事。
- 质量决策支持 `REVISE`、`REWRITE`、`REDIRECT`、`REPITCH`、`REPLAN` 和 `PASS`；每种回退次数、质量轮次和总 Token 都受 `tb_story_flow_config` 的确定性预算限制。Provider 未返回用量时，以输入输出长度估算用量，预算仍然生效。
- `WordCleanController` 根据已保存的连接 ID 查询去重单词、筛选项和例句。
- `StoryWordSourceService` 可从已保存连接中的 `word_library`/`word` 参数化随机读取 1—50 个单词；外部材料库始终只读。
- `ImageAgentController` 与 `ImageAgentService` 维护固定 4 阶段的 9 个文本 Agent、追加式 Prompt 版本、固定图片流程配置和画风预设；`ImageAgentCatalog` 另定义参考图生成、分镜图生成和文字合成 3 个只读程序节点。
- `ImageRunController`、`ImageRunExecutionService` 与 `ImageRunQueryService` 从已有故事的最终故事创建异步图片批次，保存故事、单词、年级、画风、流程、Agent 和 Provider 的安全快照，并提供 9 个 Agent 与 3 个程序步骤的完整审计。
- 图片规划先并行执行故事分析/连续性/美术导演，再并行执行双分镜提案，随后顺序完成分镜决策、参考资产规划、分镜提示词和预检。结构化输出逐步校验；任何失败都会停止后续图片调用。
- 故事分析的每个 beat 都明确 `action`、实际出场 `characters` 和唯一 `location`。两个分镜提案必须以稳定 `shotKey` 覆盖全部 beat，并与来源 beat 的 Scene、action、characters 集合和 location 严格一致；最终分镜只能继承提案 `shotKey`，继续完整保留这五项语义。故事分析另限定每 Scene 1—5 个连续 beat、全篇最多 20 个，并要求角色和地点各至少一个、合计不超过 20。
- 参考资产规划必须为每个角色和地点各生成且仅生成一份参考；分镜提示词与最终预检的 `referenceAssetKeys` 必须精确等于该镜头所属地点和全部出场角色的参考 key 集合，不允许缺少、多余或重复引用。所有这些结构约束都在 `PLANNING` 内完成校验，失败的 Agent 步骤和批次会在任何图片调用或 `PROGRAM` 步骤创建前终止。
- `ImageAgentCatalog` 为 9 个文本 Agent 提供不可编辑的 `IMAGE_AGENT_RUNTIME_CONTRACT_V2`。它在 effective system prompt 中具有最终最高优先级，但覆盖范围只限于与其冲突的 JSON marker、schema、字段、beat 覆盖和精确 reference 要求；前文不冲突的业务创作要求继续生效。运行时将该合同附加到数据库中现有或自定义 Prompt（已包含相同合同则不重复），包括真实已持久化的旧版默认 Prompt 在内都无需由初始化覆盖或迁移；批次 Agent 快照保存实际送给模型的 effective system prompt，供历史审计。
- `ImageProviderPolicy` 是图片流程配置、固定 Antigravity 引导与运行创建共用的后端资格策略；只有 ID、模型、Base URL 完整，已启用、类型为 `openai-compatible` 且同时声明 `IMAGE_GENERATION`、`IMAGE_REFERENCE` 的 Provider 才可用。`AiImageGenerationService` 无参考图调用 `/v1/images/generations`，携带参考图调用 `/v1/images/edits`，multipart 图片字段按 `image`、`image1`、`image2` 递增，只接受 `b64_json` 图片结果。
- 图片执行固定输出 `1536×864`，每个 Scene 1—5 张、全篇最多 20 张；先生成角色和地点参考图，再按 Scene/Shot 顺序为每个分镜调用一次图片模型，最后由 `ImageTextCompositor` 以 Java2D 合成角色对话气泡和底部叙事字幕。
- 故事与图片运行都使用进程内有界线程池，不包含 Python Worker、分布式队列、暂停、删除、跨重启续跑或图片重试。第一版也没有视觉评审、自动/手工重绘或审核写入能力。

## HTTP 入口

| 路径 | 说明 |
| --- | --- |
| `/api/connection/getTbConnectionList` | 查询数据库连接配置 |
| `/api/connection/createTbConnection` | 新增数据库连接配置 |
| `/api/connection/updateTbConnection` | 更新数据库连接配置 |
| `/api/connection/deleteTbConnection` | 删除数据库连接配置 |
| `/api/connection/testConnectionPayload` | 测试尚未保存的连接配置 |
| `/api/connection/listTables` | 列出已配置连接中的表 |
| `/api/ai/config` | 读取或保存 AI Provider 配置 |
| `POST /api/ai/config/image/bootstrap` | 按来源 Provider ID 在服务端复用 Antigravity 凭据并创建固定 `gemini-3-pro-image` 图片 Provider；不回传密钥或调用模型 |
| `/api/ai/cli/config` | 读取或保存本地 CLI 配置 |
| `GET /api/minio-config` | 查询脱敏 MinIO 配置 |
| `PUT /api/minio-config` | 验证并保存 MinIO 配置；Secret Key 留空时保留已有值 |
| `POST /api/minio-config/test` | 使用表单配置执行私有 Bucket 写读删验证，不持久化 |
| `GET /api/story-agents/flow` | 读取固定四阶段流程、节点配置和质量预算 |
| `PUT /api/story-agents/{agentKey}` | 保存指定可编辑 Agent 的 Prompt、Provider ID、温度和启用状态 |
| `GET /api/story-agents/{agentKey}/versions` | 按版本倒序读取 Prompt 历史 |
| `POST /api/story-agents/{agentKey}/versions/{version}/restore` | 携带当前 `updatedAt`，将历史快照恢复为新的最新版本 |
| `PUT /api/story-agents/flow/config` | 保存质量轮次、回退次数和总 Token 预算 |
| `POST /api/story-runs` | 使用显式单词快照创建异步故事运行批次 |
| `GET /api/story-runs` | 按创建时间倒序查询运行批次 |
| `GET /api/story-runs/{runId}` | 查询批次、全部实际 Agent 调用及最终故事 |
| `GET /api/story-runs/word-libraries` | 读取指定外部连接的可用词库 |
| `POST /api/story-runs/random-words` | 从指定外部词库随机预览 1—50 个单词 |
| `GET /api/image-agents/flow` | 读取固定图片流程、9 个 Agent、3 个程序节点、流程配置和画风预设 |
| `PUT /api/image-agents/{agentKey}` | 保存文本 Agent 的 Prompt、文本 Provider、温度和启用状态，并追加版本 |
| `GET /api/image-agents/{agentKey}/versions` | 按版本倒序读取图片 Agent Prompt 历史 |
| `POST /api/image-agents/{agentKey}/versions/{version}/restore` | 携带当前 `updatedAt`，恢复历史 Prompt 为新的最新版本 |
| `PUT /api/image-agents/flow/config` | 保存已启用、采用 OpenAI-compatible 协议并支持生成/多参考图的固定图片 Provider；尺寸和数量上限不可修改 |
| `GET /api/image-style-presets` | 查询全部画风预设 |
| `POST /api/image-style-presets` | 新增画风预设 |
| `PUT /api/image-style-presets/{presetId}` | 携带 `updatedAt` 更新或停用画风预设 |
| `GET /api/image-runs/source-stories` | 查询最终故事非空且可作为图片来源的已有故事批次 |
| `POST /api/image-runs` | 接收 `storyRunId + stylePresetId`，保存快照并创建异步图片批次 |
| `GET /api/image-runs` | 按创建时间倒序查询图片批次 |
| `GET /api/image-runs/{runId}` | 查询故事/配置快照、全部步骤、分镜和资产元数据 |
| `GET /api/image-assets/{assetId}/content` | 按资产 ID 校验持久化 Bucket/对象键与 SHA-256 后返回 PNG/JPEG 内容和长期缓存头 |
| `/api/word-clean` | 分页查询去重单词 |
| `/api/word-clean/facets` | 查询难度和来源筛选项 |
| `/api/word-clean/{id}/sentences` | 查询指定单词的候选例句 |

## 数据边界

- 本地配置库由 `TASK_CENTER_DB_URL`、`TASK_CENTER_DB_USER` 和 `TASK_CENTER_DB_PASSWORD` 注入，默认库名为 `english_material`。
- JPA 在可写的本地配置库中维护配置表，以及 `tb_story_run`、`tb_story_run_step` 两张运行记录表。
- 图片链路在本地配置库维护 `tb_minio_config`、`tb_image_agent_config`、`tb_image_agent_prompt_version`、`tb_image_flow_config`、`tb_image_style_preset`、`tb_image_run`、`tb_image_run_step`、`tb_image_shot` 和 `tb_image_asset`。图片字节不写入 PostgreSQL。
- 故事 Agent 表只保存 AI Provider ID 字符串，不复制 Provider 详情或密钥，与 `tb_ai_config` 之间没有数据库外键。初始化时如果没有有效的文本生成 Provider，缺失 Agent 的 Provider ID 可以保存为空字符串。
- 更新 Agent 或恢复 Prompt 版本时，请求都携带当前 Agent 的 `updatedAt`；`StoryAgentService` 拒绝过期时间戳，并当下校验 Provider 是否存在、已启用且包含 `TEXT_GENERATION` 能力。之后删除或停用 AI 配置可能使已保存 ID 失效；前端会将其标为不可用并要求重新选择后才能保存。
- 外部材料查询使用 `ConnectionConfigService.openConfiguredConnection` 打开用户选中的连接。
- `WordCleanService` 只使用参数化 `SELECT` 查询 `word_clean`、`word_clean_sentence`、`word_clean_best_sentence` 和 `word_clean_tts`。
- `StoryWordSourceService` 只参数化读取 `word_library` 与 `word`；运行创建后使用本地 JSON 单词快照，不再依赖外部库内容是否变化。
- 运行步骤只保存 Provider ID、模型名、Prompt 版本、完整输入/输出与用量，不复制 API Key、数据库密码或完整连接信息。
- 图片 Agent 只引用现有 `tb_ai_config` Provider ID；图片业务表、运行快照、步骤、资产元数据、错误信息和日志都不复制 API Key。执行所需密钥只从既有 Provider 配置装入当前进程内存，并对可见错误做脱敏。
- 图片 Provider Base URL 必须是带 host 的绝对 HTTP(S) URI，不得包含用户信息、query、fragment，也不得直接以 `/images/generations` 或 `/images/edits` 结尾。可选 `options` 只能使用长度不超过 64 的字符串 `responseFormat`、`quality`、`size`：格式固定 `b64_json`，尺寸固定 `1536x864`，quality 只接受受控枚举。前端使用同一组资格规则过滤下拉项。
- 图片画风的名称、正向提示词、负向提示词和说明四个文本字段在前后端都必填；创建或更新时任一字段为空都会被拒绝，启用状态仍独立保存。
- 图片批次创建边界会复制已校验的 `finalStory`、输入单词、目标年级、画风、固定流程和 9 个 Agent/Provider 的安全快照；之后修改原故事、Prompt、画风或 Provider 不改变历史批次含义。Provider 快照不含 API Key 或 Base URL。
- `ImageAssetStore` 将 PNG/JPEG 写入私有 MinIO Bucket 的 `<basePath>/<runId>/<assetKey>.<ext>` 对象；创建使用 `If-None-Match: *` 原子拒绝覆盖。数据库保存创建时的 Bucket、完整对象键、MIME、尺寸和 SHA-256；读取和补偿删除使用该固定位置并重新有界读取、校验哈希，修改默认 Bucket/基础路径不会切断历史资产，客户端不能提交对象键。
- 图片状态按 `QUEUED → PLANNING → GENERATING_REFERENCES → GENERATING_SHOTS → COMPOSITING → COMPLETED` 前进；任一步骤失败进入 `FAILED` 并保留已完成审计/文件。应用启动时会为每个残留活动批次开启独立新事务，把该批次所有 `RUNNING` 步骤与批次本身以同一错误和完成时间一起标为 `FAILED`；已完成步骤保持不变，批次不会续跑。
- 纯故事协议不新增模型调用、数据库表或 HTTP 接口；现有数据库中的用户 Prompt 不被初始化覆盖，运行时协议独立保证交付格式。
- 故事配置与运行记录写入只发生在本地配置库；不得把外部连接的写入、DDL 或迁移能力加入材料链路。

## 部署

- 本地开发：`./scripts/start-dev.sh`。
- Context Router fast：`deploy/context-router/fast/deploy.sh`。
- Context Router full：`deploy/context-router/full/deploy.sh`。
- full 建立 Java 17、Codex CLI、稳定依赖和 Spring Boot Loader 分层基线；fast 校验并复用该基线，只更新 SNAPSHOT 与 application 层。
- Fast/Full 共用 `english-material-backend` 容器、`18744` 端口和 `vibedeploy-shared` 网络；依赖不兼容时 Fast 必须停止并要求 Full。
- Context Router Fast/Full 不挂载图片存储卷；后端从 PostgreSQL 的 `tb_minio_config` 读取连接配置并访问外部 MinIO。
- 两种模式都执行容器健康、重启次数和 `Started AiTaskCenterApplication` 日志验证，并以非零状态报告失败。
- 容器镜像包含 Codex CLI，Compose 只在本机挂载 Codex 配置目录，并以当前宿主用户 UID 运行后端；本地凭据不得写入镜像、源码或日志。
