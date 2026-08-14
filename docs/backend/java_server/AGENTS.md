---
title: 英语材料 Spring Boot 后端
summary: 维护数据库、AI、故事 Agent 配置与有界故事执行，并只读查询外部英语材料数据。
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
- `AiConfigController` 维护 AI Provider、当前 Provider 和本地 CLI 配置。
- `StoryAgentController` 提供故事 Agent 流程、Prompt 版本和质量预算的配置接口；`StoryAgentService` 负责拼装固定流程、校验可编辑节点与文本生成 Provider、保存 Agent 配置、生成 Prompt 版本快照、恢复历史版本和维护流程预算。
- `StoryAgentCatalog` 固定定义 4 个阶段、12 个可编辑 Agent 和 5 个只读程序/人工节点；`StoryAgentInitializer` 启动时只在某个 Agent 配置缺失时创建该配置及其 v1 快照，已有 Agent 即使缺少历史快照也不补建；默认流程预算缺失时才创建，且不覆盖已有配置。
- `StoryRunController`、`StoryRunExecutionService` 与 `StoryRunQueryService` 创建异步故事运行批次，按固定 Agent 链路执行创作、审核、评分和决策，保存每次实际模型调用的完整输入/输出，并提供批次与详情查询。
- `StoryRunExecutionService` 为 `story-writer` 和 `targeted-reviser` 追加不可编辑的运行时输出协议：步骤表保留模型原始响应，但只有唯一边界内通过纯文本校验的英文场景故事会进入后续审核及 `tb_story_run.final_story`。模型在结束边界外追加的清单或修订说明仅留在原始步骤详情；边界缺失/歧义或故事块内部出现 Markdown、中文说明或清单会使该步骤和批次失败，不会把完整报告冒充最终故事。
- 质量决策支持 `REVISE`、`REWRITE`、`REDIRECT`、`REPITCH`、`REPLAN` 和 `PASS`；每种回退次数、质量轮次和总 Token 都受 `tb_story_flow_config` 的确定性预算限制。Provider 未返回用量时，以输入输出长度估算用量，预算仍然生效。
- `WordCleanController` 根据已保存的连接 ID 查询去重单词、筛选项和例句。
- `StoryWordSourceService` 可从已保存连接中的 `word_library`/`word` 参数化随机读取 1—50 个单词；外部材料库始终只读。
- 故事运行使用进程内有界线程池，不包含 Python Worker、分布式队列、暂停、恢复或删除运行能力。

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
| `/api/ai/cli/config` | 读取或保存本地 CLI 配置 |
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
| `/api/word-clean` | 分页查询去重单词 |
| `/api/word-clean/facets` | 查询难度和来源筛选项 |
| `/api/word-clean/{id}/sentences` | 查询指定单词的候选例句 |

## 数据边界

- 本地配置库由 `TASK_CENTER_DB_URL`、`TASK_CENTER_DB_USER` 和 `TASK_CENTER_DB_PASSWORD` 注入，默认库名为 `english_material`。
- JPA 在可写的本地配置库中维护配置表，以及 `tb_story_run`、`tb_story_run_step` 两张运行记录表。
- 故事 Agent 表只保存 AI Provider ID 字符串，不复制 Provider 详情或密钥，与 `tb_ai_config` 之间没有数据库外键。初始化时如果没有有效的文本生成 Provider，缺失 Agent 的 Provider ID 可以保存为空字符串。
- 更新 Agent 或恢复 Prompt 版本时，请求都携带当前 Agent 的 `updatedAt`；`StoryAgentService` 拒绝过期时间戳，并当下校验 Provider 是否存在、已启用且包含 `TEXT_GENERATION` 能力。之后删除或停用 AI 配置可能使已保存 ID 失效；前端会将其标为不可用并要求重新选择后才能保存。
- 外部材料查询使用 `ConnectionConfigService.openConfiguredConnection` 打开用户选中的连接。
- `WordCleanService` 只使用参数化 `SELECT` 查询 `word_clean`、`word_clean_sentence`、`word_clean_best_sentence` 和 `word_clean_tts`。
- `StoryWordSourceService` 只参数化读取 `word_library` 与 `word`；运行创建后使用本地 JSON 单词快照，不再依赖外部库内容是否变化。
- 运行步骤只保存 Provider ID、模型名、Prompt 版本、完整输入/输出与用量，不复制 API Key、数据库密码或完整连接信息。
- 纯故事协议不新增模型调用、数据库表或 HTTP 接口；现有数据库中的用户 Prompt 不被初始化覆盖，运行时协议独立保证交付格式。
- 故事配置与运行记录写入只发生在本地配置库；不得把外部连接的写入、DDL 或迁移能力加入材料链路。

## 部署

- 本地开发：`./scripts/start-dev.sh`。
- Context Router fast：`deploy/context-router/fast/deploy.sh`。
- Context Router full：`deploy/context-router/full/deploy.sh`。
- full 建立 Java 17、Codex CLI、稳定依赖和 Spring Boot Loader 分层基线；fast 校验并复用该基线，只更新 SNAPSHOT 与 application 层。
- Fast/Full 共用 `english-material-backend` 容器、`18744` 端口和 `vibedeploy-shared` 网络；依赖不兼容时 Fast 必须停止并要求 Full。
- 两种模式都执行容器健康、重启次数和 `Started AiTaskCenterApplication` 日志验证，并以非零状态报告失败。
- 容器镜像包含 Codex CLI，Compose 只在本机挂载 Codex 配置目录，并以当前宿主用户 UID 运行后端；本地凭据不得写入镜像、源码或日志。
