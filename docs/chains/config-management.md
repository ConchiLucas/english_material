---
title: 英语材料配置管理链路
summary: 说明 React 配置页面如何调用 Spring Boot 并持久化到本地配置库。
---

# 英语材料配置管理链路

1. React `App.tsx` 加载数据库、AI 和本地 CLI 配置；独立 `MinioConfigPage` 只在进入该页时加载 MinIO 配置。
2. `web-react/src/api.ts` 通过 `/api/connection/*`、`/api/ai/config`、`/api/ai/cli/config` 和 `/api/minio-config*` 调用后端。
3. Nginx 容器模式把 `/api` 转发到 `backend:18744`；开发模式直接访问本机后端。
4. Controller 校验请求并调用 `ConnectionConfigService`、`AiConfigService` 或 `MinioConfigService`。
5. Repository 通过 JPA 读写本地 `english_material` 配置库。
6. 数据库密码、AI Key 和 MinIO Secret Key 只属于受控配置数据，不进入文档和部署快照；MinIO 查询只返回 `secretConfigured`。

MinIO 保存与连接测试都会验证私有 Bucket 是否存在，缺失时创建，再对 `<basePath>/.readiness/` 下随机对象执行原子写入、有界读取和删除。代码不设置公开 Bucket Policy。
首次验证保存后 Endpoint 与 SSL 固定，避免图片上传和资产元数据提交之间发生服务切换；Access/Secret Key 可轮换，默认 Bucket/基础路径可调整，历史资产继续使用记录内固定的 Bucket 与完整对象键。
