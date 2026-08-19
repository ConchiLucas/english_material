---
title: 英语材料 React 管理前端
summary: 提供配置管理、故事与图片 Agent 编排和运行审计，以及去重单词浏览页面。
---

# 英语材料 React 管理前端

## 项目身份

- 源码根：`web-react`。
- 技术栈：React、TypeScript、Ant Design、Vite、Axios。
- 开发端口：`19638`。
- 生产部署：Nginx 静态站点，通过 `/api` 反向代理 Java 后端。

## 页面边界

- 配置管理依次包含数据库配置、AI 配置、本地 CLI 配置、MinIO 配置和图片模型配置。MinIO 页面维护启用状态、Endpoint、Access Key、Secret Key、SSL、私有 Bucket 和基础路径，并支持连接测试；首次保存后 Endpoint/SSL 只读，已保存 Secret Key 不回显，留空表示继续使用。图片模型配置仍读写共享 `tb_ai_config`，只筛选图片能力 Provider。
- 一级导航包含“Agent 工作台”。工作台画布固定展示策划与创意、写作与候选、独立质量委员会、修订与交付四个阶段，全部节点均可点击查看。
- 画布右侧使用页面内联的 Prompt 配置中心，不使用 Drawer；可编辑 Agent 展示并保存 AI Provider、Temperature、启用状态和 System Prompt，同时展示上下游、动态变量、当前版本与更新时间。
- Prompt 版本弹窗支持查看历史并将选定版本恢复为新的最新版本；质量预算弹窗配置轮次、回退次数和总 Token 上限。
- 工作台页头提供“开始运行”和“运行记录”。开始运行支持逐行输入单词，或从已保存的外部词库随机预览后创建正式批次。
- 运行记录是覆盖整个浏览器视口的独立界面：上方约四分之三依次展示批次、按实际调用顺序保存的 Agent（同一 Agent 多轮调用保留多行）和所选 Agent 的完整输入/输出；右侧顶部将当前批次全部单词保持在一行；底部约四分之一独立滚动展示经过后端校验的纯英文故事，只包含 `Scene N: Plain English Title` 场景标题和英文段落，不展示 Markdown、中文说明、清单或修订记录。
- 批次列表仅显示运行时间和单词数，主审计界面不展示评分、预算停止、是否通过或 Token 等辅助状态；切换批次会同时切换单词、Agent 调用和最终故事。
- 顶部“英语素材项目”是可展开入口，第一版子菜单仅包含“Agent 生成结果”。结果页只展示 `COMPLETED` 且最终故事非空的故事批次，按创建时间倒序执行服务端分页；默认每页 10 条，可切换 20 或 100 条。每条结果占满内容区宽度，上层单行显示首个 Scene 标题、年级、单词数和生成时间，下层按自然高度完整展示故事并可复制全文；不展示图片结果、Agent 中间输出、评分、Token、筛选或删除操作。
- 未保存的 Agent 配置/Prompt 编辑会在切换节点、离开 Agent 工作台和浏览器刷新/关闭时受到保护；质量预算弹窗的未保存草稿在关闭弹窗时丢弃，不属于该保护范围。窄屏下详情与画布改为纵向布局，节点和预算表单改单列。
- 如果已保存的 Provider ID 因 AI 配置删除或停用而失效，工作台将其标为不可用，并要求选择当前已启用且支持文本生成的 Provider 后才能保存 Agent。
- 去重单词表自动优先使用名称或数据库名匹配 `rob_english_word` 的已配置数据源，并支持关键词、教材难度、来源难度、综合难度、排序和分页。
- 单词详情展示候选例句、评分信息及可用的单词或例句音频。
- 流程拓扑固定，前端不提供 Agent 增删或拖拽编排；运行记录只审计 Java 后端的进程内执行，不包含 Python Worker 或队列管理页面。
- 一级导航在“Agent 工作台”后提供独立“图片工作台”。配置页固定展示 4 个阶段、9 个可编辑文本 Agent 和 3 个只读程序节点；Agent 可编辑 Prompt、文本 Provider、Temperature 和启用状态，并可查看或恢复追加式 Prompt 版本。
- 图片工作台的“画风预设”页可创建、编辑、启用或停用画风；名称、正向风格约束、负向约束和说明四个文本字段都必填，前端在提交前显示缺失错误，后端再次校验。“图片模型”页镜像后端 `ImageProviderPolicy`：只列出 ID/模型/Base URL 完整，类型为 `openai-compatible`、已启用、同时支持 `IMAGE_GENERATION` 与 `IMAGE_REFERENCE`，且 URL/options 合法的 Provider；页面明确展示固定 `1536×864`、每 Scene 最多 5 张、全篇最多 20 张。
- 图片 Agent 页面编辑和版本化数据库中的 `system_prompt`，不能关闭由代码目录持有的 V2 结构合同。无论数据库中是旧 Prompt、自定义 Prompt，还是用户删除/改写过合同文本，后端创建批次时都会确保实际 Prompt 含当前完整合同；相同合同已经存在时不会重复附加。
- “开始生成”只允许从后端返回的已有最终故事和启用画风中选择，预览目标年级、单词和完整故事；九个 Agent、画风、图片 Provider 或故事任一不合格时禁止提交，提交期间防止重复创建。
- “图片记录”覆盖整个视口：上方展示批次、单行输入单词、实际执行步骤及所选步骤的完整输入/原始输出；底部约四分之一切换最终分镜图与参考设定图。分镜卡展示 Scene/Shot、来源片段、对白/字幕和最终提示词，图片可打开大图。
- 图片活动批次每 2 秒轮询，进入 `COMPLETED` 或 `FAILED` 后停止；切换批次会废弃旧请求结果。界面不提供视觉评分、通过判定、候选图、重试、自动/手工重绘或审核提交控件，结果由用户人工查看。
- 图片工作台的 Agent、画风和图片模型草稿使用独立未保存保护；切换节点、页签、一级菜单和浏览器刷新/关闭前会提示，图片运行记录不修改配置草稿。

## 请求入口

请求统一定义在 `web-react/src/api.ts`。开发环境默认访问 `http://127.0.0.1:18744/api`；容器生产环境使用同源 `/api`。

主要请求包括 `/connection/*`、`/ai/config`、`POST /ai/config/image/bootstrap`、`/ai/cli/config`、`/minio-config*`、`/story-agents/*`、`/story-runs*`（其中 `GET /story-runs/results` 只接受 10/20/100 的分页大小）、`/image-agents/*`、`/image-style-presets*`、`/image-runs*`、`/image-assets/{id}/content` 和 `/word-clean*`。图片字节仍通过后端资产 ID 接口读取，浏览器不直接访问 MinIO。

## 部署

- Context Router fast 使用 Node/Vite 开发容器、当前源码挂载和锁文件哈希隔离的 `node_modules` Volume。
- full 模式使用 Node 22 执行 `npm ci` 与生产构建，再生成只读 Nginx 镜像。
- Fast/Full 共用 `english-material-frontend` 容器、宿主机端口 `19638` 和 `vibedeploy-shared` 网络；前端通过 `/api` 代理访问 `english-material-backend:18744`。
- Context Router 入口位于 `web-react/deploy/context-router/{fast|full}/deploy.sh`。
