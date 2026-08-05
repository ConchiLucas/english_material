---
title: 英语材料数据源目录
summary: 区分应用配置库与用户配置的外部材料数据库。
---

# 英语材料数据源目录

## 应用配置库

- 默认逻辑库名：`english_material`。
- 用途：保存数据库连接配置、AI Provider 配置和本地 CLI 配置。
- 访问方式：Spring Data JPA。
- 连接值由运行环境注入，文档不保存凭据。

## 外部材料库

- 由用户在数据库配置页面维护连接。
- 当前材料浏览读取 `word_clean`、`word_clean_sentence`、`word_clean_best_sentence` 和 `word_clean_tts`。
- SQL 使用参数绑定，并由服务端限制排序字段和分页。
- 该链路只读，不承担外部库 Schema 管理或数据迁移。
