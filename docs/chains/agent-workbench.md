---
title: Agent 工作台链路
summary: 说明 Agent 定义如何引用 AI Provider、执行在线测试并保存结构校验和评分记录。
---

# Agent 工作台链路

## 调用结构

```text
React AgentWorkspacePage
  -> /api/agents
  -> Spring Boot AgentController / AgentService
  -> english_material.tb_agent_definition

React 在线测试
  -> /api/agents/{id}/test
  -> 输入 JSON Schema 校验
  -> tb_ai_config.local_cli_config 中当前默认本地 CLI（初始为 Codex CLI）
  -> Java ProcessBuilder 以标准输入传递 Prompt，并以只读沙箱运行 Codex CLI
  -> 输出 JSON Schema 校验与独立评分调用
  -> english_material.tb_agent_test_run
```

## 数据边界

- Agent 定义、Prompt、Schema、硬规则、评分量表和测试记录只写入本项目 `english_material` 配置库。
- Agent 不独立选择执行器，运行时统一读取当前默认本地 CLI；切换默认 CLI 后无需逐个修改 Agent。
- 本地 CLI 凭据不进入业务表、接口响应、源码或镜像；容器运行时只挂载本机 Codex 配置目录。
- 当前在线测试只调用默认本地 CLI，不连接或写入外部材料数据源。
- 当前不实现 Agent 启用/停用、版本管理、发布、回滚或完整工作流执行。

## 质量处理

- 输入和输出使用轻量 JSON Schema 检查 `type`、`required`、`properties` 和数组项目。
- 输出结构失败时按 Agent 的 `retryLimit` 重试，最多 3 次。
- 结构通过后，在独立上下文中按 Agent 评分量表生成总分、维度分和问题清单。
- 每次运行保留状态、耗时、输出、校验结果、评分和错误摘要，前端最近显示 100 条。
