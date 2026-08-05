---
title: 英语材料系统总览
summary: 说明 Java 后端、React 前端、配置库和外部材料库的职责边界。
---

# 英语材料系统总览

## 工作空间组成

| Project | 类型 | 相对路径 | 主要职责 |
| --- | --- | --- | --- |
| 英语材料配置后端 | backend | `.` | 配置持久化、连接测试、材料只读查询 |
| 英语材料管理前端 | frontend | `web-react` | 配置管理和去重单词浏览 |

## 调用结构

```text
浏览器 -> React/Nginx -> Spring Boot -> english_material 配置库
                                  -> 已配置外部材料库（只读）
```

当前源码没有常驻 Python Worker。`deploy/backend/python_worker` 和部分旧文档属于历史设计，不是可运行 Project。
