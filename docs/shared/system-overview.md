---
title: 英语材料系统总览
summary: 说明 Java 后端、React 前端、配置库和外部材料库的职责边界。
---

# 英语材料系统总览

## 工作空间组成

| Project | 类型 | 相对路径 | 主要职责 |
| --- | --- | --- | --- |
| 英语材料配置后端 | backend | `.` | 配置持久化、故事/图片 Agent 流程与进程内执行、图片资产服务、连接测试、材料只读查询 |
| 英语材料管理前端 | frontend | `web-react` | 配置管理、故事与图片工作台、运行审计和去重单词浏览 |

## 调用结构

```text
浏览器 -> React/Nginx -> Spring Boot -> english_material 配置与运行库
                                  -> 图片文件根目录/容器命名卷
                                  -> 已配置外部材料库（只读）
                                  -> 文本/图片 Provider
```

故事 Agent 配置链路为：

```text
React Agent 工作台 -> /api/story-agents/* -> StoryAgentService
                                         -> 本地 tb_story_agent_config
                                         -> 本地 tb_story_agent_prompt_version
                                         -> 本地 tb_story_flow_config
                                         -> 读取现有 tb_ai_config 中的 Provider ID
```

图片故事链路为：

```text
React 图片工作台 -> /api/image-agents/*        -> 9 个文本 Agent/版本与固定流程配置
                 -> /api/image-style-presets* -> 画风预设
                 -> /api/image-runs*          -> 故事快照与进程内异步执行
                                                -> OpenAI Images-compatible Provider
                                                -> 私有 MinIO Bucket 图片对象
                 -> /api/image-assets/{id}/content -> 按元数据路径与 SHA-256 受控读取
```

本地配置库允许通过后端写入配置与运行记录；故事/图片 Agent 表只保存 Provider ID，不复制 Provider 详情或密钥。图片批次另保存故事、单词、年级、画风、流程和安全 Provider/Agent 快照，图片字节只写入文件根目录。外部材料库与 `word_clean` 相关表始终只读。

故事和图片运行都由 Java 进程内有界线程池执行；当前源码没有常驻 Python Worker 或分布式任务队列。`deploy/backend/python_worker` 和部分旧文档属于历史设计，不是可运行 Project。
