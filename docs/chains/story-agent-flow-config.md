---
title: 故事 Agent 流程与 Prompt 配置链路
summary: 说明 Agent 工作台、Spring Boot 配置接口和本地三张故事配置表之间的当前链路与边界。
---

# 故事 Agent 流程与 Prompt 配置链路

## 前端入口

- `web-react/src/App.tsx` 提供一级“Agent 工作台”入口，并把现有 AI Provider 列表传给 `StoryAgentFlowPage`。
- `web-react/src/StoryAgentFlowPage.tsx` 渲染固定四阶段可点击画布；右侧是页面内联、非 Drawer 的 Prompt 配置中心，展示 Provider、Temperature、启用状态、上下游、动态变量和当前版本。
- 版本历史与质量预算使用弹窗；未保存的 Agent 配置/Prompt 编辑在切换节点、离开工作台或刷新/关闭浏览器时受到保护。质量预算弹窗关闭时会丢弃其中未保存的草稿，不属于该保护范围。响应式样式在窄屏把详情、画布节点和预算表单改为纵向或单列布局。
- 本页不支持增删 Agent、拖拽拓扑、查看运行记录或触发真实故事生成。

## 后端类与接口

`web-react/src/api.ts` 经统一 `/api` 基址调用 `StoryAgentController`；控制器委托 `StoryAgentService` 完成固定流程组装、配置校验、版本快照/恢复和质量预算持久化。

| 方法与路径 | 当前行为 |
| --- | --- |
| `GET /api/story-agents/flow` | 返回 4 个阶段、12 个可编辑 Agent、5 个只读程序/人工节点及预算 |
| `PUT /api/story-agents/{agentKey}` | 更新 Prompt、AI Provider ID、Temperature、启用状态，并生成新版本 |
| `GET /api/story-agents/{agentKey}/versions` | 倒序返回指定 Agent 的 Prompt 快照 |
| `POST /api/story-agents/{agentKey}/versions/{version}/restore` | 复制历史快照并保存为新的最新版本，不删除历史 |
| `PUT /api/story-agents/flow/config` | 更新质量轮次、各类回退次数和总 Token 预算 |

`StoryAgentCatalog` 是四阶段拓扑、节点关系、变量和默认 Prompt 的固定定义；5 个 `PROGRAM`/`HUMAN` 节点只读且不能通过 Agent 更新或版本接口修改。

## 持久化与初始化

| 本地配置表 | 用途 |
| --- | --- |
| `tb_story_agent_config` | 12 个可编辑 Agent 的当前 Prompt、Provider ID 字符串、Temperature、启用状态和版本号 |
| `tb_story_agent_prompt_version` | Agent 初始创建、有效更新或恢复产生的 Prompt 快照 |
| `tb_story_flow_config` | 默认故事流程的质量与 Token 预算 |

`StoryAgentInitializer` 在应用启动时调用 `StoryAgentService.initializeDefaults()`：只在某个 Agent 配置缺失时创建该配置及其 v1 快照；已有 Agent 即使历史快照缺失也不补建。默认流程预算缺失时才创建，且不覆盖已经保存的记录。

故事 Agent 配置和版本表只保存 Provider ID 字符串，不复制 Provider 详情或密钥，与 `tb_ai_config` 之间没有数据库外键。初始化时如果找不到有效文本生成 Provider，缺失 Agent 的 Provider ID 可以保存为空字符串；更新 Agent 或恢复版本时，服务才按当下的 AI 配置确认 Provider 存在、已启用且支持 `TEXT_GENERATION`。之后删除或停用 AI 配置会使已保存 ID 失效，前端将其显示为不可用并要求重新选择后才能保存。

## 数据流

```text
Agent 工作台
  -> web-react/src/api.ts
  -> /api/story-agents/*
  -> StoryAgentController
  -> StoryAgentService
  -> 本地三张 story 配置表
  -> 只读取 tb_ai_config 校验 Provider ID
```

读取流程时，服务用 `StoryAgentCatalog` 的固定拓扑合并数据库中的可编辑配置；只读节点不持久化 Prompt。保存 Agent 时先校验请求，再更新当前配置并追加版本快照；恢复历史时同样追加新版本。

## 错误、并发与边界

- 空 Prompt、缺失/停用/不支持文本生成的 Provider、超出 `0` 到 `2` 的 Temperature、非法节点和越界预算由 `StoryAgentService` 拒绝，`ApiExceptionHandler` 返回 `code = 7` 的统一错误响应。
- Agent 更新携带 `updatedAt` 作为客户端并发前置条件；时间戳过期会提示刷新。`tb_story_agent_config` 另有 JPA `@Version` 锁，写入竞争同样转换为可读错误。流程预算更新当前不携带客户端并发版本，以最后一次成功保存为当前值。
- 本链路只写本地配置库，不写外部材料库或 `word_clean` 相关表，不保存 Provider 密钥或完整连接信息。
- 当前版本没有故事执行引擎、真实生成调用、任务队列、运行状态或运行结果；画布描述的是未来执行所需的配置拓扑，不代表流程已经运行。
