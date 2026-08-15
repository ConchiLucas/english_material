---
title: 英语材料开发工作空间文档入口
summary: 定义英语材料配置后端、React 管理前端、故事与图片工作台、只读材料查询链路和统一部署方式。
---

# 英语材料开发工作空间文档入口

本工作空间是一个单仓库双 Project 应用：根目录为 Spring Boot 配置、材料查询及进程内故事/图片执行后端，`web-react` 为 React 管理前端。当前源码不包含 Python Worker 或分布式任务队列；故事和图片批次均由 Java 进程内有界线程池执行。

## 如何使用本工作空间

1. 后端接口、配置持久化或外部数据库只读查询任务，先读取 `docs/backend/README.md`。
2. 前端页面、请求封装或交互任务，先读取 `docs/frontend/README.md`。
3. 涉及数据库、请求寻址、启动或部署时，先读取 `docs/shared/README.md`。
4. 涉及前端到后端再到数据库的完整流程时，先读取 `docs/chains/README.md`。
5. 历史 `docs/ai-task-center-*` 文档可能包含已经移除的 Worker 和任务中心设计，不作为当前事实，最终以本树、源码和受控环境为准。

## Context Router MCP

- 新任务涉及业务规则、启动、数据库、部署或跨层链路时，先调用 `prepare_task_context` 并保存 `task_id`。
- 目标不明确时先调用 `search_context_documents`，读取正文时只调用 `read_context_document`。
- prepare 返回的数据库别名只用于当前任务；查询前先搜索对象，只执行单条、有界、只读 SQL。
- 环境配置和凭据不得进入回答、日志、源码或文档。
- 修改已登记 Project 后，按 Context Router 约定提交变化并使用 Workspace 运行编排更新目标服务。

## 文档维护约定

- 根文档只维护工作空间规则和导航，单项目事实写入对应 Project 文档。
- 跨项目事实写入 `docs/shared/`，完整业务流程写入 `docs/chains/`。
- 父子关系只由 `## 下级文档` 下的“功能说明 / 相对路径”表格定义。
- 无法从源码或配置确认的内容标记为“待运行核对”，不把历史设计写成当前能力。
- 文档保留项目名、接口、表名和类名等检索词，但不记录密码、Token 或完整连接串。

## 下级文档

| 功能说明 | 相对路径 |
| --- | --- |
| 后端服务文档索引 | `./docs/backend/README.md` |
| 前端项目文档索引 | `./docs/frontend/README.md` |
| 公共技术文档索引 | `./docs/shared/README.md` |
| 跨层业务链路索引 | `./docs/chains/README.md` |

## 工作空间操作约束

- 本仓库只有一个 Git 根；修改前仍需明确变更属于根后端、`web-react` 前端还是共享部署配置。
- 本地开发入口为 `./scripts/start-dev.sh`；Context Router 编排入口为 `deploy/context-router/`。
- 本机差异只放入被 Git 忽略的根 `.env.local`，模板使用 `.env.local.example`。
- 图片故事文件根目录由 `IMAGE_STORY_STORAGE_ROOT` 控制；数据库仅保存受控相对路径和 SHA-256，客户端只能通过资产 ID API 读取。
- 图片工作台固定为 4 阶段、9 个文本 Agent 和 3 个程序节点；第一版不增加视觉评审、自动重绘、图片重试或审核写入链路。
- 外部业务数据源只允许参数化只读查询，不得通过本服务执行建表、改表、迁移或写入。

## Context Router 部署 MCP 使用规则

- 本工作空间的部署唯一事实源是 `deploy/context-router/`；根 `manifest.yaml`、Workspace `workspace/start/deploy.sh`，以及各 Project 的 `fast/deploy.sh`、`full/deploy.sh` 必须与项目结构保持同步。机器差异配置只放在根 `.env.local`，不得提交、写入文档、传给 Context Router 或出现在日志中。
- 新任务先调用 Context Router MCP `prepare_task_context`，`cwd` 传本工作空间内的实际当前目录，保存返回的 `task_id`；后续部署调用必须复用该 `task_id`。
- 用户要求“启动”“启动项目”“启动前后端”时，调用 `start_workspace(task_id)`。启动边界始终是整个 Workspace，不使用单 Project 启动替代。
- 完成代码修改后，调用一次 `apply_workspace_changes(task_id, changed_files)`；`changed_files` 必须包含本轮全部真实变更，并使用 Workspace 相对路径。服务端负责按 Project 路由以及选择 `fast` 或 `full`。
- `start_workspace` 或 `apply_workspace_changes` 返回操作 `id` 后，使用 `get_workspace_operation(operation_id)` 查询，直到状态进入 `succeeded`、`failed`、`cancelled` 或 `interrupted`；失败时向用户报告失败步骤和有界日志，不得把排队成功当成部署成功。
- 新任务优先使用 `apply_workspace_changes`、`start_workspace` 和 `get_workspace_operation`。`apply_project_changes`、`get_project_operation` 是保留的兼容入口，不作为首选。
- 如果 `apply_workspace_changes` 或 `get_workspace_operation` 尚未出现在当前 Codex 任务的 MCP 工具列表中，但旧兼容工具可用，代码修改后的部署可以回退：按受影响 Project 分别调用一次 `apply_project_changes(project_id, changed_files)`，其中 `changed_files` 使用该 Project 相对路径；再用 `get_project_operation(operation_id)` 查询到终态。跨 Project 修改必须逐个 Project 调用，并向用户说明本次使用了兼容模式。
- 旧兼容工具不具备 Workspace 完整启动语义。用户明确要求“启动”“启动前后端”时，如果 `start_workspace` 不可见，应先重新连接 Context Router MCP 或新建 Codex 任务刷新工具发现；不得用项目变更操作冒充完整启动，也不得静默使用宿主机命令绕过运行编排。
- 执行部署 MCP 前，确认控制端与 Host Runtime Runner 已启动、仓库 deploy 配置已同步，并确认本机根 `.env.local` 已配置。控制端启动规范见 `/Users/conchi/workforce/python_workforce/agent-context-router/docs/STARTUP_GUIDE.md`。
