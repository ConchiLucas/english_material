# Agent Single List and Detail Drawer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three-column Agent workbench and top-level views with one Agent table whose operation column opens a wide detail drawer for editing, testing, and per-Agent run history.

**Architecture:** Keep `AgentWorkspacePage` as the data owner and reuse all existing APIs. Present filtered definitions through an Ant Design table; reuse the existing form and test-result UI inside a controlled Drawer with three tabs, and derive per-Agent history from the already loaded run list.

**Tech Stack:** React 18, TypeScript, Ant Design 5, Vite 6, Vitest, Testing Library, CSS.

---

### Task 1: Add the frontend behavior test harness

**Files:**
- Modify: `web-react/package.json`
- Modify: `web-react/package-lock.json`
- Create: `web-react/vitest.config.ts`
- Create: `web-react/src/test/setup.ts`

- [ ] **Step 1: Add test dependencies and script**

Run from `web-react`:

```bash
npm install --save-dev vitest jsdom @testing-library/react @testing-library/jest-dom @testing-library/user-event
```

Add this script to `package.json`:

```json
"test": "vitest run"
```

- [ ] **Step 2: Configure Vitest**

Create `vitest.config.ts`:

```ts
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
});
```

Create `src/test/setup.ts` with `@testing-library/jest-dom/vitest`, cleanup, `matchMedia`, `ResizeObserver`, and `scrollTo` shims required by Ant Design.

- [ ] **Step 3: Verify the empty harness**

Run: `npm test -- --passWithNoTests`

Expected: exit 0 with no test failures.

- [ ] **Step 4: Commit the harness**

```bash
git add web-react/package.json web-react/package-lock.json web-react/vitest.config.ts web-react/src/test/setup.ts
git commit -m "test: add agent workspace frontend harness"
```

### Task 2: Specify the single-list behavior with failing tests

**Files:**
- Create: `web-react/src/AgentWorkspacePage.test.tsx`

- [ ] **Step 1: Mock the existing Agent APIs**

Use two Agent fixtures and two run fixtures with distinct `agentId` and diagnostic messages. Mock `getAgents`, `getAgentRuns`, `createAgent`, `updateAgent`, and `testAgent`, and render `AgentWorkspacePage` inside Ant Design `App`.

- [ ] **Step 2: Write the list-only test**

Assert that the loaded page contains one table and row-level “详情” buttons, while the top-level “Agent 配置”, “流程视图”, and “运行记录” tabs are absent before opening a detail drawer.

- [ ] **Step 3: Write the detail drawer test**

Click the first row's “详情”, assert a dialog opens with “配置编辑”, “在线测试”, and “运行记录” tabs, then select “运行记录” and assert only the selected Agent's diagnostic appears.

- [ ] **Step 4: Write the new-Agent test**

Click “新增 Agent” and assert the same drawer opens in “新增 Agent” state with configuration fields available.

- [ ] **Step 5: Run the tests and verify RED**

Run: `npm test -- AgentWorkspacePage.test.tsx`

Expected: FAIL because the existing page renders top-level tabs and has no detail Drawer.

- [ ] **Step 6: Commit the failing specification**

```bash
git add web-react/src/AgentWorkspacePage.test.tsx
git commit -m "test: specify agent list detail workflow"
```

### Task 3: Implement the table and detail drawer

**Files:**
- Modify: `web-react/src/AgentWorkspacePage.tsx`

- [ ] **Step 1: Replace view state with drawer state**

Remove `view` and introduce:

```ts
const [drawerOpen, setDrawerOpen] = useState(false);
const [detailTab, setDetailTab] = useState<'config' | 'test' | 'runs'>('config');
```

Change selection and creation handlers to populate the form, reset test state, select the config tab, and open the drawer.

- [ ] **Step 2: Add safe drawer closing**

Use `modal.confirm` from `AntApp.useApp()` when `dirty` is true. Close immediately otherwise. The confirmed close must reset `drawerOpen`, `dirty`, and transient test results without changing the category filter.

- [ ] **Step 3: Replace the catalog with an Agent table**

Define `ColumnsType<AgentDefinition>` columns for name/key, category, description, current default CLI, update time, and an operation button:

```tsx
<Button type="link" onClick={() => openAgent(record)}>详情</Button>
```

Render the table inside one `Card`, with the existing category selector in the card header and horizontal scrolling on narrow screens.

- [ ] **Step 4: Move existing content into Drawer tabs**

Render one wide `Drawer` whose tab items are:

```ts
[
  { key: 'config', label: '配置编辑', children: renderEditor() },
  { key: 'test', label: '在线测试', children: renderTestPanel() },
  { key: 'runs', label: '运行记录', children: renderAgentRuns() },
]
```

Reuse the existing form, test input, test execution, result display, and run columns. Filter run data with `runs.filter((run) => run.agentId === selectedId)`; new Agents show a save-first empty state.

- [ ] **Step 5: Keep drawer state stable after save and test**

After saving, retain the saved Agent selection and open drawer. After testing, prepend the new result and leave the test tab visible.

- [ ] **Step 6: Run focused tests and verify GREEN**

Run: `npm test -- AgentWorkspacePage.test.tsx`

Expected: all Agent workspace tests pass.

- [ ] **Step 7: Commit the functional change**

```bash
git add web-react/src/AgentWorkspacePage.tsx
git commit -m "feat: simplify agent workspace to list and drawer"
```

### Task 4: Align styling and documentation

**Files:**
- Modify: `web-react/src/styles.css`
- Modify: `docs/frontend/web_react/AGENTS.md`

- [ ] **Step 1: Replace obsolete workbench layout styles**

Remove rules dedicated to `.agent-workbench-grid`, `.agent-catalog`, `.agent-flow-*`, and sticky `.agent-test-panel`. Add focused rules for `.agent-list-card`, `.agent-list-name`, `.agent-detail-drawer`, `.agent-detail-form`, `.agent-detail-test`, and `.agent-detail-runs`.

- [ ] **Step 2: Add responsive behavior**

Keep table horizontal scrolling and make the Drawer full-width at the existing mobile breakpoint. Preserve readable form gutters and code-editor heights.

- [ ] **Step 3: Update the frontend facts**

Replace the Agent page description with the new list-and-drawer behavior and explicitly state that workflow view is no longer a current page capability.

- [ ] **Step 4: Run tests and production build**

Run:

```bash
npm test
npm run build
```

Expected: all tests pass and Vite finishes with exit 0.

- [ ] **Step 5: Commit styles and docs**

```bash
git add web-react/src/styles.css docs/frontend/web_react/AGENTS.md
git commit -m "style: refine agent list detail layout"
```

### Task 5: Deploy and visually verify

**Files:**
- No additional source files expected.

- [ ] **Step 1: Apply the complete workspace change set**

Call Context Router `apply_workspace_changes` with task ID `573` and every changed workspace-relative path from Tasks 1–4. Poll `get_workspace_operation` until terminal success.

- [ ] **Step 2: Verify the running frontend**

Open `http://127.0.0.1:19638`, navigate to Agent 工作台, and verify the table-only page, detail drawer tabs, creation state, per-Agent history, and narrow-screen behavior.

- [ ] **Step 3: Run final repository checks**

Run:

```bash
git status --short
git log -5 --oneline
```

Confirm only intended changes remain and all implementation commits are present.

