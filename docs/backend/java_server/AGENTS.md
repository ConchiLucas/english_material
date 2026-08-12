---
title: 英语材料 Spring Boot 后端
summary: 维护数据库连接、AI 与本地 CLI 配置，并参数化只读查询外部 word_clean 数据。
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
- `WordCleanController` 根据已保存的连接 ID 查询去重单词、筛选项和例句。
- 当前源码没有项目配置、任务配置、任务队列、任务结果或 Python Worker API。

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
| `/api/word-clean` | 分页查询去重单词 |
| `/api/word-clean/facets` | 查询难度和来源筛选项 |
| `/api/word-clean/{id}/sentences` | 查询指定单词的候选例句 |

## 数据边界

- 本地配置库由 `TASK_CENTER_DB_URL`、`TASK_CENTER_DB_USER` 和 `TASK_CENTER_DB_PASSWORD` 注入，默认库名为 `english_material`。
- JPA 当前维护 `tb_connection` 与 `tb_ai_config` 等本地配置表。
- 外部材料查询使用 `ConnectionConfigService.openConfiguredConnection` 打开用户选中的连接。
- `WordCleanService` 只使用参数化 `SELECT` 查询 `word_clean`、`word_clean_sentence`、`word_clean_best_sentence` 和 `word_clean_tts`。
- 不得把外部连接的写入、DDL 或迁移能力加入材料浏览链路。

## 部署

- 本地开发：`./scripts/start-dev.sh`。
- Context Router fast：`deploy/context-router/fast/deploy.sh`。
- Context Router full：`deploy/context-router/full/deploy.sh`。
- full 建立 Java 17、Codex CLI、稳定依赖和 Spring Boot Loader 分层基线；fast 校验并复用该基线，只更新 SNAPSHOT 与 application 层。
- Fast/Full 共用 `english-material-backend` 容器、`18744` 端口和 `vibedeploy-shared` 网络；依赖不兼容时 Fast 必须停止并要求 Full。
- 两种模式都执行容器健康、重启次数和 `Started AiTaskCenterApplication` 日志验证，并以非零状态报告失败。
- 容器镜像包含 Codex CLI，Compose 只在本机挂载 Codex 配置目录，并以当前宿主用户 UID 运行后端；本地凭据不得写入镜像、源码或日志。
