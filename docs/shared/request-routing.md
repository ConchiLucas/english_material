---
title: 英语材料请求寻址
summary: 说明开发与容器环境下 React 请求如何到达 Spring Boot。
---

# 英语材料请求寻址

## 开发模式

- React 默认地址为 `http://127.0.0.1:19638`。
- Axios 默认基地址为 `http://127.0.0.1:18744/api`，可由 `VITE_API_BASE` 覆盖。
- Spring Boot 默认监听 `18744`。

## Context Router 容器模式

- 浏览器访问回环地址上的前端端口。
- 前端使用同源 `/api`。
- Nginx 通过 Compose 服务名 `backend:18744` 代理请求。
- Java 容器通过 `host.docker.internal` 访问宿主机 PostgreSQL，实际连接由根 `.env.local` 注入。
- 用户保存的外部数据源若使用 `127.0.0.1`、`localhost` 或 `::1`，Java 容器仅在运行时将其映射为 `host.docker.internal`；保存值不变，本机非容器开发仍按原地址连接。
