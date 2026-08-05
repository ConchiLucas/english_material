---
title: 英语材料运行与部署说明
summary: 区分开发机启动、旧部署目录和 Context Router 统一编排入口。
---

# 英语材料运行与部署说明

## 本地开发

`./scripts/start-dev.sh` 在宿主机打包并启动 Java 与 Vite，日志和 PID 写入 `.runtime/`。该入口适合开发，不作为 Host Runtime Runner 的部署入口。

## Context Router 部署

- Source of truth：`deploy/context-router/`。
- Workspace start：`deploy/context-router/workspace/start/deploy.sh`。
- Java fast/full：`deploy/context-router/{fast|full}/deploy.sh`。
- React fast/full：`web-react/deploy/context-router/{fast|full}/deploy.sh`。
- 统一实现：`deploy/context-router/deploy.sh`。
- 统一 Compose：`deploy/context-router/compose.yaml`。

fast 使用现有基础镜像和 Docker 构建缓存；full 增加 `--pull` 并强制重建。Workspace 启动先等待后端 HTTP 可用，再启动并检查前端。

## 环境规则

- 本机差异只放入根 `.env.local`，模板为 `.env.local.example`。
- `.env.local` 不进入 Git、Context Router 数据库、运行快照或日志。
- 部署脚本缺少数据库密码时立即失败，不提供仓库内默认口令。

旧 `deploy/backend|frontend|compose` 保留用于历史追踪；其中 Worker 和旧数据库配置不代表当前推荐入口。
