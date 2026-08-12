# Agent 工作台彻底移除设计

## 目标

从英语材料工作空间彻底移除现有 Agent 工作台，为后续重新设计提供无历史实现约束的干净基线。删除范围覆盖前端、后端、测试、当前事实文档和本项目配置库表；保留数据库配置、AI 配置、本地 CLI 配置、去重单词查询及通用前端测试基础设施。

## 删除策略

采用“Git 历史辅助核对、当前树精确删除”的方式，不整体回退历史提交。

初始 Agent 提交 `0caf31b` 同时包含单词查询、AI/CLI 配置、部署入口和其他文档，整体回退会破坏保留功能。后续 Agent 布局提交只改变页面形态，单独回退会恢复旧工作台，也无法满足彻底清空要求。因此，实施时以当前依赖关系为准删除文件和引用，并用 Git 历史检查是否遗漏 Agent 专属内容。

## 前端范围

删除：

- `web-react/src/AgentWorkspacePage.tsx`
- `web-react/src/AgentWorkspacePage.test.tsx`
- `App.tsx` 中 Agent 工作台的图标导入、一级导航项、`WorkspaceSection` 分支、页面渲染和专属布局类
- `api.ts` 中 `AgentCategory`、`AgentDefinition`、`AgentTestResult` 以及 `/agents*` 请求
- `styles.css` 中全部 Agent 工作台专属样式和响应式规则

保留 Vitest、Testing Library、jsdom、`vitest.config.ts` 和 `src/test/setup.ts`。这些是通用测试能力，不属于 Agent 业务实现，可供重新设计和其他前端页面使用。

删除后顶栏只保留“配置管理”和“去重单词表”。

## 后端范围

删除以下 Agent 专属类及测试：

- `AgentController`
- `AgentService`
- `AgentSchemaValidator`
- `AgentCatalogInitializer`
- `AgentDefinitionRequest`
- `AgentTestRequest`
- `AgentTestResult`
- `AgentDefinition`
- `AgentTestRun`
- `AgentDefinitionRepository`
- `AgentTestRunRepository`
- `AgentSchemaValidatorTest`

保留 `AiConfigService`、`AiTextGenerationService`、`LocalCliGenerationService` 及其通用测试，因为 AI Provider 和本地 CLI 配置仍是独立功能。若其中仅有注释或测试文案使用“Agent”作为泛称，只在确认不属于工作台契约时改为中性“生成任务”措辞，不删除服务能力。

删除后 `/api/agents`、`/api/agents/{id}`、`/api/agents/{id}/test` 和 `/api/agents/runs` 均不再注册。

## 数据库销毁

目标仅限本项目应用配置库 `english_material`，不连接、不修改用户配置的外部英语材料数据源。

删除顺序：

1. 先移除并验证所有 JPA 实体、Repository 和初始化器引用，确保应用重启不会重新创建 Agent 表。
2. 在应用配置库确认两张目标表存在及其所属 schema。
3. 执行精确 DDL：
   - `DROP TABLE IF EXISTS tb_agent_test_run`
   - `DROP TABLE IF EXISTS tb_agent_definition`
4. 再次查询数据库目录，确认两张表均不存在。

表中现有 Agent 定义和运行记录全部永久删除，不做备份或迁移。不得使用模糊表名、通配目标或级联删除其他对象。

## 文档范围

删除当前 Agent 工作台专属文档：

- `docs/chains/agent-workbench.md`
- `docs/superpowers/specs/2026-08-09-agent-list-detail-design.md`
- `docs/superpowers/plans/2026-08-09-agent-list-detail.md`

更新以下当前事实文档，移除 Agent 工作台、接口、表和链路入口：

- `DESIGN.md`
- `docs/backend/java_server/AGENTS.md`
- `docs/frontend/web_react/AGENTS.md`
- `docs/chains/README.md`
- 其他经全文检索确认包含现行 Agent 工作台事实的文档

Git 提交历史不可改写，因此旧提交消息和历史内容仍可通过 Git 查看；“彻底移除”指当前工作树、当前文档和当前数据库不再保留该实现。

## 执行顺序与安全性

1. 先增加能证明导航和接口被移除的回归检查。
2. 删除前端与后端实现及引用。
3. 更新当前事实文档。
4. 运行源码全文检索、后端测试/构建、前端测试/构建。
5. 通过 Context Router 提交全部代码变化并等待终态。
6. 确认部署后的应用不再依赖 Agent 实体后，删除本项目配置库中的两张表。
7. 验证页面、接口和数据库最终状态。

数据库 DDL 放在代码和部署验证之后，避免旧后端仍在线时因表先被删除而发生运行错误。

## 验收标准

- 顶栏不再出现“Agent 工作台”。
- 当前源码中不存在 Agent 工作台页面、Controller、Service、实体、Repository、DTO、初始化器或专属测试。
- `/api/agents*` 请求返回未映射状态。
- `tb_agent_test_run` 与 `tb_agent_definition` 在本项目配置库中不存在。
- AI 配置、本地 CLI 配置、数据库配置和去重单词功能仍可用。
- 后端完整测试与构建通过。
- 前端测试与生产构建通过。
- 当前事实文档不再把 Agent 工作台描述为现有能力。
