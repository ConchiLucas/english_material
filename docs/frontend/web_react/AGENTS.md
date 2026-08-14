---
title: 英语材料 React 管理前端
summary: 提供配置管理、故事 Agent 编排与运行审计，以及去重单词浏览页面。
---

# 英语材料 React 管理前端

## 项目身份

- 源码根：`web-react`。
- 技术栈：React、TypeScript、Ant Design、Vite、Axios。
- 开发端口：`19638`。
- 生产部署：Nginx 静态站点，通过 `/api` 反向代理 Java 后端。

## 页面边界

- 配置管理包含数据库配置、AI 配置和本地 CLI 配置。
- 一级导航包含“Agent 工作台”。工作台画布固定展示策划与创意、写作与候选、独立质量委员会、修订与交付四个阶段，全部节点均可点击查看。
- 画布右侧使用页面内联的 Prompt 配置中心，不使用 Drawer；可编辑 Agent 展示并保存 AI Provider、Temperature、启用状态和 System Prompt，同时展示上下游、动态变量、当前版本与更新时间。
- Prompt 版本弹窗支持查看历史并将选定版本恢复为新的最新版本；质量预算弹窗配置轮次、回退次数和总 Token 上限。
- 工作台页头提供“开始运行”和“运行记录”。开始运行支持逐行输入单词，或从已保存的外部词库随机预览后创建正式批次。
- 运行记录是覆盖整个浏览器视口的独立界面：上方约四分之三依次展示批次、按实际调用顺序保存的 Agent（同一 Agent 多轮调用保留多行）和所选 Agent 的完整输入/输出；右侧顶部将当前批次全部单词保持在一行；底部约四分之一独立滚动展示最终故事结果。
- 批次列表仅显示运行时间和单词数，主审计界面不展示评分、预算停止、是否通过或 Token 等辅助状态；切换批次会同时切换单词、Agent 调用和最终故事。
- 未保存的 Agent 配置/Prompt 编辑会在切换节点、离开 Agent 工作台和浏览器刷新/关闭时受到保护；质量预算弹窗的未保存草稿在关闭弹窗时丢弃，不属于该保护范围。窄屏下详情与画布改为纵向布局，节点和预算表单改单列。
- 如果已保存的 Provider ID 因 AI 配置删除或停用而失效，工作台将其标为不可用，并要求选择当前已启用且支持文本生成的 Provider 后才能保存 Agent。
- 去重单词表自动优先使用名称或数据库名匹配 `rob_english_word` 的已配置数据源，并支持关键词、教材难度、来源难度、综合难度、排序和分页。
- 单词详情展示候选例句、评分信息及可用的单词或例句音频。
- 流程拓扑固定，前端不提供 Agent 增删或拖拽编排；运行记录只审计 Java 后端的进程内执行，不包含 Python Worker 或队列管理页面。

## 请求入口

请求统一定义在 `web-react/src/api.ts`。开发环境默认访问 `http://127.0.0.1:18744/api`；容器生产环境使用同源 `/api`。

主要请求包括 `/connection/*`、`/ai/config`、`/ai/cli/config`、`/story-agents/*`、`/story-runs*` 和 `/word-clean*`。`/story-runs` 覆盖创建、列表、详情、词库列表与随机单词预览。

## 部署

- Context Router fast 使用 Node/Vite 开发容器、当前源码挂载和锁文件哈希隔离的 `node_modules` Volume。
- full 模式使用 Node 22 执行 `npm ci` 与生产构建，再生成只读 Nginx 镜像。
- Fast/Full 共用 `english-material-frontend` 容器、宿主机端口 `19638` 和 `vibedeploy-shared` 网络；前端通过 `/api` 代理访问 `english-material-backend:18744`。
- Context Router 入口位于 `web-react/deploy/context-router/{fast|full}/deploy.sh`。
