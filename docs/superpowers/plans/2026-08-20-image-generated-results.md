# 图片生成结果归档 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在“英语素材项目”下提供服务端分页的最终图片批次画廊，只展示安全的 FINAL 成品图。

**Architecture:** 后端以 ImageRun 为分页主表，并对当前页 runId 批量读取 FINAL 资产与分镜，返回专用轻量 DTO。前端使用独立页面、受控资产 ID URL、Ant Design 预览和请求 generation；App 只负责菜单与现有 dirty guard 接入。

**Tech Stack:** Java 17、Spring Boot、Spring Data JPA、JUnit 5/Mockito、React 18、TypeScript、Ant Design、Vitest/Testing Library。

---

### Task 1: 后端图片结果分页合同

**Files:**
- Modify: `src/main/java/com/aitaskcenter/dto/ImageRunDtos.java`
- Modify: `src/main/java/com/aitaskcenter/repository/ImageRunRepository.java`
- Modify: `src/main/java/com/aitaskcenter/repository/ImageAssetRepository.java`
- Modify: `src/main/java/com/aitaskcenter/repository/ImageShotRepository.java`
- Modify: `src/main/java/com/aitaskcenter/service/ImageRunQueryService.java`
- Modify: `src/main/java/com/aitaskcenter/controller/ImageRunController.java`
- Test: `src/test/java/com/aitaskcenter/service/ImageRunQueryServiceTest.java`
- Test: `src/test/java/com/aitaskcenter/controller/ImageRunControllerTest.java`

- [ ] **Step 1: 写失败测试**

测试 `listResults(1, 10)` 只返回 COMPLETED、按当前页批量查询 FINAL 资产与 shots、按 sequence 组装，并验证非法页码和 25 条 pageSize 被拒绝。Controller 测试锁定：

```java
mockMvc.perform(get("/api/image-runs/results").param("page", "2").param("pageSize", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page").value(2));
```

- [ ] **Step 2: 运行 RED**

Run: `mvn -B -ntp -Dtest=ImageRunQueryServiceTest,ImageRunControllerTest test`

Expected: 编译失败，缺少 `ImageResultPage`、repository 批量查询和 `listResults`。

- [ ] **Step 3: 最小实现**

增加：

```java
public record ImageResultShot(Long assetId, String shotKey, int sceneIndex, int shotIndex,
        int sequence, String sourceExcerpt, String dialogue, String caption) {}

public record ImageResultItem(String runId, String title, String stylePresetName,
        String targetGrade, int imageCount, OffsetDateTime completedAt,
        List<ImageResultShot> shots) {}

public record ImageResultPage(List<ImageResultItem> items, int page, int pageSize,
        long totalItems, int totalPages) {}
```

`ImageRunRepository` 返回 `Page<ImageRun>`；Asset/Shot repository 接受 `Collection<String> runIds` 做批量有界查询。Service 校验 page/pageSize、从 story snapshot 提取标题、以 `(runId, shotKey)` 配对、按 sequence 排序并忽略孤立资产。Controller 注册 `/results` 静态路由。

- [ ] **Step 4: 运行 GREEN**

Run: `mvn -B -ntp -Dtest=ImageRunQueryServiceTest,ImageRunControllerTest test`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add <Task 1 精确文件>
git commit -m "feat: paginate final image results"
```

### Task 2: 前端图片结果画廊

**Files:**
- Modify: `web-react/src/image-story-types.ts`
- Modify: `web-react/src/api.ts`
- Create: `web-react/src/ImageGeneratedResultsPage.tsx`
- Create: `web-react/src/ImageGeneratedResultsPage.test.tsx`
- Create: `web-react/src/api.image-results.test.ts`
- Modify: `web-react/src/styles.css`

- [ ] **Step 1: 写失败测试**

覆盖默认 1/10 请求、批次全宽区块、FINAL 图排序、`imageAssetUrl(assetId)`、预览、20/100 切换、初始失败、分页失败保留、空态、迟到响应、越界页恢复和单图加载错误。

```tsx
expect(getImageResults).toHaveBeenCalledWith(1, 10);
expect(screen.getByRole('img', { name: 'Scene 1 · Shot 1' }))
  .toHaveAttribute('src', expect.stringContaining('/image-assets/41/content'));
```

- [ ] **Step 2: 运行 RED**

Run: `npm --prefix web-react test -- ImageGeneratedResultsPage.test.tsx api.image-results.test.ts`

Expected: 缺少组件、类型和 `getImageResults`。

- [ ] **Step 3: 最小实现**

新增 `ImageResultPageSize`、`ImageResultShot`、`ImageResultItem`、`ImageResultPage`；API：

```ts
export const getImageResults = (page: number, pageSize: ImageResultPageSize) =>
  request.get<ApiResponse<ImageResultPage>>('/image-runs/results', { params: { page, pageSize } }).then(unwrap);
```

组件使用 `Image.PreviewGroup`、响应式网格、每页 Select/Pagination、局部图片错误状态和 request generation。样式只添加 `.image-results-*`。

- [ ] **Step 4: 运行 GREEN**

Run: `npm --prefix web-react test -- ImageGeneratedResultsPage.test.tsx api.image-results.test.ts`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add <Task 2 精确文件>
git commit -m "feat: add final image result gallery"
```

### Task 3: 菜单与未保存保护接入

**Files:**
- Modify: `web-react/src/App.tsx`
- Modify: `web-react/src/App.test.tsx`

- [ ] **Step 1: 写失败测试**

断言“英语素材项目”展开后顺序为 Agent 生成结果、图片生成结果；点击新项展示结果页；从故事或图片工作台带 dirty 离开时仍先确认。

- [ ] **Step 2: 运行 RED**

Run: `npm --prefix web-react test -- App.test.tsx`

Expected: 新菜单项和区域缺失。

- [ ] **Step 3: 最小实现**

给 `WorkspaceSection` 增加 `image-results`，submenu 添加新 child，渲染 `ImageGeneratedResultsPage`，沿用 `changeSection` 和现有两个 dirty guard；结果页使用独立 workspace class。

- [ ] **Step 4: 运行 GREEN**

Run: `npm --prefix web-react test -- App.test.tsx`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add web-react/src/App.tsx web-react/src/App.test.tsx
git commit -m "feat: expose final image results"
```

### Task 4: 文档、回归和审查

**Files:**
- Modify: `docs/backend/java_server/AGENTS.md`
- Modify: `docs/frontend/web_react/AGENTS.md`
- Modify: `docs/chains/image-story-generation.md`

- [ ] **Step 1: 更新事实文档**

记录 `/api/image-runs/results`、10/20/100 分页、FINAL-only、受控资产 ID、大图预览和不展示审计中间过程。

- [ ] **Step 2: 后端全量验证**

Run: `mvn -B -ntp clean test`

Expected: 0 failures/errors。

- [ ] **Step 3: 前端全量验证**

Run: `npm --prefix web-react test -- --run`

Expected: 0 failures。

- [ ] **Step 4: 生产构建与差异检查**

Run: `npm --prefix web-react run build`

Run: `git diff --check`

Expected: build exit 0；diff check 无输出。

- [ ] **Step 5: 只读审查并提交文档**

检查分页稳定性、N+1、资产 URL、XSS、竞态、dirty guard 与现有未提交改动隔离；只暂存本功能文档增量。
