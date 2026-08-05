---
title: 英语材料 React 管理前端
summary: 提供数据库、AI、本地 CLI 配置和去重单词浏览页面。
---

# 英语材料 React 管理前端

## 项目身份

- 源码根：`web-react`。
- 技术栈：React、TypeScript、Ant Design、Vite、Axios。
- 开发端口：`19638`。
- 生产部署：Nginx 静态站点，通过 `/api` 反向代理 Java 后端。

## 页面边界

- 配置管理包含数据库配置、AI 配置和本地 CLI 配置。
- 去重单词表自动优先使用名称或数据库名匹配 `rob_english_word` 的已配置数据源，并支持关键词、教材难度、来源难度、综合难度、排序和分页。
- 单词详情展示候选例句、评分信息及可用的单词或例句音频。
- Agent 工作台包含 Agent 分类与编辑、当前默认本地 CLI 展示、输入/输出 Schema、在线测试、只读流程视图和最近运行记录；所有 Agent 跟随本地 CLI 配置的当前默认项，当前不包含启用/停用、版本管理、发布或回滚。
- 前端不包含任务队列、任务执行和 Python Worker 页面。

## 请求入口

请求统一定义在 `web-react/src/api.ts`。开发环境默认访问 `http://127.0.0.1:18744/api`；容器生产环境使用同源 `/api`。

主要请求包括 `/connection/*`、`/ai/config`、`/ai/cli/config`、`/word-clean*` 和 `/agents*`。

## 部署

- Context Router fast 和 full 都使用 Docker 层缓存构建当前前端。
- full 模式拉取基础镜像并强制替换容器；fast 模式不拉取基础镜像。
- Context Router 入口位于 `web-react/deploy/context-router/{fast|full}/deploy.sh`。
