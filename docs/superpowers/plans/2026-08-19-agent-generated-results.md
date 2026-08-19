# Agent Generated Results Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an expandable “英语素材项目” navigation entry and a server-paginated page that displays completed Agent-generated final stories as full-width, auto-height reading blocks.

**Architecture:** Add a dedicated bounded story-results query that returns only completed runs with nonblank final stories and stable database ordering. A focused React page owns result pagination and request-race protection, while `App` owns the expandable project menu and routes into the page through the existing unsaved-change guard.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, JUnit 5, Mockito, React, TypeScript, Ant Design, Axios, Vitest, Testing Library, CSS.

---

## File map

- Modify `src/main/java/com/aitaskcenter/dto/StoryRunDtos.java`: paged result DTOs.
- Modify `src/main/java/com/aitaskcenter/repository/StoryRunRepository.java`: database-side completed-result page query.
- Modify `src/main/java/com/aitaskcenter/service/StoryRunQueryService.java`: validate pagination, map titles and word counts.
- Modify `src/main/java/com/aitaskcenter/controller/StoryRunController.java`: `GET /api/story-runs/results` route.
- Modify `src/test/java/com/aitaskcenter/service/StoryRunQueryServiceTest.java`: result filtering, ordering, title fallback, page metadata, no step lookup.
- Modify `src/test/java/com/aitaskcenter/controller/StoryRunControllerTest.java`: controller parameter and envelope contract.
- Modify `web-react/src/story-flow-types.ts`: result item/page types.
- Modify `web-react/src/api.ts`: result page request.
- Create `web-react/src/api.story-results.test.ts`: request parameter contract.
- Create `web-react/src/AgentGeneratedResultsPage.tsx`: archive page and request lifecycle.
- Create `web-react/src/AgentGeneratedResultsPage.test.tsx`: rendering, pagination, errors, copy, race handling.
- Modify `web-react/src/App.tsx`: expandable project entry and route.
- Modify `web-react/src/App.test.tsx`: menu semantics, selection, existing dirty guards.
- Modify `web-react/src/styles.css`: result archive layout and responsive behavior, preserving unrelated existing edits.
- Modify `docs/frontend/web_react/AGENTS.md`: document the new menu and page contract without overwriting unrelated local edits.

### Task 1: Backend paged results contract

**Files:**
- Modify: `src/test/java/com/aitaskcenter/service/StoryRunQueryServiceTest.java`
- Modify: `src/test/java/com/aitaskcenter/controller/StoryRunControllerTest.java`
- Modify: `src/main/java/com/aitaskcenter/dto/StoryRunDtos.java`
- Modify: `src/main/java/com/aitaskcenter/repository/StoryRunRepository.java`
- Modify: `src/main/java/com/aitaskcenter/service/StoryRunQueryService.java`
- Modify: `src/main/java/com/aitaskcenter/controller/StoryRunController.java`

- [ ] **Step 1: Write failing service tests**

Add tests that request page 1 with size 10, stub a Spring `Page<StoryRun>`, and assert:

```java
StoryResultPage page = service.listResults(1, 10);
assertEquals("The Wrong Recipe", page.items().getFirst().title());
assertEquals(20, page.items().getFirst().wordCount());
assertEquals(1, page.page());
assertEquals(10, page.pageSize());
assertEquals(21, page.totalItems());
assertEquals(3, page.totalPages());
verifyNoInteractions(stepRepository);
```

Also cover `null` grade -> `不限制`, malformed first line -> `未命名故事`, and rejection of page `0` or page size outside `10/20/100`.

- [ ] **Step 2: Write the failing controller test**

```java
mockMvc.perform(get("/api/story-runs/results?page=2&pageSize=20"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.page").value(2))
    .andExpect(jsonPath("$.data.pageSize").value(20))
    .andExpect(jsonPath("$.data.items[0].title").value("The Wrong Recipe"));
verify(service).listResults(2, 20);
```

- [ ] **Step 3: Run focused tests and confirm RED**

Run:

```bash
mvn -B -ntp -Dtest=StoryRunQueryServiceTest,StoryRunControllerTest test
```

Expected: compilation or assertion failure because paged result DTOs and route do not exist.

- [ ] **Step 4: Implement DTOs and repository query**

Add records equivalent to:

```java
public record StoryResultItem(
        String runId, String title, String targetGrade, int wordCount,
        String finalStory, OffsetDateTime createdAt) {}

public record StoryResultPage(
        List<StoryResultItem> items, int page, int pageSize,
        long totalItems, int totalPages) {}
```

Add a repository method using `Pageable` and stable `createdAt DESC, id DESC` ordering that filters `status = COMPLETED` and nonblank `finalStory` in SQL/JPA rather than in memory.

- [ ] **Step 5: Implement service validation and mapping**

Implement `listResults(int page, int pageSize)` with one-based external page numbering, an allowlist of `10, 20, 100`, `PageRequest.of(page - 1, pageSize)`, story title extraction from the first line matching `Scene N: title`, and word count from the bounded existing word snapshot parser. Do not query `StoryRunStepRepository`.

- [ ] **Step 6: Implement controller route**

```java
@GetMapping("/results")
public ApiResponse<StoryResultPage> listResults(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int pageSize) {
    return ApiResponse.ok(queryService.listResults(page, pageSize));
}
```

- [ ] **Step 7: Run focused backend tests and confirm GREEN**

Run the same Maven command. Expected: all `StoryRunQueryServiceTest` and `StoryRunControllerTest` tests pass.

- [ ] **Step 8: Commit backend slice**

Stage only the six Task 1 files and commit `feat: paginate agent story results`.

### Task 2: Frontend API and result archive page

**Files:**
- Modify: `web-react/src/story-flow-types.ts`
- Modify: `web-react/src/api.ts`
- Create: `web-react/src/api.story-results.test.ts`
- Create: `web-react/src/AgentGeneratedResultsPage.tsx`
- Create: `web-react/src/AgentGeneratedResultsPage.test.tsx`
- Modify: `web-react/src/styles.css`

- [ ] **Step 1: Write failing API and page tests**

API assertions:

```ts
await getStoryResults(2, 20);
expect(request.get).toHaveBeenCalledWith('/story-runs/results', {
  params: { page: 2, pageSize: 20 },
});
```

Page assertions cover default page 1/size 10, full story text, one-line title content, copy, 20/100 size changes returning to page 1, page navigation, skeleton, empty state, initial error retry, preserving content after page-change failure, and discarding a stale response after rapid page changes.

- [ ] **Step 2: Run focused frontend tests and confirm RED**

```bash
npm --prefix web-react test -- api.story-results.test.ts AgentGeneratedResultsPage.test.tsx
```

Expected: missing exports/components and failed behavior assertions.

- [ ] **Step 3: Add exact TypeScript contracts and API call**

```ts
export interface StoryResultItem {
  runId: string;
  title: string;
  targetGrade: string;
  wordCount: number;
  finalStory: string;
  createdAt: string;
}

export interface StoryResultPage {
  items: StoryResultItem[];
  page: number;
  pageSize: 10 | 20 | 100;
  totalItems: number;
  totalPages: number;
}
```

The request accepts only the same page-size union and calls `/story-runs/results` with query params.

- [ ] **Step 4: Implement `AgentGeneratedResultsPage`**

Use a focused component with `page`, `pageSize`, `data`, `initialLoading`, `pageLoading`, and bounded `error` state. Use a request-generation ref so only the newest request mutates state. Keep old data during page changes, reset to page 1 on size changes, and scroll the results heading into view after successful navigation.

Render each item as a named `<article>` with:

```tsx
<header>
  <h3 title={fullTitle}>{fullTitle}</h3>
  <Button aria-label={`复制故事 ${item.title}`}>复制全文</Button>
</header>
<pre>{item.finalStory}</pre>
```

Use Ant Design `Pagination`, `Skeleton`, `Empty`, `Alert`, and the existing message system. Never use `dangerouslySetInnerHTML`.

- [ ] **Step 5: Add scoped archive styles**

Add `.agent-results-*` classes only. The page is one full-width column; articles have separate one-line title and auto-height body layers. Set `white-space: pre-wrap`, `overflow-wrap: anywhere`, readable line height, visible focus styles, and responsive metadata/title behavior without horizontal clipping. Preserve all unrelated local edits already present in `styles.css`.

- [ ] **Step 6: Run focused frontend tests and confirm GREEN**

Run the Task 2 focused test command. Expected: all new API and page tests pass.

- [ ] **Step 7: Commit frontend archive slice**

Stage only the six Task 2 files and commit `feat: add agent result archive page`.

### Task 3: Expandable navigation and route integration

**Files:**
- Modify: `web-react/src/App.tsx`
- Modify: `web-react/src/App.test.tsx`
- Modify: `web-react/src/styles.css`

- [ ] **Step 1: Write failing App tests**

Cover:

```ts
expect(screen.queryByRole('menuitem', { name: 'Agent 生成结果' })).not.toBeInTheDocument();
await user.click(screen.getByRole('menuitem', { name: '英语素材项目' }));
await user.click(await screen.findByRole('menuitem', { name: 'Agent 生成结果' }));
expect(await screen.findByRole('region', { name: 'Agent 生成结果' })).toBeInTheDocument();
```

Also verify Escape/outside selection closes the submenu, existing top-level menu order remains unchanged, and navigating from dirty story/image workbenches invokes the existing confirm before entering the result page.

- [ ] **Step 2: Run App test and confirm RED**

```bash
npm --prefix web-react test -- App.test.tsx
```

Expected: the expandable entry and result region do not exist.

- [ ] **Step 3: Integrate route through existing section guard**

Add a `results` workspace section and render `AgentGeneratedResultsPage`. Use Ant Design menu submenu semantics for “英语素材项目” with the sole child “Agent 生成结果”, while retaining the four existing top-level entries. Ensure child navigation calls the same `changeSection` function so existing dirty guards remain authoritative.

- [ ] **Step 4: Add minimal navigation CSS**

Only add scoped menu alignment/selected-state styles if Ant Design defaults do not match the existing header. Do not build a custom popover or second navigation row.

- [ ] **Step 5: Run App and combined focused tests**

```bash
npm --prefix web-react test -- App.test.tsx AgentGeneratedResultsPage.test.tsx api.story-results.test.ts
```

Expected: all focused tests pass.

- [ ] **Step 6: Commit navigation slice**

Stage only `App.tsx`, `App.test.tsx`, and the Task 3 additions in `styles.css`; commit `feat: expose agent result archive`.

### Task 4: Documentation and full verification

**Files:**
- Modify: `docs/frontend/web_react/AGENTS.md`

- [ ] **Step 1: Document the current feature**

Add the expandable project menu, completed-story-only result archive, title composition, 10/20/100 server pagination, full-width auto-height stories, and explicit first-version exclusions. Preserve unrelated local documentation edits.

- [ ] **Step 2: Run backend full tests**

```bash
mvn -B -ntp clean test
```

Expected: BUILD SUCCESS with zero failures/errors. If local HTTP fixture tests require host loopback permission, rerun the identical command in the approved host environment.

- [ ] **Step 3: Run frontend full tests**

```bash
npm --prefix web-react test -- --run
```

Expected: all test files pass with zero failures.

- [ ] **Step 4: Run production build**

```bash
npm --prefix web-react run build
```

Expected: TypeScript and Vite build exit 0; the known chunk-size warning is non-blocking.

- [ ] **Step 5: Check exact diff quality**

```bash
git diff --check
git diff --cached --check
```

Review changed files to ensure unrelated pre-existing work remains untouched and no secrets or generated assets are staged.

- [ ] **Step 6: Commit documentation**

Stage only `docs/frontend/web_react/AGENTS.md` and commit `docs: document agent result archive`.

- [ ] **Step 7: Request code review**

Use the requesting-code-review workflow against the complete feature commit range. Fix any Critical or Important findings with focused RED-to-GREEN tests, then rerun impacted focused tests plus full verification.
