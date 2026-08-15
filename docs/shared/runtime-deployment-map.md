---
title: 英语材料运行与部署说明
summary: 区分开发机启动、旧部署目录和 Context Router 统一编排入口。
---

# 英语材料运行与部署说明

## 本地开发

`./scripts/start-dev.sh` 在宿主机打包并启动 Java 与 Vite，日志和 PID 写入 `.runtime/`。图片资产使用 PostgreSQL 中保存的 MinIO 配置，脚本不创建本地图片目录。该入口适合开发，不作为 Host Runtime Runner 的部署入口。

## Context Router 部署

- Source of truth：`deploy/context-router/`。
- Workspace start：`deploy/context-router/workspace/start/deploy.sh`。
- Java fast/full：`deploy/context-router/{fast|full}/deploy.sh`。
- React fast/full：`web-react/deploy/context-router/{fast|full}/deploy.sh`。

Java Full 从当前本地代码完整打包 Spring Boot JAR，提取 `dependencies`、`spring-boot-loader`、`snapshot-dependencies` 和 `application` 四层，建立带哈希标签的 Java 17、Codex CLI 与稳定依赖基线；Java Fast 重新打包当前代码并严格验证基线，只叠加 SNAPSHOT 与业务层。依赖或基线不一致时 Fast 必须失败并要求 Full。

Java Full 拉取 Java 与 Node 基础镜像时使用有界等待，默认上限为 120 秒，可用正整数秒数 `ENGLISH_MATERIAL_IMAGE_PULL_TIMEOUT` 覆盖。公开基础镜像的拉取与基线构建使用临时无凭据 Docker 配置并复用当前 Docker Context 的守护进程地址，避免本机凭据助手故障阻塞；不会读取或复制用户的 Registry 凭据。部署会把 `linux/aarch64` 等平台别名规范化，并校验缓存镜像的 OS、Architecture 和可用 Variant；拉取失败或超时后，只有对应 tag 已存在且与目标平台兼容时才会输出警告并继续，否则部署失败。超时会先终止并回收拉取进程，避免 Host Runtime Runner 长时间卡在无界 `docker pull`。

React Fast 使用锁文件哈希隔离的 `node_modules` Volume 和源码挂载运行 Vite；React Full 执行 `npm ci`、生产构建并生成只读 Nginx 镜像。React Fast/Full 构建公开基础镜像时都使用临时无凭据 Docker 配置、当前 Docker Context 地址和显式探测过的 buildx 插件，避免本机 Registry 凭据助手故障阻塞构建；不会读取或复制用户的 Registry 凭据。Fast/Full 替换同一个前端容器并保持宿主机端口 `19638` 不变。

后端和前端都加入 `vibedeploy-shared`。Workspace 启动依次执行后端 Full 和前端 Full，每一步都等待容器健康。

图片文件不进入镜像、容器卷或 PostgreSQL，而是进入配置管理维护的私有 MinIO Bucket。Context Router Fast/Full 不注入图片路径、不挂载 `english-material-image-story`，因此容器仍可保持只读根文件系统；Fast/Full 切换或容器替换不会删除 MinIO 对象。

## 环境规则

- 本机差异只放入根 `.env.local`，模板为 `.env.local.example`。
- `.env.local` 不进入 Git、Context Router 数据库、运行快照或日志。
- 部署脚本缺少数据库密码时立即失败，不提供仓库内默认口令。
- MinIO Endpoint、Access Key、Secret Key、SSL、Bucket 和基础路径由 `/api/minio-config` 持久化；Secret Key 的查询响应永不回传原值，也不得写入 `.env.local`、部署日志或文档。

旧 `deploy/backend|frontend|compose` 保留用于历史追踪；其中 Worker 和旧数据库配置不代表当前推荐入口。
