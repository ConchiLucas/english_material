---
title: 英语材料系统总览
summary: 说明 Java 后端、React 前端、配置库和外部材料库的职责边界。
---

# 英语材料系统总览

## 工作空间组成

| Project | 类型 | 相对路径 | 主要职责 |
| --- | --- | --- | --- |
| 英语材料配置后端 | backend | `.` | 配置持久化、故事 Agent 流程配置、连接测试、材料只读查询 |
| 英语材料管理前端 | frontend | `web-react` | 配置管理、故事 Agent 工作台和去重单词浏览 |

## 调用结构

```text
浏览器 -> React/Nginx -> Spring Boot -> english_material 配置库
                                  -> 已配置外部材料库（只读）
```

故事 Agent 配置链路为：

```text
React Agent 工作台 -> /api/story-agents/* -> StoryAgentService
                                         -> 本地 tb_story_agent_config
                                         -> 本地 tb_story_agent_prompt_version
                                         -> 本地 tb_story_flow_config
                                         -> 读取现有 tb_ai_config 中的 Provider ID
```

本地配置库允许通过后端写入配置；故事 Agent 表只保存 Provider ID，不复制 Provider 详情，也不会向外部材料库或 `word_clean` 相关表写入。当前源码没有故事执行引擎、任务队列或常驻 Python Worker；`deploy/backend/python_worker` 和部分旧文档属于历史设计，不是可运行 Project。
