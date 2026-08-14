---
title: 故事 Agent 流程与 Prompt 配置链路
summary: 说明 Agent 配置、正式故事运行、逐 Agent 输入输出审计和本地持久化链路。
---

# 故事 Agent 流程与 Prompt 配置链路

## 前端入口

- `web-react/src/App.tsx` 提供一级“Agent 工作台”入口，并把现有 AI Provider 与数据库连接列表传给 `StoryAgentFlowPage`。
- `web-react/src/StoryAgentFlowPage.tsx` 渲染固定四阶段可点击画布；右侧是页面内联、非 Drawer 的 Prompt 配置中心，展示 Provider、Temperature、启用状态、上下游、动态变量和当前版本。
- 版本历史与质量预算使用弹窗；未保存的 Agent 配置/Prompt 编辑在切换节点、离开工作台或刷新/关闭浏览器时受到保护。质量预算弹窗关闭时会丢弃其中未保存的草稿，不属于该保护范围。响应式样式在窄屏把详情、画布节点和预算表单改为纵向或单列布局。
- “开始运行”从手工输入或外部词库随机预览得到显式单词快照；“运行记录”打开 `StoryRunHistory` 全屏审计界面。
- 全屏界面上方约占四分之三：左侧批次只显示时间与单词数，中间逐行显示实际 Agent 调用，右侧在单行单词栏下并排显示完整输入/输出；底部约占四分之一显示最终故事。批次、Agent 与结果始终联动。

## 后端类与接口

`web-react/src/api.ts` 经统一 `/api` 基址调用 `StoryAgentController`；控制器委托 `StoryAgentService` 完成固定流程组装、配置校验、版本快照/恢复和质量预算持久化。

| 方法与路径 | 当前行为 |
| --- | --- |
| `GET /api/story-agents/flow` | 返回 4 个阶段、12 个可编辑 Agent、5 个只读程序/人工节点及预算 |
| `PUT /api/story-agents/{agentKey}` | 更新 Prompt、AI Provider ID、Temperature、启用状态，并生成新版本 |
| `GET /api/story-agents/{agentKey}/versions` | 倒序返回指定 Agent 的 Prompt 快照 |
| `POST /api/story-agents/{agentKey}/versions/{version}/restore` | 请求体携带当前 Agent 的 `updatedAt`，复制历史快照并保存为新的最新版本，不删除历史 |
| `PUT /api/story-agents/flow/config` | 更新质量轮次、各类回退次数和总 Token 预算 |
| `POST /api/story-runs` | 校验 1—50 个显式单词，保存 `QUEUED` 批次并交给有界线程池 |
| `GET /api/story-runs` | 按新到旧返回批次及单词快照 |
| `GET /api/story-runs/{runId}` | 返回完整步骤输入/输出和最终故事，活动批次由前端轮询 |
| `GET /api/story-runs/word-libraries` | 参数化读取指定连接中的启用词库 |
| `POST /api/story-runs/random-words` | 参数化随机读取指定词库中的可用单词 |

`StoryAgentCatalog` 是四阶段拓扑、节点关系、变量和默认 Prompt 的固定定义；5 个 `PROGRAM`/`HUMAN` 节点只读且不能通过 Agent 更新或版本接口修改。

## 持久化与初始化

| 本地配置表 | 用途 |
| --- | --- |
| `tb_story_agent_config` | 12 个可编辑 Agent 的当前 Prompt、Provider ID 字符串、Temperature、启用状态和版本号 |
| `tb_story_agent_prompt_version` | Agent 初始创建、有效更新或恢复产生的 Prompt 快照 |
| `tb_story_flow_config` | 默认故事流程的质量与 Token 预算 |
| `tb_story_run` | 单词快照、目标年级、状态、最终故事、总用量与起止时间 |
| `tb_story_run_step` | 每次实际 Agent 调用的顺序、质量轮次、Prompt/Provider、完整输入输出、用量与耗时 |

`StoryAgentInitializer` 在应用启动时调用 `StoryAgentService.initializeDefaults()`：只在某个 Agent 配置缺失时创建该配置及其 v1 快照；已有 Agent 即使历史快照缺失也不补建。默认流程预算缺失时才创建，且不覆盖已经保存的记录。

故事 Agent 配置和版本表只保存 Provider ID 字符串，不复制 Provider 详情或密钥，与 `tb_ai_config` 之间没有数据库外键。运行步骤同样只保存 Provider ID 与模型名，API Key 只在发起模型请求时从 `tb_ai_config` 读取，不进入运行表或响应。

## 数据流

```text
Agent 工作台
  -> web-react/src/api.ts
  -> /api/story-agents/*
  -> StoryAgentController
  -> StoryAgentService
  -> 本地三张 story 配置表
  -> 只读取 tb_ai_config 校验 Provider ID

开始运行
  -> POST /api/story-runs
  -> 保存 tb_story_run 单词快照
  -> StoryRunExecutionService 有界异步执行
  -> 每次模型调用追加 tb_story_run_step
  -> 作家/修订原始响应保留在步骤表，并严格提取纯英文故事块
  -> 后续审核只读取已提取的纯故事正文
  -> 审核 / 评分 / 决策选择 PASS 或有预算的回退动作
  -> 保存最终故事或预算上限时的当前故事
  -> StoryRunHistory 轮询详情并按批次/调用顺序展示
```

读取流程时，服务用 `StoryAgentCatalog` 的固定拓扑合并数据库中的可编辑配置；只读节点不持久化 Prompt。保存 Agent 时先校验请求，再更新当前配置并追加版本快照；恢复历史时同样追加新版本。

## 错误、并发与边界

- 空 Prompt、缺失/停用/不支持文本生成的 Provider、超出 `0` 到 `2` 的 Temperature、非法节点和越界预算由 `StoryAgentService` 拒绝，`ApiExceptionHandler` 返回 `code = 7` 的统一错误响应。
- Agent 更新和 Prompt 版本恢复都携带目标 Agent 当前的 `updatedAt` 作为客户端并发前置条件；时间戳缺失或过期会提示刷新。版本弹窗恢复时按弹窗所属 Agent key 从最新流程状态读取该时间戳，避免节点切换或并行保存响应造成串台、陈旧恢复。`tb_story_agent_config` 另有 JPA `@Version` 锁，写入竞争同样转换为可读错误。流程预算更新当前不携带客户端并发版本，以最后一次成功保存为当前值。
- 手工单词按大小写无关去重；随机单词只从用户选择的已保存连接中参数化读取 `word_library`/`word`。正式运行保存快照后不再查询外部材料库。
- 质量决策可回到定向修订、作家、导演、创意或用词策划；每次回退都形成新的步骤记录，不覆盖旧记录。达到轮次、动作次数或 Token 上限后停止继续调用。
- `story-writer` 和 `targeted-reviser` 的数据库 Prompt 仍可编辑，执行时另行追加不可编辑的 `STORY_TEXT_BEGIN` / `STORY_TEXT_END` 协议。原始响应完整保存在 `tb_story_run_step.output_text`；只有包含纯英文 `Scene N:` 标题和故事段落且不含 Markdown、中文说明、清单、表格或修订记录的故事块才进入审核链和 `tb_story_run.final_story`。格式错误会使步骤与批次失败，不回退为保存完整响应。
- 纯故事输出没有新增最终整理 Agent，不增加正常链路的模型调用次数，也不新增表或 API；历史运行记录保持原值。
- 本链路只写本地配置库，不写外部材料库，不保存 Provider 密钥、数据库密码或完整连接信息。
- 当前执行器是单实例进程内有界线程池，不提供分布式队列、运行暂停/恢复、删除或跨重启续跑。
