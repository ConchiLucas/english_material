# Agent 工作台彻底移除 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从当前代码、运行服务、事实文档和本项目配置库中彻底移除旧 Agent 工作台，同时保留其他配置与单词查询能力。

**Architecture:** 使用 Git 历史作为定位依据，在当前树中精确删除 Agent 专属文件和共享文件内的引用。先通过通用 App 导航测试约束保留页面，再删除前后端实现并部署；最后只对应用配置库的两个已确认表执行精确 `DROP TABLE`，避免旧服务继续访问已删除表。

**Tech Stack:** React 18、TypeScript、Ant Design、Vitest、Spring Boot 3、Spring Data JPA、PostgreSQL、Context Router Runtime Runner

---

### Task 1: 建立保留导航的回归测试

**Files:**
- Create: `web-react/src/App.test.tsx`
- Modify: `web-react/src/App.tsx`
- Test: `web-react/src/App.test.tsx`

- [ ] **Step 1: 写出当前会失败的顶栏导航测试**

用 `vi.mock('./api')` 为 `getConnections`、`getAIConfig` 和 `getLocalCliConfig` 返回空配置，渲染 `App`，断言主导航只包含“配置管理”和“去重单词表”：

```tsx
expect(await screen.findByRole('menuitem', { name: /配置管理/ })).toBeInTheDocument();
expect(screen.getByRole('menuitem', { name: /去重单词表/ })).toBeInTheDocument();
expect(screen.getAllByRole('menuitem')).toHaveLength(2);
```

- [ ] **Step 2: 运行测试并确认旧导航使其失败**

Run: `cd web-react && npm test -- App.test.tsx`

Expected: FAIL，主导航实际有 3 个 menuitem。

- [ ] **Step 3: 从 App 删除工作台入口和渲染分支**

在 `App.tsx` 中删除 `ApartmentOutlined`、`AgentWorkspacePage`、`'agents'` section、Agent 导航项和 Agent 专属 className 分支，使内容只在配置页与去重单词页之间切换。

- [ ] **Step 4: 运行导航测试并确认通过**

Run: `cd web-react && npm test -- App.test.tsx`

Expected: PASS，主导航固定为 2 项。

- [ ] **Step 5: 提交导航删除**

```bash
git add web-react/src/App.tsx web-react/src/App.test.tsx
git commit -m "test: remove agent workbench navigation"
```

### Task 2: 删除前端 Agent 实现

**Files:**
- Delete: `web-react/src/AgentWorkspacePage.tsx`
- Delete: `web-react/src/AgentWorkspacePage.test.tsx`
- Modify: `web-react/src/api.ts`
- Modify: `web-react/src/styles.css`
- Preserve: `web-react/package.json`
- Preserve: `web-react/package-lock.json`
- Preserve: `web-react/vitest.config.ts`
- Preserve: `web-react/src/test/setup.ts`

- [ ] **Step 1: 删除 Agent 页面及专属测试文件**

删除两个文件，不保留旧布局组件、表单、测试输入或运行记录展示。

- [ ] **Step 2: 删除前端 Agent API 契约**

从 `api.ts` 删除 `AgentCategory`、`AgentDefinition`、`AgentTestResult` 和以下请求：

```text
getAgents
createAgent
updateAgent
testAgent
getAgentRuns
```

- [ ] **Step 3: 删除全部 `.agent-*` 专属样式**

从 `styles.css` 的默认、平板和手机媒体查询中删除 Agent 工作台选择器；不改变数据库配置或单词表选择器。

- [ ] **Step 4: 验证前端不再引用 Agent 工作台**

Run: `rg -n "AgentWorkspace|/agents|agent-workspace|agent-detail|agent-list|agent-test" web-react/src`

Expected: 无输出。

- [ ] **Step 5: 运行前端测试与构建**

Run: `cd web-react && npm test && npm run build`

Expected: App 测试通过，TypeScript 和 Vite 构建成功。

- [ ] **Step 6: 提交前端删除**

```bash
git add -A web-react/src
git commit -m "refactor: remove agent workbench frontend"
```

### Task 3: 删除后端 Agent 实现

**Files:**
- Delete: `src/main/java/com/aitaskcenter/config/AgentCatalogInitializer.java`
- Delete: `src/main/java/com/aitaskcenter/controller/AgentController.java`
- Delete: `src/main/java/com/aitaskcenter/dto/AgentDefinitionRequest.java`
- Delete: `src/main/java/com/aitaskcenter/dto/AgentTestRequest.java`
- Delete: `src/main/java/com/aitaskcenter/dto/AgentTestResult.java`
- Delete: `src/main/java/com/aitaskcenter/model/AgentDefinition.java`
- Delete: `src/main/java/com/aitaskcenter/model/AgentTestRun.java`
- Delete: `src/main/java/com/aitaskcenter/repository/AgentDefinitionRepository.java`
- Delete: `src/main/java/com/aitaskcenter/repository/AgentTestRunRepository.java`
- Delete: `src/main/java/com/aitaskcenter/service/AgentSchemaValidator.java`
- Delete: `src/main/java/com/aitaskcenter/service/AgentService.java`
- Delete: `src/test/java/com/aitaskcenter/service/AgentSchemaValidatorTest.java`

- [ ] **Step 1: 删除 Agent Controller、业务服务和初始化器**

移除所有 `/api/agents*` 映射、在线测试/评分逻辑和启动种子数据。

- [ ] **Step 2: 删除 DTO、实体和 Repository**

移除两个 JPA 表映射以及全部 Agent 请求/响应模型，保证 `ddl-auto=update` 重启后不再管理或创建这两张表。

- [ ] **Step 3: 删除专属 Schema 校验器和测试**

保留 AI Provider 与本地 CLI 服务；不删除 `AiTextGenerationService` 或 `LocalCliGenerationService`。

- [ ] **Step 4: 检查后端残留引用**

Run: `rg -n "AgentController|AgentService|AgentSchemaValidator|AgentDefinition|AgentTestRun|/api/agents|tb_agent_" src/main src/test`

Expected: 无输出。

- [ ] **Step 5: 运行后端测试与打包**

Run: `mvn -B -ntp test`

Expected: BUILD SUCCESS，0 failures。

Run: `mvn -B -ntp -DskipTests package`

Expected: BUILD SUCCESS。

- [ ] **Step 6: 提交后端删除**

```bash
git add -A src/main src/test
git commit -m "refactor: remove agent workbench backend"
```

### Task 4: 清理当前事实文档和历史布局文档

**Files:**
- Delete: `docs/chains/agent-workbench.md`
- Delete: `docs/superpowers/specs/2026-08-09-agent-list-detail-design.md`
- Delete: `docs/superpowers/plans/2026-08-09-agent-list-detail.md`
- Modify: `docs/chains/README.md`
- Modify: `docs/backend/java_server/AGENTS.md`
- Modify: `docs/frontend/web_react/AGENTS.md`
- Modify: `DESIGN.md`

- [ ] **Step 1: 删除专属链路、旧布局规格和旧实施计划**

只保留本轮“彻底移除”规格与实施计划，作为删除决策和数据库销毁审计依据。

- [ ] **Step 2: 更新后端和前端事实文档**

移除 Agent Controller、接口、JPA 表、页面、导航和请求描述；保留配置管理与去重单词事实。

- [ ] **Step 3: 更新 DESIGN.md**

删除 Agent 工作台章节、导航项、响应式布局、限制和文件清单，并将项目描述收敛为配置管理与英语材料浏览。

- [ ] **Step 4: 检查当前事实文档**

Run: `rg -n "Agent 工作台|AgentWorkspacePage|/api/agents|tb_agent_definition|tb_agent_test_run" DESIGN.md docs --glob '!docs/superpowers/specs/2026-08-12-remove-agent-workbench-design.md' --glob '!docs/superpowers/plans/2026-08-12-remove-agent-workbench.md'`

Expected: 无输出。

- [ ] **Step 5: 提交文档清理**

```bash
git add -A DESIGN.md docs
git commit -m "docs: remove agent workbench documentation"
```

### Task 5: 部署不含 Agent 实体的应用

**Files:**
- No source changes
- Use: `deploy/context-router/`

- [ ] **Step 1: 执行最终代码验证**

Run: `git diff --check && mvn -B -ntp test && cd web-react && npm test && npm run build`

Expected: 所有命令退出码 0。

- [ ] **Step 2: 提交 Workspace 变化**

使用任务 `575` 调用 `apply_workspace_changes`，`changed_files` 包含本轮所有实际删除和修改路径。

- [ ] **Step 3: 轮询部署操作**

使用 `get_workspace_operation(operation_id)` 直到 `succeeded`、`failed`、`cancelled` 或 `interrupted`。只有 `succeeded` 才继续删表；失败时先修复部署，不提前执行 DDL。

- [ ] **Step 4: 验证已部署接口**

Run: `curl -i http://127.0.0.1:18744/api/agents`

Expected: HTTP 404 或后端统一的未映射响应，不能返回 Agent 列表。

### Task 6: 精确删除应用配置库 Agent 表

**Files:**
- No repository file changes
- Database: local application configuration database only

- [ ] **Step 1: 从本机受控配置解析应用库连接**

只在进程环境中加载根 `.env.local` 的 `TASK_CENTER_DB_URL`、`TASK_CENTER_DB_USER`、`TASK_CENTER_DB_PASSWORD`，不得打印值或写入文件。

- [ ] **Step 2: 只读确认目标表**

执行参数化目录查询，确认当前数据库和 schema 中精确存在：

```text
tb_agent_test_run
tb_agent_definition
```

不得选择名称相似的其他表。

- [ ] **Step 3: 执行已授权的精确 DDL**

在单一事务中执行：

```sql
DROP TABLE IF EXISTS tb_agent_test_run;
DROP TABLE IF EXISTS tb_agent_definition;
```

不使用 `CASCADE`，若存在未知依赖则停止并报告，不扩大删除范围。

- [ ] **Step 4: 验证表已不存在**

重复目录查询。

Expected: 两个精确表名均返回 0 行。

### Task 7: 最终验收

**Files:**
- No source changes

- [ ] **Step 1: 全文检查残留**

Run: `rg -n "AgentWorkspacePage|AgentController|AgentService|AgentSchemaValidator|AgentDefinition|AgentTestRun|/api/agents|tb_agent_definition|tb_agent_test_run" . --glob '!target/**' --glob '!web-react/node_modules/**' --glob '!web-react/dist/**' --glob '!.git/**' --glob '!docs/superpowers/specs/2026-08-12-remove-agent-workbench-design.md' --glob '!docs/superpowers/plans/2026-08-12-remove-agent-workbench.md'`

Expected: 无输出。

- [ ] **Step 2: 最终测试和构建**

Run: `mvn -B -ntp test`

Expected: BUILD SUCCESS。

Run: `cd web-react && npm test && npm run build`

Expected: 测试与生产构建成功。

- [ ] **Step 3: 浏览器验收保留功能**

打开 `http://127.0.0.1:19638`，确认顶栏只有配置管理和去重单词表；数据库、AI、本地 CLI 配置页可进入；去重单词表可加载；页面不存在 Agent 工作台入口。

- [ ] **Step 4: 检查工作区与提交**

Run: `git status --short && git log -8 --oneline`

Expected: 工作区干净，删除相关提交存在。

