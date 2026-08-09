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

Java Full 从当前本地代码完整打包 Spring Boot JAR，提取 `dependencies`、`spring-boot-loader`、`snapshot-dependencies` 和 `application` 四层，建立带哈希标签的 Java 17、Codex CLI 与稳定依赖基线；Java Fast 重新打包当前代码并严格验证基线，只叠加 SNAPSHOT 与业务层。依赖或基线不一致时 Fast 必须失败并要求 Full。

React Fast 使用锁文件哈希隔离的 `node_modules` Volume 和源码挂载运行 Vite；React Full 执行 `npm ci`、生产构建并生成只读 Nginx 镜像。Fast/Full 替换同一个前端容器并保持宿主机端口 `19638` 不变。

后端和前端都加入 `vibedeploy-shared`。Workspace 启动依次执行后端 Full 和前端 Full，每一步都等待容器健康。

## 环境规则

- 本机差异只放入根 `.env.local`，模板为 `.env.local.example`。
- `.env.local` 不进入 Git、Context Router 数据库、运行快照或日志。
- 部署脚本缺少数据库密码时立即失败，不提供仓库内默认口令。

旧 `deploy/backend|frontend|compose` 保留用于历史追踪；其中 Worker 和旧数据库配置不代表当前推荐入口。
