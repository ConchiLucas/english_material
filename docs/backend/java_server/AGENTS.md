---
title: 英语材料 Spring Boot 后端
summary: 维护数据库连接、AI、本地 CLI 与故事 Agent 流程配置，并参数化只读查询外部 word_clean 数据。
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
- `StoryAgentCatalog` 固定定义 4 个阶段、12 个可编辑 Agent 和 5 个只读程序/人工节点；`StoryAgentInitializer` 启动时只补齐缺失的 Agent、初始版本和默认流程预算，不覆盖已有配置。
- `WordCleanController` 根据已保存的连接 ID 查询去重单词、筛选项和例句。
- 本版 Story Agent 能力只管理配置，不运行或生成故事，也没有执行引擎、任务队列、运行记录、任务结果或 Python Worker API。

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
| `POST /api/story-agents/{agentKey}/versions/{version}/restore` | 将历史快照恢复为新的最新版本 |
| `PUT /api/story-agents/flow/config` | 保存质量轮次、回退次数和总 Token 预算 |
| `/api/word-clean` | 分页查询去重单词 |
| `/api/word-clean/facets` | 查询难度和来源筛选项 |
| `/api/word-clean/{id}/sentences` | 查询指定单词的候选例句 |

## 数据边界

- 本地配置库由 `TASK_CENTER_DB_URL`、`TASK_CENTER_DB_USER` 和 `TASK_CENTER_DB_PASSWORD` 注入，默认库名为 `english_material`。
- JPA 在可写的本地配置库中维护 `tb_connection`、`tb_ai_config`、`tb_story_agent_config`、`tb_story_agent_prompt_version` 和 `tb_story_flow_config` 等配置表。
- 故事 Agent 表只引用现有 `tb_ai_config` 中的 AI Provider ID；Provider 的具体配置仍由 AI 配置链路管理，不复制到故事 Agent 表。
- 外部材料查询使用 `ConnectionConfigService.openConfiguredConnection` 打开用户选中的连接。
- `WordCleanService` 只使用参数化 `SELECT` 查询 `word_clean`、`word_clean_sentence`、`word_clean_best_sentence` 和 `word_clean_tts`。
- 故事 Agent 配置写入只发生在本地配置库，不触碰外部 `word_clean` 材料表；不得把外部连接的写入、DDL 或迁移能力加入材料浏览链路。

## 部署

- 本地开发：`./scripts/start-dev.sh`。
- Context Router fast：`deploy/context-router/fast/deploy.sh`。
- Context Router full：`deploy/context-router/full/deploy.sh`。
- full 建立 Java 17、Codex CLI、稳定依赖和 Spring Boot Loader 分层基线；fast 校验并复用该基线，只更新 SNAPSHOT 与 application 层。
- Fast/Full 共用 `english-material-backend` 容器、`18744` 端口和 `vibedeploy-shared` 网络；依赖不兼容时 Fast 必须停止并要求 Full。
- 两种模式都执行容器健康、重启次数和 `Started AiTaskCenterApplication` 日志验证，并以非零状态报告失败。
- 容器镜像包含 Codex CLI，Compose 只在本机挂载 Codex 配置目录，并以当前宿主用户 UID 运行后端；本地凭据不得写入镜像、源码或日志。
