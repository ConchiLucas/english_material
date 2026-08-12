# 英文故事 Agent 流转工作台实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在原“Agent 工作台”一级菜单位置实现固定可点击的英文故事 Agent 流程配置页，支持 Prompt 中心详情、模型参数、独立版本和质量预算持久化。

**Architecture:** Spring Boot 以固定目录定义 12 个 Agent 和 5 个程序/人工节点，JPA 只持久化可编辑配置、Prompt 快照和单例预算，不把拓扑做成任意图。React 使用固定四段式流程画布和右侧常驻详情区；每次保存单个 Agent，切换节点或离开页面前保护未保存内容。第一版不调用模型或执行故事流程。

**Tech Stack:** Java 17、Spring Boot 3.3、Spring Data JPA、PostgreSQL、React 18、TypeScript、Ant Design、Axios、JUnit 5、Mockito、Vitest、Testing Library。

---

## 文件结构

### 后端

- `src/main/java/com/aitaskcenter/config/StoryAgentCatalog.java`：固定节点、阶段、变量、上下游和默认 Prompt 的唯一事实源。
- `src/main/java/com/aitaskcenter/config/StoryAgentInitializer.java`：启动时只补齐缺失配置。
- `src/main/java/com/aitaskcenter/model/StoryAgentConfig.java`：12 个 Agent 的当前配置。
- `src/main/java/com/aitaskcenter/model/StoryAgentPromptVersion.java`：不可变 Prompt/模型参数快照。
- `src/main/java/com/aitaskcenter/model/StoryFlowConfig.java`：默认质量预算。
- `src/main/java/com/aitaskcenter/repository/StoryAgentConfigRepository.java`：按固定 Key 读取当前配置。
- `src/main/java/com/aitaskcenter/repository/StoryAgentPromptVersionRepository.java`：按 Agent 和版本读取快照。
- `src/main/java/com/aitaskcenter/repository/StoryFlowConfigRepository.java`：读取单例预算。
- `src/main/java/com/aitaskcenter/dto/StoryAgentDtos.java`：流转页面请求/响应 DTO 集合。
- `src/main/java/com/aitaskcenter/service/StoryAgentService.java`：初始化、读取、校验、版本化保存、恢复和预算保存。
- `src/main/java/com/aitaskcenter/controller/StoryAgentController.java`：`/api/story-agents` HTTP 入口。
- `src/test/java/com/aitaskcenter/config/StoryAgentCatalogTest.java`：固定目录与拓扑契约。
- `src/test/java/com/aitaskcenter/service/StoryAgentServiceTest.java`：版本、校验、恢复、预算和初始化行为。

### 前端

- `web-react/src/story-flow-types.ts`：流程、节点、版本和预算 TypeScript 类型。
- `web-react/src/StoryAgentFlowPage.tsx`：流程画布、详情编辑、历史版本和预算弹窗。
- `web-react/src/StoryAgentFlowPage.test.tsx`：节点切换、Prompt 显示、保存和程序节点测试。
- `web-react/src/App.tsx`：恢复菜单、渲染工作台并保护离开时的未保存修改。
- `web-react/src/App.test.tsx`：主导航与进入工作台测试。
- `web-react/src/api.ts`：Story Agent API 封装。
- `web-react/src/styles.css`：流程画布、节点、右侧详情和响应式样式。

### 文档

- `docs/backend/java_server/AGENTS.md`：新增配置表和接口。
- `docs/frontend/web_react/AGENTS.md`：新增页面范围。
- `docs/shared/system-overview.md`：加入 Story Agent 配置链路。
- `docs/chains/README.md`：登记新链路。
- `docs/chains/story-agent-flow-config.md`：描述前后端到本地配置库的完整链路。

---

### Task 1: 固定 Agent 目录和持久化模型

**Files:**
- Create: `src/main/java/com/aitaskcenter/config/StoryAgentCatalog.java`
- Create: `src/main/java/com/aitaskcenter/model/StoryAgentConfig.java`
- Create: `src/main/java/com/aitaskcenter/model/StoryAgentPromptVersion.java`
- Create: `src/main/java/com/aitaskcenter/model/StoryFlowConfig.java`
- Create: `src/main/java/com/aitaskcenter/repository/StoryAgentConfigRepository.java`
- Create: `src/main/java/com/aitaskcenter/repository/StoryAgentPromptVersionRepository.java`
- Create: `src/main/java/com/aitaskcenter/repository/StoryFlowConfigRepository.java`
- Test: `src/test/java/com/aitaskcenter/config/StoryAgentCatalogTest.java`

- [ ] **Step 1: 写固定目录失败测试**

```java
class StoryAgentCatalogTest {
    @Test
    void exposesTwelveUniqueEditableAgentsAndFiveReadOnlyNodes() {
        List<StoryAgentCatalog.NodeDefinition> nodes = StoryAgentCatalog.nodes();
        assertEquals(12, nodes.stream().filter(StoryAgentCatalog.NodeDefinition::editable).count());
        assertEquals(5, nodes.stream().filter(node -> !node.editable()).count());
        assertEquals(nodes.size(), nodes.stream().map(StoryAgentCatalog.NodeDefinition::key).distinct().count());
        assertEquals(List.of(
                "vocabulary-planner", "pitch-humor", "pitch-adventure", "pitch-wonder",
                "story-director", "story-writer", "review-fun", "review-language",
                "review-continuity", "story-scorer", "quality-decider", "targeted-reviser"),
                StoryAgentCatalog.agents().stream().map(StoryAgentCatalog.NodeDefinition::key).toList());
    }

    @Test
    void exposesFourOrderedStagesAndDecisionReturnTargets() {
        assertEquals(List.of("planning", "writing", "quality", "delivery"),
                StoryAgentCatalog.stages().stream().map(StoryAgentCatalog.StageDefinition::key).toList());
        assertEquals(List.of("targeted-reviser", "story-writer", "story-director",
                        "pitch-humor", "pitch-adventure", "pitch-wonder", "vocabulary-planner"),
                StoryAgentCatalog.require("quality-decider").downstream());
    }
}
```

- [ ] **Step 2: 运行测试确认红灯**

Run: `mvn -B -ntp -Dtest=StoryAgentCatalogTest test`

Expected: FAIL，提示 `StoryAgentCatalog` 不存在。

- [ ] **Step 3: 实现固定目录**

使用不可变 record：

```java
public final class StoryAgentCatalog {
    public record StageDefinition(String key, String name, String note, int order) {}
    public record NodeDefinition(
            String key, String name, String nodeKind, String roleType, String stageKey,
            int order, String parallelGroup, String description, List<String> variables,
            List<String> upstream, List<String> downstream, String defaultPrompt,
            String modelPreference, double defaultTemperature, boolean editable) {}

    private static final List<StageDefinition> STAGES = List.of(
            new StageDefinition("planning", "策划与创意", "目标词到三个匿名提案", 10),
            new StageDefinition("writing", "写作与候选", "同一主角、同一主线、逐场升级", 20),
            new StageDefinition("quality", "独立质量委员会", "审核、评分与决策完全分离", 30),
            new StageDefinition("delivery", "修订与交付", "通过后进入人工审核", 40));

    public static List<StageDefinition> stages() { return STAGES; }
    public static List<NodeDefinition> nodes() { return NODES; }
    public static List<NodeDefinition> agents() {
        return NODES.stream().filter(NodeDefinition::editable).toList();
    }
    public static NodeDefinition require(String key) {
        return NODES.stream().filter(node -> node.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Story Agent「" + key + "」不存在"));
    }
}
```

`NODES` 按规格定义以下 Key：`word-pack`、12 个 Agent Key、`hard-rule-check`、`candidate-snapshot`、`budget-controller`、`human-review`。12 个 Prompt 使用设计稿中已经确认的职责文本，明确“谁能写正文、谁只能诊断、谁只能评分、谁只能路由”。

- [ ] **Step 4: 实现三张 JPA 表和 Repository**

`StoryAgentConfig` 使用 `@Table(name = "tb_story_agent_config")`，字段为 `agentKey`（唯一）、`name`、`roleType`、`description`（text）、`systemPrompt`（text）、`aiProviderId`、`temperature`、`enabled`、`promptVersion`。

`StoryAgentPromptVersion` 使用唯一约束 `(agent_key, version)`，保存 `agentKey`、`version`、`systemPrompt`、`aiProviderId`、`temperature`、`enabled`。

`StoryFlowConfig` 使用唯一 `configKey`，保存七个预算字段，并提供：

```java
public static StoryFlowConfig defaults() {
    StoryFlowConfig config = new StoryFlowConfig();
    config.setConfigKey("default-story-flow");
    config.setMaxQualityRounds(3);
    config.setMaxLocalRevisions(2);
    config.setMaxWriterRewrites(1);
    config.setMaxDirectorReturns(1);
    config.setMaxPitchReturns(1);
    config.setMaxPlanReturns(1);
    config.setMaxTotalTokens(120000L);
    return config;
}
```

Repository 精确提供：

```java
Optional<StoryAgentConfig> findByAgentKey(String agentKey);
List<StoryAgentConfig> findAllByOrderByAgentKeyAsc();
List<StoryAgentPromptVersion> findByAgentKeyOrderByVersionDesc(String agentKey);
Optional<StoryAgentPromptVersion> findByAgentKeyAndVersion(String agentKey, int version);
Optional<StoryFlowConfig> findByConfigKey(String configKey);
```

- [ ] **Step 5: 运行目录测试**

Run: `mvn -B -ntp -Dtest=StoryAgentCatalogTest test`

Expected: 2 tests PASS。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/aitaskcenter/config/StoryAgentCatalog.java \
  src/main/java/com/aitaskcenter/model/StoryAgentConfig.java \
  src/main/java/com/aitaskcenter/model/StoryAgentPromptVersion.java \
  src/main/java/com/aitaskcenter/model/StoryFlowConfig.java \
  src/main/java/com/aitaskcenter/repository/StoryAgentConfigRepository.java \
  src/main/java/com/aitaskcenter/repository/StoryAgentPromptVersionRepository.java \
  src/main/java/com/aitaskcenter/repository/StoryFlowConfigRepository.java \
  src/test/java/com/aitaskcenter/config/StoryAgentCatalogTest.java
git commit -m "feat: define story agent flow catalog"
```

### Task 2: Agent 配置、版本和预算服务

**Files:**
- Create: `src/main/java/com/aitaskcenter/dto/StoryAgentDtos.java`
- Create: `src/main/java/com/aitaskcenter/service/StoryAgentService.java`
- Test: `src/test/java/com/aitaskcenter/service/StoryAgentServiceTest.java`

- [ ] **Step 1: 定义 DTO 契约**

在 `StoryAgentDtos` 中定义嵌套 record：

```java
public record AgentUpdateRequest(String systemPrompt, String aiProviderId,
        Double temperature, Boolean enabled, OffsetDateTime updatedAt) {}
public record BudgetUpdateRequest(Integer maxQualityRounds, Integer maxLocalRevisions,
        Integer maxWriterRewrites, Integer maxDirectorReturns, Integer maxPitchReturns,
        Integer maxPlanReturns, Long maxTotalTokens) {}
public record AgentView(String key, String name, String nodeKind, String roleType,
        String stageKey, int order, String parallelGroup, String description,
        List<String> variables, List<String> upstream, List<String> downstream,
        String systemPrompt, String aiProviderId, Double temperature, Boolean enabled,
        Integer promptVersion, OffsetDateTime updatedAt, boolean editable) {}
public record StageView(String key, String name, String note, int order, List<AgentView> nodes) {}
public record BudgetView(int maxQualityRounds, int maxLocalRevisions,
        int maxWriterRewrites, int maxDirectorReturns, int maxPitchReturns,
        int maxPlanReturns, long maxTotalTokens, OffsetDateTime updatedAt) {}
public record FlowView(List<StageView> stages, BudgetView budget) {}
public record PromptVersionView(int version, String systemPrompt, String aiProviderId,
        double temperature, boolean enabled, OffsetDateTime createdAt) {}
```

- [ ] **Step 2: 写服务失败测试**

使用 Mockito 覆盖以下行为：

```java
@Test void initializeOnlyCreatesMissingAgentsAndInitialVersions();
@Test void saveChangedPromptCreatesNextVersion();
@Test void saveIdenticalValuesDoesNotCreateVersion();
@Test void rejectsBlankPromptUnknownKeyAndNonTextProvider();
@Test void rejectsStaleUpdatedAt();
@Test void restoreCreatesNewLatestVersionWithoutChangingHistory();
@Test void validatesAndSavesBudgetBounds();
```

`saveChangedPromptCreatesNextVersion` 明确断言：

```java
when(configRepository.findByAgentKey("story-writer")).thenReturn(Optional.of(existingVersion(2)));
when(aiConfigService.getProviders()).thenReturn(textProviders("gemini-pro"));
StoryAgentDtos.AgentView saved = service.update("story-writer",
        new AgentUpdateRequest("新的作家提示词", "gemini-pro", 0.8, true, existing.getUpdatedAt()));
assertEquals(3, saved.promptVersion());
verify(versionRepository).save(argThat(version -> version.getVersion() == 3
        && version.getSystemPrompt().equals("新的作家提示词")));
```

- [ ] **Step 3: 运行测试确认红灯**

Run: `mvn -B -ntp -Dtest=StoryAgentServiceTest test`

Expected: FAIL，提示 `StoryAgentService` 不存在或行为未实现。

- [ ] **Step 4: 实现初始化和 Provider 选择**

`initializeDefaults()` 对固定 Agent 逐个 `findByAgentKey`；缺失时创建版本 1。Provider 偏好按配置的 `id + label + model` 小写匹配：`MEDIUM -> flash-medium`、`HIGH -> flash-high`、`PRO -> pro`，未匹配时使用 active 的已启用 `TEXT_GENERATION` Provider，再退回第一个有效 Provider。没有有效 Provider 时保存空 Provider ID，让页面展示“待选择”，但不覆盖 Prompt。

- [ ] **Step 5: 实现版本化保存和并发保护**

保存规则：

```java
StoryAgentCatalog.NodeDefinition definition = StoryAgentCatalog.require(agentKey);
if (!definition.editable()) throw new IllegalArgumentException("程序节点不可编辑");
String prompt = require(request.systemPrompt(), "System Prompt 不能为空");
double temperature = requireRange(request.temperature(), 0.0, 2.0, "Temperature 必须在 0 到 2 之间");
validateTextProvider(request.aiProviderId());
assertCurrentTimestamp(config, request.updatedAt());
boolean changed = !prompt.equals(config.getSystemPrompt())
        || !request.aiProviderId().equals(config.getAiProviderId())
        || Double.compare(temperature, config.getTemperature()) != 0
        || request.enabled() != config.isEnabled();
if (changed) {
    int nextVersion = config.getPromptVersion() + 1;
    apply(config, request, nextVersion);
    configRepository.save(config);
    versionRepository.save(snapshot(config));
}
```

时间戳按 `toInstant()` 比较。相同内容直接返回当前 View，不生成快照。恢复历史版本总是创建 `current + 1` 的新快照。

- [ ] **Step 6: 实现预算读写和 Flow 组装**

`getFlow()` 先确保默认数据存在，再按 `StoryAgentCatalog.stages()` 组装节点。程序节点的 Prompt/Provider/Temperature/版本为空，`editable=false`。预算校验：质量轮次 `1..20`，各回退次数 `0..20`，总 Token `1000..10_000_000`。

- [ ] **Step 7: 运行服务与完整后端测试**

Run: `mvn -B -ntp -Dtest=StoryAgentServiceTest test && mvn -B -ntp test`

Expected: 新服务测试全部 PASS；现有 5 个测试无回归。

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/aitaskcenter/dto/StoryAgentDtos.java \
  src/main/java/com/aitaskcenter/service/StoryAgentService.java \
  src/test/java/com/aitaskcenter/service/StoryAgentServiceTest.java
git commit -m "feat: persist story agent prompts and budgets"
```

### Task 3: 初始化器和 HTTP API

**Files:**
- Create: `src/main/java/com/aitaskcenter/config/StoryAgentInitializer.java`
- Create: `src/main/java/com/aitaskcenter/controller/StoryAgentController.java`
- Test: `src/test/java/com/aitaskcenter/controller/StoryAgentControllerTest.java`

- [ ] **Step 1: 写 Controller 失败测试**

使用 `MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())` 验证：

```java
mockMvc.perform(get("/api/story-agents/flow"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.stages[0].key").value("planning"));

mockMvc.perform(put("/api/story-agents/story-writer")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"systemPrompt":"prompt","aiProviderId":"gemini-pro","temperature":0.8,"enabled":true}"""))
        .andExpect(jsonPath("$.data.key").value("story-writer"));
```

并验证版本列表、恢复和预算保存分别调用正确服务方法。

- [ ] **Step 2: 运行测试确认红灯**

Run: `mvn -B -ntp -Dtest=StoryAgentControllerTest test`

Expected: FAIL，提示 Controller 不存在。

- [ ] **Step 3: 实现 Controller 和初始化器**

```java
@RestController
@RequestMapping("/api/story-agents")
public class StoryAgentController {
    @GetMapping("/flow")
    public ApiResponse<FlowView> flow() { return ApiResponse.ok(service.getFlow()); }
    @PutMapping("/{agentKey}")
    public ApiResponse<AgentView> update(@PathVariable String agentKey,
            @RequestBody AgentUpdateRequest request) {
        return ApiResponse.ok(service.update(agentKey, request), "Prompt 已保存");
    }
    @GetMapping("/{agentKey}/versions")
    public ApiResponse<List<PromptVersionView>> versions(@PathVariable String agentKey) {
        return ApiResponse.ok(service.versions(agentKey));
    }
    @PostMapping("/{agentKey}/versions/{version}/restore")
    public ApiResponse<AgentView> restore(@PathVariable String agentKey, @PathVariable int version) {
        return ApiResponse.ok(service.restore(agentKey, version), "Prompt 版本已恢复");
    }
    @PutMapping("/flow/config")
    public ApiResponse<BudgetView> updateBudget(@RequestBody BudgetUpdateRequest request) {
        return ApiResponse.ok(service.updateBudget(request), "质量预算已保存");
    }
}
```

`StoryAgentInitializer implements ApplicationRunner`，`run()` 只调用 `storyAgentService.initializeDefaults()`。

- [ ] **Step 4: 运行 Controller 和后端测试**

Run: `mvn -B -ntp -Dtest=StoryAgentControllerTest test && mvn -B -ntp test`

Expected: Controller 测试 PASS，全部后端测试 PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/aitaskcenter/config/StoryAgentInitializer.java \
  src/main/java/com/aitaskcenter/controller/StoryAgentController.java \
  src/test/java/com/aitaskcenter/controller/StoryAgentControllerTest.java
git commit -m "feat: expose story agent flow api"
```

### Task 4: 前端类型、API 和菜单入口

**Files:**
- Create: `web-react/src/story-flow-types.ts`
- Modify: `web-react/src/api.ts`
- Modify: `web-react/src/App.tsx`
- Modify: `web-react/src/App.test.tsx`

- [ ] **Step 1: 修改导航测试形成红灯**

```tsx
it('exposes the story Agent workbench from primary navigation', async () => {
  render(<AntApp><App /></AntApp>);
  const navigation = await screen.findByRole('menu', { name: '主导航' });
  expect(within(navigation).getByRole('menuitem', { name: /Agent 工作台/ })).toBeInTheDocument();
  expect(within(navigation).getAllByRole('menuitem')).toHaveLength(3);
});
```

Mock 新增的 `getStoryAgentFlow`，避免进入页面时发真实请求。

- [ ] **Step 2: 运行测试确认红灯**

Run: `npm --prefix web-react test -- App.test.tsx`

Expected: FAIL，找不到“Agent 工作台”，当前只有 2 个菜单项。

- [ ] **Step 3: 定义前端类型与 API**

`story-flow-types.ts` 精确对应后端 camelCase JSON：

```ts
export type StoryNodeKind = 'AGENT' | 'PROGRAM' | 'HUMAN';
export interface StoryAgentNode { key: string; name: string; nodeKind: StoryNodeKind; roleType: string;
  stageKey: string; order: number; parallelGroup: string; description: string; variables: string[];
  upstream: string[]; downstream: string[]; systemPrompt?: string; aiProviderId?: string;
  temperature?: number; enabled?: boolean; promptVersion?: number; updatedAt?: string; editable: boolean; }
export interface StoryFlowStage { key: string; name: string; note: string; order: number; nodes: StoryAgentNode[]; }
export interface StoryFlowBudget { maxQualityRounds: number; maxLocalRevisions: number;
  maxWriterRewrites: number; maxDirectorReturns: number; maxPitchReturns: number;
  maxPlanReturns: number; maxTotalTokens: number; updatedAt?: string; }
export interface StoryAgentFlow { stages: StoryFlowStage[]; budget: StoryFlowBudget; }
export interface StoryPromptVersion { version: number; systemPrompt: string; aiProviderId: string;
  temperature: number; enabled: boolean; createdAt: string; }
```

API：

```ts
export const getStoryAgentFlow = () => request.get<ApiResponse<StoryAgentFlow>>('/story-agents/flow').then(unwrap);
export const updateStoryAgent = (key: string, value: StoryAgentUpdate) =>
  request.put<ApiResponse<StoryAgentNode>>(`/story-agents/${key}`, value).then(unwrap);
export const getStoryAgentVersions = (key: string) =>
  request.get<ApiResponse<StoryPromptVersion[]>>(`/story-agents/${key}/versions`).then(unwrap);
export const restoreStoryAgentVersion = (key: string, version: number) =>
  request.post<ApiResponse<StoryAgentNode>>(`/story-agents/${key}/versions/${version}/restore`).then(unwrap);
export const updateStoryFlowBudget = (value: StoryFlowBudget) =>
  request.put<ApiResponse<StoryFlowBudget>>('/story-agents/flow/config', value).then(unwrap);
```

- [ ] **Step 4: 恢复菜单和页面渲染分支**

在 `App.tsx` 引入 `ApartmentOutlined` 和 `StoryAgentFlowPage`，把 `WorkspaceSection` 扩展为 `'config' | 'word-clean' | 'agents'`。第三个菜单项名称固定为“Agent 工作台”。`section === 'agents'` 时渲染 `<StoryAgentFlowPage providers={ai.providers} onDirtyChange={setAgentDirty} />`，不显示配置 Sider。

菜单切换函数在离开 `agents` 且 `agentDirty` 时使用现有 `modal.confirm`；确认后清除 dirty 并切换，取消则保持原页。

- [ ] **Step 5: 创建临时页面导出让导航测试转绿**

先创建最小 `StoryAgentFlowPage.tsx`：

```tsx
export default function StoryAgentFlowPage() {
  return <section aria-label="Agent 流程工作台">Agent 流程工作台</section>;
}
```

- [ ] **Step 6: 运行导航测试**

Run: `npm --prefix web-react test -- App.test.tsx`

Expected: PASS，3 个一级菜单项均存在。

- [ ] **Step 7: 提交**

```bash
git add web-react/src/story-flow-types.ts web-react/src/api.ts \
  web-react/src/App.tsx web-react/src/App.test.tsx web-react/src/StoryAgentFlowPage.tsx
git commit -m "feat: restore story agent workbench navigation"
```

### Task 5: 可点击流程画布和 Prompt 详情

**Files:**
- Modify: `web-react/src/StoryAgentFlowPage.tsx`
- Create: `web-react/src/StoryAgentFlowPage.test.tsx`
- Modify: `web-react/src/styles.css`

- [ ] **Step 1: 写交互失败测试**

构建含四阶段、12 Agent 和 5 程序/人工节点的 fixture。覆盖：

```tsx
it('renders four stages and switches prompt details when an Agent is clicked', async () => {
  renderPage();
  expect(await screen.findByRole('heading', { name: '策划与创意' })).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '独立质量委员会' })).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: /故事作家 Agent/ }));
  expect(screen.getByRole('heading', { name: '故事作家 Agent' })).toBeInTheDocument();
  expect(screen.getByLabelText('System Prompt')).toHaveValue('writer prompt');
});

it('shows read-only details for program nodes', async () => {
  renderPage();
  await userEvent.click(await screen.findByRole('button', { name: /硬规则校验/ }));
  expect(screen.getByText('该节点不使用 Prompt')).toBeInTheDocument();
  expect(screen.queryByLabelText('System Prompt')).not.toBeInTheDocument();
});

it('saves a changed prompt and keeps the selected Agent visible', async () => {
  renderPage();
  await userEvent.click(await screen.findByRole('button', { name: /故事作家 Agent/ }));
  await userEvent.clear(screen.getByLabelText('System Prompt'));
  await userEvent.type(screen.getByLabelText('System Prompt'), 'new writer prompt');
  await userEvent.click(screen.getByRole('button', { name: '保存提示词' }));
  await waitFor(() => expect(apiMocks.updateStoryAgent).toHaveBeenCalledWith('story-writer',
    expect.objectContaining({ systemPrompt: 'new writer prompt' })));
  expect(screen.getByRole('heading', { name: '故事作家 Agent' })).toBeInTheDocument();
  expect(screen.getByText(/Prompt v2/)).toBeInTheDocument();
});
```

- [ ] **Step 2: 运行测试确认红灯**

Run: `npm --prefix web-react test -- StoryAgentFlowPage.test.tsx`

Expected: FAIL，临时页面没有流程和详情。

- [ ] **Step 3: 实现加载、选中与脏状态**

页面加载 `getStoryAgentFlow()`，默认选择第一个 `editable` 节点。维护 `selectedKey`、`draft`、`dirty`、`loading`、`loadError` 和 `saving`。Agent 切换时：无修改直接切换；有修改使用 `modal.confirm`。同时注册 `beforeunload`，并持续调用 `onDirtyChange(dirty)`。

- [ ] **Step 4: 实现四段式画布**

每个 stage 渲染 `.story-flow-stage`。按 `parallelGroup` 将 `pitch` 和 `reviews` 节点放入虚线 `.story-parallel-group`。节点按钮显示角色类型、名称、描述、版本、Provider 简称和状态；`aria-pressed` 标识选中。质量区末尾固定展示决策回退标签和预算控制器摘要。

- [ ] **Step 5: 实现右侧 Prompt 中心详情**

编辑节点显示：启用 Switch、System Prompt TextArea、变量 Tag、Provider Select、Temperature InputNumber、只读上下游、Prompt 版本和保存按钮。程序/人工节点显示说明、输入输出和“该节点不使用 Prompt”。保存成功用 API 返回节点替换 flow 中对应节点，并重置 draft/dirty，不重新挂载页面。

- [ ] **Step 6: 实现版本列表与恢复**

“查看版本”打开 Modal，按版本倒序列出创建时间、Provider、Temperature 和 Prompt 预览。“恢复此版本”二次确认后调用 restore API；成功后用返回节点更新当前详情并关闭版本 Modal。恢复后显示新的最新版本号。

- [ ] **Step 7: 实现质量预算弹窗**

弹窗包含七个 InputNumber，对应后端范围。保存调用 `updateStoryFlowBudget`，用返回值更新页头和预算摘要。字段标签明确“最大质量轮次”“局部修订”“正文重写”“导演回退”“创意重做”“用词重做”“最大总 Token”。

- [ ] **Step 8: 实现样式和响应式**

新增统一前缀样式：`.story-workbench`、`.story-flow-canvas`、`.story-flow-stage`、`.story-agent-node`、`.story-parallel-group`、`.story-agent-detail`。桌面使用 `grid-template-columns: minmax(720px, 1fr) 440px`；`max-width: 1100px` 时改为单列，详情位于画布下方。选中节点使用 `--color-primary`，程序节点使用中性边框，审核/评分/决策节点使用同一质量色系但不依赖颜色传达角色。

- [ ] **Step 9: 运行前端测试和构建**

Run: `npm --prefix web-react test && npm --prefix web-react run build`

Expected: 所有 Vitest PASS；TypeScript 和 Vite 构建成功，允许现有大 chunk 警告。

- [ ] **Step 10: 提交**

```bash
git add web-react/src/StoryAgentFlowPage.tsx \
  web-react/src/StoryAgentFlowPage.test.tsx web-react/src/styles.css
git commit -m "feat: build interactive story agent flow workbench"
```

### Task 6: 当前事实文档

**Files:**
- Modify: `docs/backend/java_server/AGENTS.md`
- Modify: `docs/frontend/web_react/AGENTS.md`
- Modify: `docs/shared/system-overview.md`
- Modify: `docs/chains/README.md`
- Create: `docs/chains/story-agent-flow-config.md`

- [ ] **Step 1: 更新后端文档**

记录 `StoryAgentController` 五组接口、三张本地配置表、固定拓扑与初始化只补缺规则。明确本版仅配置、不运行故事，也不写外部材料库。

- [ ] **Step 2: 更新前端文档**

记录一级“Agent 工作台”、四段式画布、右侧 Prompt 详情、版本恢复和质量预算弹窗。明确无新增/删除/拖拽/运行记录。

- [ ] **Step 3: 新增跨层链路文档并登记**

`story-agent-flow-config.md` 描述：React -> `/api/story-agents/*` -> `StoryAgentService` -> 本地三表；Provider 仅引用现有配置 ID；外部词库不参与此配置链路。

- [ ] **Step 4: 检查文档与提交**

Run: `rg -n 'StoryAgent|story-agents|tb_story_agent|Agent 工作台' docs/backend docs/frontend docs/shared docs/chains && git diff --check`

Expected: 新能力在四类当前事实文档均可检索，diff 无空白错误。

```bash
git add docs/backend/java_server/AGENTS.md docs/frontend/web_react/AGENTS.md \
  docs/shared/system-overview.md docs/chains/README.md docs/chains/story-agent-flow-config.md
git commit -m "docs: document story agent flow configuration"
```

### Task 7: 完整验证、部署与浏览器验收

**Files:**
- Modify only if verification finds a scoped defect.

- [ ] **Step 1: 完整静态、测试和构建验证**

Run:

```bash
git diff --check
mvn -B -ntp test
mvn -B -ntp -DskipTests package
npm --prefix web-react test
npm --prefix web-react run build
```

Expected: 后端与前端全部 PASS；仅允许 Vite 现有 chunk 大小警告。

- [ ] **Step 2: 通过 Context Router 应用本轮全部实际变更**

使用当前任务 `task_id=577` 调用 `apply_workspace_changes`，`changed_files` 包含本轮所有新增和修改的 Workspace 相对路径。取得 operation ID 后持续调用 `get_workspace_operation`，直到 `succeeded`、`failed`、`cancelled` 或 `interrupted`；不得把 queued/running 当成功。

- [ ] **Step 3: 验证运行数据库和 API**

只读验证本地 `english_material` 配置库已存在：

- `tb_story_agent_config` 共 12 个固定 Key。
- `tb_story_agent_prompt_version` 每个 Agent 至少版本 1。
- `tb_story_flow_config` 存在 `default-story-flow`。

调用 `/api/story-agents/flow` 验证四阶段、12 个可编辑节点、5 个只读节点与默认预算。保存一个测试 Prompt 后恢复原内容，确认版本增加且配置不丢失；验收产生的临时修改必须恢复为原 Prompt，恢复动作作为新版本保留审计。

- [ ] **Step 4: 浏览器逐项验收**

打开 `http://127.0.0.1:19638`：

1. 顶部有“配置管理”“去重单词表”“Agent 工作台”。
2. 点击 Agent 工作台可见四个区段和完整节点。
3. 依次点击用词策划、故事作家、三个审核员、评分员、决策人和修订员，右侧 Prompt 与上下游正确切换。
4. 点击硬规则校验和预算控制器，只显示只读说明。
5. 编辑 Prompt 后切换节点会出现放弃确认；取消后内容仍在。
6. 保存 Prompt 后保持节点选中且版本更新。
7. 打开版本列表并恢复历史版本成功。
8. 修改并保存质量预算，再恢复原值。
9. 配置管理、AI/CLI 配置和去重单词页仍可进入。

- [ ] **Step 5: 最终工作树和提交检查**

Run: `git status --short && git log --oneline -12`

Expected: 工作树干净；目录、服务/API、菜单/API、工作台 UI 和文档提交存在。
