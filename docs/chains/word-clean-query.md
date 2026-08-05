---
title: 英语材料去重单词查询链路
summary: 说明前端如何选择数据源并经 Java 参数化只读查询外部材料库。
---

# 英语材料去重单词查询链路

1. 用户先在数据库配置中保存并测试外部材料数据库连接。
2. `WordCleanPage.tsx` 选择连接 ID，并请求 `/api/word-clean/facets` 和 `/api/word-clean`。
3. `WordCleanController` 把连接 ID、筛选、排序和分页参数交给 `WordCleanService`。
4. `ConnectionConfigService` 从本地配置库读取连接记录并打开 JDBC 连接。
5. `WordCleanService` 检查必要表，使用参数化 SELECT 查询单词、难度、最佳例句和音频状态。
6. 用户打开单词详情时，前端请求 `/api/word-clean/{id}/sentences` 查询候选例句。
7. 服务关闭外部 JDBC 连接并返回统一 `ApiResponse`；全链路不执行外部库写入或 DDL。
