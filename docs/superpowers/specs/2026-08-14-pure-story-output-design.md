---
title: 纯英文故事最终输出设计
summary: 约束故事生成 Agent 的运行时输出协议，并保证最终结果只保存纯英文场景故事。
---

# 纯英文故事最终输出设计

## 背景与根因

当前 `story-writer` 与 `targeted-reviser` 的可编辑 Prompt 要求同时输出故事正文、目标词位置清单和修订记录。执行器虽然支持从 `STORY_TEXT_BEGIN` 与 `STORY_TEXT_END` 之间提取正文，但运行时没有向 Agent 强制声明该协议；当标记缺失时，`extractStory` 又把完整模型响应作为 `finalStory`。因此中文说明、Markdown 标题、加粗星号、表格和变更记录全部进入最终结果。

## 目标

- `tb_story_run.final_story` 只保存可直接交付给学生阅读的英文故事。
- 故事允许分成多个场景，每个场景保留纯文本标题，例如 `Scene 1: Blue Bag and Red Juice`。
- 最终故事不得包含中文说明、目标词清单、评分信息、变更记录、Markdown 标题符号、加粗星号、表格或代码围栏。
- `tb_story_run_step.output_text` 继续保存每个 Agent 的完整原始输出，保证运行记录可审计。
- 用户仍可在工作台优化 Agent Prompt；运行时格式协议不作为可编辑创作 Prompt 的一部分。

## 方案

### 运行时输出协议

`StoryRunExecutionService` 在调用 `story-writer` 和 `targeted-reviser` 时，向其数据库 Prompt 追加不可编辑的运行时协议：

```text
STORY_TEXT_BEGIN
Scene 1: Plain English Title

Plain English story paragraphs only.
STORY_TEXT_END
```

协议明确禁止标记外内容，并禁止在故事块内使用 Markdown、中文说明、清单、评分或变更记录。该约束只负责机器输出格式，不替代用户配置的角色、创意、年级和质量要求。

Provider 兼容边界：模型若仍在唯一故事块之外追加清单或修订说明，这些内容只作为原始响应保留在步骤详情，不进入审核链或最终结果，也不单独导致批次失败。故事块缺失、边界重复/歧义或故事块内部不纯净仍按格式错误处理。

### 提取与校验

执行器只接受同时包含唯一开始标记和结束标记、且顺序正确的故事块。提取后执行以下检查：

1. 正文非空。
2. 不包含协议边界标记。
3. 不包含 Markdown 标题、加粗星号、表格行或代码围栏。
4. 不包含明显的中文说明、目标词清单、评分或变更记录章节。

校验失败时抛出明确的“故事输出格式错误”，本次 Agent 步骤记为失败，运行批次进入 `FAILED`；不得回退为保存完整模型响应。这样即使 Provider 不遵守协议，也不会再次把审计报告冒充最终故事。

### 数据流

```text
用户 Prompt + 运行时纯故事协议
  -> story-writer / targeted-reviser
  -> 完整原始响应保存到 StoryRunStep.outputText
  -> 严格提取 STORY_TEXT_BEGIN / STORY_TEXT_END
  -> 校验纯英文纯文本故事
  -> 保存到 StoryRun.finalStory
  -> StoryRunHistory 底部直接展示
```

审核员、评分员和决策人的输出协议不变。后续审核接收提取后的纯故事正文，而不是作家响应中的附加说明。

## 兼容边界

- 不修改历史运行记录；已有批次仍保留当时的原始最终结果。
- 新运行从部署完成后开始执行严格协议。
- 不新增 Agent、数据库表、接口或额外模型调用，不增加正常流程的 Token 调用次数。
- 达到 Token 预算时仍保存最后一次已经成功提取并校验的纯故事。

## 测试与验收

- 单元测试先证明旧实现会把无边界标记的分析报告保存为最终故事。
- 验证作家和定向修订两条路径都追加运行时协议。
- 验证合法多场景纯文本被正确提取并保存。
- 验证缺少标记、空故事、Markdown、中文说明和清单输出会失败，且不会污染 `finalStory`。
- 运行后端全量测试、前端全量测试和前端生产构建。
- 通过 Context Router 部署后，用三年级上册单词创建一个新批次，确认底部最终结果只有英文场景标题和英文故事正文。
