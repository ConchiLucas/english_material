---
title: 英语材料配置管理链路
summary: 说明 React 配置页面如何调用 Spring Boot 并持久化到本地配置库。
---

# 英语材料配置管理链路

1. React `App.tsx` 加载数据库、AI 和本地 CLI 配置。
2. `web-react/src/api.ts` 通过 `/api/connection/*`、`/api/ai/config` 和 `/api/ai/cli/config` 调用后端。
3. Nginx 容器模式把 `/api` 转发到 `backend:18744`；开发模式直接访问本机后端。
4. Controller 校验请求并调用 `ConnectionConfigService` 或 `AiConfigService`。
5. Repository 通过 JPA 读写本地 `english_material` 配置库。
6. 数据库密码和 AI Key 只属于受控配置数据，不进入文档和部署快照。
