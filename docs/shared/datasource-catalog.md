---
title: 英语材料数据源目录
summary: 区分应用配置库与用户配置的外部材料数据库。
---

# 英语材料数据源目录

## 应用配置库

- 默认逻辑库名：`english_material`。
- 用途：保存数据库连接、AI Provider、本地 CLI、故事 Agent 配置，以及故事运行批次和逐 Agent 调用记录。
- 访问方式：Spring Data JPA。
- 连接值由运行环境注入，文档不保存凭据。

## 外部材料库

- 由用户在数据库配置页面维护连接。
- 当前材料浏览读取 `word_clean`、`word_clean_sentence`、`word_clean_best_sentence` 和 `word_clean_tts`。
- 故事运行的随机单词预览读取 `word_library` 和 `word`；只允许选择启用词库和可用单词，数量限制为 1—50。
- SQL 使用参数绑定，并由服务端限制排序字段和分页。
- 正式运行只保存单词和含义快照，后续模型执行不持续依赖外部材料库。
- 该链路只读，不承担外部库 Schema 管理或数据迁移。
