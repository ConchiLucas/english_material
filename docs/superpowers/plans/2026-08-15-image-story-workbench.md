# Image Story Workbench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a first-level image workbench beside the existing Agent workbench that turns an existing final story into a 16:9, multi-shot illustrated sequence through nine configurable text Agents, reference-image generation, one image call per shot, deterministic dialogue/caption composition, and full run auditing.

**Architecture:** Keep the fixed image topology in a Java catalog, persist editable Agent/style/model configuration and immutable run snapshots in PostgreSQL, and store image bytes under a configured local root with database metadata. Text Agents exchange schema-checked JSON; one OpenAI Images-compatible adapter generates reference and shot images; React provides configuration, start, and full-screen history views without visual scoring or redraw controls.

**Tech Stack:** Java 17, Spring Boot 3.3, Spring Data JPA, Java `HttpClient`, Jackson, Java2D/ImageIO, PostgreSQL, React, TypeScript, Ant Design, Axios, Vitest, Testing Library, Vite.

---

## File map

Backend files are split by responsibility instead of placing the feature in one large service:

- `config/ImageAgentCatalog.java`: fixed nine-Agent/program/human topology, variables, schemas, and default prompts.
- `config/ImageAgentInitializer.java`: idempotent missing-only initialization.
- `config/ImageRunExecutorConfig.java`: bounded run and parallel planning executors.
- `dto/ImageAgentDtos.java`: configuration, versions, flow, and style DTOs.
- `dto/ImageRunDtos.java`: source-story, run, step, shot, and asset DTOs.
- `model/Image*.java` and `repository/Image*.java`: eight image configuration/run tables.
- `service/ImageAgentService.java`: config validation, optimistic concurrency, versions, styles, and provider selection.
- `service/ImageStructuredOutputParser.java`: unique JSON-block extraction, record parsing, and cross-output validation.
- `service/AiImageGenerationService.java`: OpenAI Images-compatible generations/edits client.
- `service/ImageAssetStore.java`: safe relative-path image persistence and reads.
- `service/ImageTextCompositor.java`: deterministic speech bubbles and bottom captions.
- `service/ImageRunExecutionService.java`: nine-Agent orchestration and image pipeline.
- `service/ImageRunQueryService.java`: read-only run/detail/source-story projections.
- `controller/ImageAgentController.java`, `ImageStylePresetController.java`, `ImageRunController.java`, `ImageAssetController.java`: HTTP boundaries.
- `web-react/src/image-story-types.ts`: exact frontend contracts.
- `web-react/src/ImageAgentFlowPage.tsx`: flow/config/start surface.
- `web-react/src/ImageRunHistory.tsx`: full-screen audit and gallery.

## Task 1: OpenAI Images-compatible provider adapter

**Files:**
- Create: `src/main/java/com/aitaskcenter/service/AiImageGenerationService.java`
- Create: `src/test/java/com/aitaskcenter/service/AiImageGenerationServiceTest.java`
- Modify: `src/main/java/com/aitaskcenter/dto/AiProviderConfigItem.java`
- Modify: `web-react/src/api.ts`

- [ ] **Step 1: Write failing adapter tests**

Use JDK `HttpServer` to capture requests. Cover generations without references, multipart edits with two references, `b64_json` decoding, missing image data, non-2xx bounded errors, and base URLs that already end in `/v1`.

```java
@Test
void generatesAndEditsImagesThroughOpenAiCompatibleEndpoints() {
    var noRefs = service.generate(provider(serverUrl), "draw a bird", "no text", 1536, 864, List.of());
    assertEquals("/v1/images/generations", requests.get(0).path());
    assertArrayEquals(PNG_BYTES, noRefs.bytes());

    var refs = List.of(
            new ImageReference("character.png", "image/png", PNG_BYTES),
            new ImageReference("zoo.png", "image/png", PNG_BYTES));
    service.generate(provider(serverUrl), "same bird at the zoo", "no text", 1536, 864, refs);
    assertEquals("/v1/images/edits", requests.get(1).path());
    assertTrue(requests.get(1).contentType().startsWith("multipart/form-data; boundary="));
    assertTrue(requests.get(1).bodyText().contains("name=\"image\"; filename=\"character.png\""));
    assertTrue(requests.get(1).bodyText().contains("name=\"image\"; filename=\"zoo.png\""));
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
JAVA_HOME=/Users/conchi/Library/Java/JavaVirtualMachines/graalvm-ce-21.0.2/Contents/Home \
  /opt/homebrew/bin/mvn -B -ntp -Dtest=AiImageGenerationServiceTest test
```

Expected: test compilation fails because `AiImageGenerationService` and its records do not exist.

- [ ] **Step 3: Implement the adapter and provider option contract**

The production API is fixed:

```java
@Service
public class AiImageGenerationService {
    public record ImageReference(String filename, String mimeType, byte[] bytes) {}
    public record ImageResult(byte[] bytes, String mimeType, int width, int height,
                              String providerRequestId, Map<String, Object> metadata) {}

    public ImageResult generate(AiProviderConfigItem provider,
                                String prompt,
                                String negativePrompt,
                                int width,
                                int height,
                                List<ImageReference> references) {
        requireImageCapabilities(provider, !references.isEmpty());
        HttpRequest request = references.isEmpty()
                ? buildGenerationRequest(provider, prompt, negativePrompt, width, height)
                : buildEditRequest(provider, prompt, negativePrompt, width, height, references);
        HttpResponse<String> response = send(request);
        return decodeImageResult(response);
    }
}
```

`requireImageCapabilities`, `buildGenerationRequest`, `buildEditRequest`, `send`, and `decodeImageResult` are private methods in this same file. Their focused tests must exercise their behavior only through `generate`; do not expose transport helpers as public API.

Read optional image request keys from `provider.options`: `responseFormat` defaults to `b64_json`, `quality` defaults to `high`, and `size` defaults to `1536x864`. Never serialize API keys into result metadata. Add `options?: Record<string, unknown>` to `AIProviderConfigItem` in `web-react/src/api.ts` so the existing AI editor preserves image options.

- [ ] **Step 4: Run focused tests and diff checks**

Run the Task 1 Maven command and `git diff --check`. Expected: all adapter tests pass and no whitespace errors.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/aitaskcenter/service/AiImageGenerationService.java \
  src/main/java/com/aitaskcenter/dto/AiProviderConfigItem.java \
  src/test/java/com/aitaskcenter/service/AiImageGenerationServiceTest.java \
  web-react/src/api.ts
git commit -m "feat: add image generation provider adapter"
```

## Task 2: Fixed image Agent catalog and persistence model

**Files:**
- Create: `src/main/java/com/aitaskcenter/config/ImageAgentCatalog.java`
- Create: `src/main/java/com/aitaskcenter/model/ImageAgentConfig.java`
- Create: `src/main/java/com/aitaskcenter/model/ImageAgentPromptVersion.java`
- Create: `src/main/java/com/aitaskcenter/model/ImageFlowConfig.java`
- Create: `src/main/java/com/aitaskcenter/model/ImageStylePreset.java`
- Create: `src/main/java/com/aitaskcenter/repository/ImageAgentConfigRepository.java`
- Create: `src/main/java/com/aitaskcenter/repository/ImageAgentPromptVersionRepository.java`
- Create: `src/main/java/com/aitaskcenter/repository/ImageFlowConfigRepository.java`
- Create: `src/main/java/com/aitaskcenter/repository/ImageStylePresetRepository.java`
- Create: `src/test/java/com/aitaskcenter/config/ImageAgentCatalogTest.java`

- [ ] **Step 1: Write catalog tests for the exact topology**

```java
@Test
void exposesNineEditableAgentsAndThreeProgramNodes() {
    var stages = ImageAgentCatalog.stages();
    var nodes = stages.stream().flatMap(stage -> stage.nodes().stream()).toList();
    assertEquals(List.of(
            "image-story-analyst", "image-continuity-designer", "image-art-director",
            "image-action-storyboarder", "image-learning-storyboarder",
            "image-storyboard-director", "image-reference-planner",
            "image-shot-prompt-engineer", "image-prompt-preflight"),
            nodes.stream().filter(ImageAgentCatalog.NodeDefinition::editable)
                    .map(ImageAgentCatalog.NodeDefinition::key).toList());
    assertEquals(List.of("reference-image-generator", "shot-image-generator", "text-compositor"),
            nodes.stream().filter(node -> "PROGRAM".equals(node.nodeKind()))
                    .map(ImageAgentCatalog.NodeDefinition::key).toList());
}

@Test
void locksStoryboardAndImageLimits() {
    assertEquals(5, ImageAgentCatalog.DEFAULT_MAX_SHOTS_PER_SCENE);
    assertEquals(20, ImageAgentCatalog.DEFAULT_MAX_SHOTS_PER_STORY);
    assertEquals(1536, ImageAgentCatalog.DEFAULT_WIDTH);
    assertEquals(864, ImageAgentCatalog.DEFAULT_HEIGHT);
}
```

- [ ] **Step 2: Run catalog tests and verify RED**

Run the focused Maven command with `-Dtest=ImageAgentCatalogTest`. Expected: missing catalog symbols.

- [ ] **Step 3: Implement the catalog and JPA entities**

Define four stages: `understanding`, `storyboarding`, `prompting`, `generation`. The first three Agents use parallel group `image-foundation`; the two storyboarders use `image-storyboards`; Agents 6–9 are ordered. Every default prompt includes its exact input variables, JSON block protocol, third-grade text constraint, no-image-text rule, continuity rules, and output schema name.

Use these entity invariants:

- `ImageAgentConfig`: `@Table(name="tb_image_agent_config")`, unique non-null `agent_key`, `system_prompt` as `TEXT`, nullable `ai_provider_id`, non-null `temperature`, `enabled`, `prompt_version`, and `@Version long lockVersion`.
- `ImageAgentPromptVersion`: `@Table(name="tb_image_agent_prompt_version", uniqueConstraints=unique(agent_key,prompt_version))`, with immutable snapshot fields for Prompt, Provider, Temperature, and enabled state.
- `ImageFlowConfig`: `@Table(name="tb_image_flow_config")`, unique `flow_key="default"`, nullable `image_provider_id`, `width=1536`, `height=864`, `max_shots_per_scene=5`, `max_shots_per_story=20`, and `@Version long lockVersion`.
- `ImageStylePreset`: `@Table(name="tb_image_style_preset")`, unique `preset_key`, `name`, `positive_prompt`/`negative_prompt`/`description` as `TEXT`, `enabled`, `built_in`, and `@Version long lockVersion`.

Each entity extends `BaseEntity`, uses explicit column names, `TEXT` for prompt content, and ordinary getters/setters matching the existing story entities.

- [ ] **Step 4: Run tests and commit**

Run `ImageAgentCatalogTest`, then `git diff --check`. Commit only Task 2 files:

```bash
git commit -m "feat: define image agent catalog"
```

## Task 3: Image workbench configuration, versions, styles, and controller

**Files:**
- Create: `src/main/java/com/aitaskcenter/dto/ImageAgentDtos.java`
- Create: `src/main/java/com/aitaskcenter/service/ImageAgentService.java`
- Create: `src/main/java/com/aitaskcenter/config/ImageAgentInitializer.java`
- Create: `src/main/java/com/aitaskcenter/controller/ImageAgentController.java`
- Create: `src/main/java/com/aitaskcenter/controller/ImageStylePresetController.java`
- Create: `src/test/java/com/aitaskcenter/service/ImageAgentServiceTest.java`
- Create: `src/test/java/com/aitaskcenter/controller/ImageAgentControllerTest.java`
- Create: `src/test/java/com/aitaskcenter/controller/ImageStylePresetControllerTest.java`

- [ ] **Step 1: Write service RED tests**

Cover missing-only initialization, provider preference selection, nine-Agent flow merging, text/image capability filtering, stale timestamp rejection before mutation, prompt version append/restore, built-in style creation, style snapshots, and fixed 16:9/5/20 constraints.

```java
@Test
void rejectsImageProviderWithoutBothCapabilities() {
    when(aiConfigService.getProviders()).thenReturn(config(provider("text-only", "TEXT_GENERATION")));
    var error = assertThrows(IllegalArgumentException.class,
            () -> service.updateFlow(new FlowUpdateRequest("text-only", 1536, 864, 5, 20, null)));
    assertEquals("请选择支持图片生成和多参考图的 Provider", error.getMessage());
}

@Test
void initializesOnlyMissingDefaults() {
    when(agentRepository.findByAgentKey("image-story-analyst")).thenReturn(Optional.of(existing));
    service.initializeDefaults();
    verify(agentRepository, never()).save(argThat(value ->
            "image-story-analyst".equals(value.getAgentKey())));
    verify(styleRepository, atLeastOnce()).save(argThat(style -> style.isBuiltIn()));
}
```

- [ ] **Step 2: Run service tests and verify RED**

Run `-Dtest=ImageAgentServiceTest`. Expected: missing DTO/service/initializer symbols.

- [ ] **Step 3: Implement DTOs and service API**

Use exact public operations:

```java
public FlowView getFlow();
public AgentView updateAgent(String agentKey, AgentUpdateRequest request);
public List<PromptVersionView> versions(String agentKey);
public AgentView restoreVersion(String agentKey, int version, OffsetDateTime updatedAt);
public FlowConfigView updateFlow(FlowUpdateRequest request);
public List<StylePresetView> styles();
public StylePresetView createStyle(StyleCreateRequest request);
public StylePresetView updateStyle(long presetId, StyleUpdateRequest request);
public void initializeDefaults();
```

All write requests carry `updatedAt` except creates. Validate trimmed non-empty prompts, temperature `0..2`, enabled text Provider for Agents, both image capabilities for flow config, immutable fixed dimensions/limits, preset name/prompt lengths, and optimistic exceptions mapped to the same stale message as story Agents. Flush before creating a version snapshot and return the flushed timestamp.

- [ ] **Step 4: Write controller RED tests and implement routes**

MockMvc must cover all routes from the design under `/api/image-agents` and `/api/image-style-presets`, including URL encoding and exact service delegation. `ImageAgentInitializer` is an `ApplicationRunner` component calling `initializeDefaults()` once.

- [ ] **Step 5: Run service/controller tests and commit**

Run `-Dtest=ImageAgentServiceTest,ImageAgentControllerTest,ImageStylePresetControllerTest`; expected all pass. Commit:

```bash
git commit -m "feat: expose image workbench configuration"
```

## Task 4: Image run, shot, asset persistence and safe file storage

**Files:**
- Create: `src/main/java/com/aitaskcenter/model/ImageRun.java`
- Create: `src/main/java/com/aitaskcenter/model/ImageRunStep.java`
- Create: `src/main/java/com/aitaskcenter/model/ImageShot.java`
- Create: `src/main/java/com/aitaskcenter/model/ImageAsset.java`
- Create: `src/main/java/com/aitaskcenter/repository/ImageRunRepository.java`
- Create: `src/main/java/com/aitaskcenter/repository/ImageRunStepRepository.java`
- Create: `src/main/java/com/aitaskcenter/repository/ImageShotRepository.java`
- Create: `src/main/java/com/aitaskcenter/repository/ImageAssetRepository.java`
- Create: `src/main/java/com/aitaskcenter/service/ImageAssetStore.java`
- Create: `src/main/java/com/aitaskcenter/config/ImageRunRecoveryInitializer.java`
- Create: `src/test/java/com/aitaskcenter/service/ImageAssetStoreTest.java`
- Create: `src/test/java/com/aitaskcenter/config/ImageRunRecoveryInitializerTest.java`
- Modify: `src/main/resources/application.yml`
- Modify: `.env.local.example`

- [ ] **Step 1: Write storage RED tests**

Use `@TempDir` and a 1x1 PNG. Assert deterministic run directories, atomic writes, SHA-256, MIME/size extraction, safe reads, and path traversal rejection.

```java
@Test
void storesAndReadsOnlyFilesBelowConfiguredRoot(@TempDir Path root) {
    var store = new ImageAssetStore(root.toString());
    var stored = store.store("run-1", "shot-01", "image/png", PNG_BYTES);
    assertEquals("run-1/shot-01.png", stored.relativePath());
    assertArrayEquals(PNG_BYTES, store.read(stored.relativePath()));
    assertThrows(IllegalArgumentException.class, () -> store.read("../secret"));
}
```

- [ ] **Step 2: Run test and verify RED**

Run `-Dtest=ImageAssetStoreTest`. Expected: missing class.

- [ ] **Step 3: Implement entities and storage**

Persist the spec fields. `ImageRun` stores `storyRunId`, story/word/grade/style/flow snapshots, status, expected/generated image counts, total text tokens, error, and timestamps. `ImageRunStep` stores raw and parsed output. `ImageShot` stores stable shot key/order/source/dialogue/caption/prompt/reference-key JSON. `ImageAsset` stores type, asset/shot key, relative path, MIME, dimensions, SHA-256, Provider/model/request ID, prompt, negative prompt, and metadata JSON.

Configure:

```yaml
image-story:
  storage-root: ${IMAGE_STORY_STORAGE_ROOT:./runtime/image-story}
```

Add the same variable, without secrets, to `.env.local.example`. Resolve/normalize every path and require `resolved.startsWith(root)`.

`ImageRunRecoveryInitializer` is an `ApplicationRunner`. On startup it loads only `QUEUED`, `PLANNING`, `GENERATING_REFERENCES`, `GENERATING_SHOTS`, and `COMPOSITING` records, marks them `FAILED` with `应用重启，图片批次无法继续`, sets `finishedAt`, and leaves `COMPLETED`/`FAILED` untouched. Its test must prove both sides of this boundary.

- [ ] **Step 4: Run tests and commit**

Run `-Dtest=ImageAssetStoreTest,ImageRunRecoveryInitializerTest` and `git diff --check`. Commit:

```bash
git commit -m "feat: persist image runs and assets"
```

## Task 5: Structured Agent output contracts and validation

**Files:**
- Create: `src/main/java/com/aitaskcenter/service/ImageStructuredOutputParser.java`
- Create: `src/test/java/com/aitaskcenter/service/ImageStructuredOutputParserTest.java`

- [ ] **Step 1: Write parser RED tests**

Fixtures must cover every schema and cross-reference rule, not just JSON syntax.

```java
@Test
void validatesFinalStoryboardCoverageAndLimits() {
    FinalStoryboard valid = parser.finalStoryboard(framedJson("FINAL_STORYBOARD", validStoryboardJson()));
    assertEquals(7, valid.shots().size());

    assertMessage("每个 Scene 最多 5 个分镜",
            () -> parser.finalStoryboard(framedJson("FINAL_STORYBOARD", sixShotsInOneScene())));
    assertMessage("分镜总数最多 20 个",
            () -> parser.finalStoryboard(framedJson("FINAL_STORYBOARD", twentyOneShots())));
    assertMessage("故事场景未完整覆盖",
            () -> parser.validateCoverage(analysisWithFourScenes(), storyboardWithThreeScenes()));
}

@Test
void rejectsImageTextAndBrokenReferences() {
    assertMessage("图片提示词不得要求模型绘制文字",
            () -> parser.preflight(planWithPrompt("add the words HELLO in the image")));
    assertMessage("分镜引用了未知参考资产",
            () -> parser.validateReferences(planWithReference("missing"), referencePlan()));
}
```

- [ ] **Step 2: Run and verify RED**

Run `-Dtest=ImageStructuredOutputParserTest`. Expected: missing parser and contract records.

- [ ] **Step 3: Implement the parser**

Define nested immutable records for the eight spec contracts. Extract exactly one standalone block named `<SCHEMA>_JSON_BEGIN` / `<SCHEMA>_JSON_END`, preserve raw output outside it, parse with Jackson, and reject missing/duplicate markers. Validate duplicate keys, unknown characters/locations/references, Scene coverage, shot order, 1–5 per Scene, max 20, one temporal beat per shot, dialogue speaker/anchor, caption maximum 180 characters, and prompt phrases that instruct the model to render text.

- [ ] **Step 4: Run tests and commit**

Run focused tests, then commit:

```bash
git commit -m "feat: validate image planning contracts"
```

## Task 6: Nine-Agent planning orchestration

**Files:**
- Create: `src/main/java/com/aitaskcenter/config/ImageRunExecutorConfig.java`
- Create: `src/main/java/com/aitaskcenter/dto/ImageRunDtos.java`
- Create: `src/main/java/com/aitaskcenter/service/ImageRunExecutionService.java`
- Create: `src/test/java/com/aitaskcenter/service/ImageRunExecutionServiceTest.java`

- [ ] **Step 1: Write orchestration RED tests**

Use synchronous executors for deterministic tests and a real pool/latches for the two parallel-stage tests. Assert order, prompt versions/providers, raw output preservation, parsed downstream inputs, failure behavior, and token accounting.

```java
@Test
void plansWithNineAgentsInFixedDependencyOrder() {
    service.createRun(new StartImageRunRequest("story-run-1", 7L));
    assertEquals(List.of(
            "image-story-analyst", "image-continuity-designer", "image-art-director",
            "image-action-storyboarder", "image-learning-storyboarder",
            "image-storyboard-director", "image-reference-planner",
            "image-shot-prompt-engineer", "image-prompt-preflight"), savedAgentKeys());
    assertTrue(inputFor("image-storyboard-director").contains("actionStoryboard"));
    assertTrue(inputFor("image-storyboard-director").contains("learningStoryboard"));
}

@Test
void failsWithoutCallingImagesWhenAContractIsInvalid() {
    generationOutputs.replace("image-storyboard-director", "not-json");
    service.createRun(new StartImageRunRequest("story-run-1", 7L));
    assertEquals("FAILED", persistedRun.getStatus());
    verifyNoInteractions(imageGenerationService);
}
```

- [ ] **Step 2: Run and verify RED**

Run `-Dtest=ImageRunExecutionServiceTest`. Expected: missing executor/DTO/service symbols.

- [ ] **Step 3: Implement run creation and planning**

`createRun` must load a story with non-empty `finalStory`, an enabled style, all enabled/valid text Agents, and a valid image flow/provider; serialize snapshots before enqueueing. Catch `RejectedExecutionException` and persist `FAILED` rather than leaving `QUEUED`.

The executor transitions `QUEUED -> PLANNING`, runs Agents 1–3 with `CompletableFuture` on `imagePlanningExecutor`, then 4–5 in parallel, then 6–9 in order. For every call, persist input JSON before invocation and save raw/parsed output, usage, duration, Provider/model, and status. A structured failure marks the step and run `FAILED` and stops all later work.

- [ ] **Step 4: Run tests and commit**

Run focused tests and commit:

```bash
git commit -m "feat: orchestrate image planning agents"
```

## Task 7: Reference generation, shot generation, and deterministic text composition

**Files:**
- Create: `src/main/java/com/aitaskcenter/service/ImageTextCompositor.java`
- Create: `src/test/java/com/aitaskcenter/service/ImageTextCompositorTest.java`
- Modify: `src/main/java/com/aitaskcenter/service/ImageRunExecutionService.java`
- Modify: `src/test/java/com/aitaskcenter/service/ImageRunExecutionServiceTest.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Write compositor and image-pipeline RED tests**

```java
@Test
void composesSpeechBubbleAndBottomCaptionOnSixteenByNineImage() {
    var result = compositor.compose(basePng(1536, 864), List.of(
            dialogue("Toby", "No! That is my cake!", .62, .18),
            narration("The elephant lifts the cakes high.")));
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(result));
    assertEquals(1536, image.getWidth());
    assertEquals(864, image.getHeight());
    assertFalse(Arrays.equals(basePng(1536, 864), result));
}

@Test
void generatesEachReferenceBeforeExactlyOneCallPerShot() {
    service.execute(persistedRun);
    InOrder order = inOrder(imageGenerationService);
    order.verify(imageGenerationService, times(referenceCount)).generate(any(), any(), any(), anyInt(), anyInt(), eq(List.of()));
    order.verify(imageGenerationService, times(shotCount)).generate(any(), any(), any(), anyInt(), anyInt(), argThat(refs -> !refs.isEmpty()));
    verify(imageGenerationService, times(referenceCount + shotCount)).generate(any(), any(), any(), anyInt(), anyInt(), anyList());
}
```

- [ ] **Step 2: Run and verify RED**

Run `-Dtest=ImageTextCompositorTest,ImageRunExecutionServiceTest`. Expected: missing compositor and image stages.

- [ ] **Step 3: Implement Java2D composition**

`ImageTextCompositor` uses Java logical font `Font.SANS_SERIF`, wraps text to safe widths, enforces a minimum 28px font, draws rounded white speech bubbles with dark text and a pointer toward normalized anchors, and draws narration in a bottom semi-opaque safe-area bar. Reject overflow instead of silently clipping; do not download or add a font binary in this task.

- [ ] **Step 4: Implement image stages**

Transition through `GENERATING_REFERENCES`, `GENERATING_SHOTS`, and `COMPOSITING`. Generate character references first, then locations. For each shot resolve all declared asset keys, call the image service once, store the base asset, compose text, store the final asset, and update counts. Any failure marks run `FAILED`, keeps completed assets, and performs no retry. Successful completion sets `COMPLETED` and `finishedAt`.

- [ ] **Step 5: Run tests and commit**

Run focused tests and full backend tests. Commit:

```bash
git commit -m "feat: generate illustrated story assets"
```

## Task 8: Run query, source-story, and asset content APIs

**Files:**
- Create: `src/main/java/com/aitaskcenter/service/ImageRunQueryService.java`
- Create: `src/main/java/com/aitaskcenter/controller/ImageRunController.java`
- Create: `src/main/java/com/aitaskcenter/controller/ImageAssetController.java`
- Create: `src/test/java/com/aitaskcenter/service/ImageRunQueryServiceTest.java`
- Create: `src/test/java/com/aitaskcenter/controller/ImageRunControllerTest.java`
- Create: `src/test/java/com/aitaskcenter/controller/ImageAssetControllerTest.java`

- [ ] **Step 1: Write query/controller RED tests**

Assert source stories include `COMPLETED` and `LIMIT_REACHED` story runs when `finalStory` is non-empty, summaries sort newest first, details sort steps/shots/assets deterministically, and asset content returns MIME, ETag, immutable cache headers, bytes, and 404 for unknown IDs.

```java
mockMvc.perform(get("/api/image-assets/42/content"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"))
        .andExpect(header().string("ETag", "\"sha256-value\""))
        .andExpect(content().bytes(PNG_BYTES));
```

- [ ] **Step 2: Run and verify RED**

Run the three focused test classes. Expected: missing classes/routes.

- [ ] **Step 3: Implement projections and routes**

Implement the exact API table from the design. `POST /api/image-runs` returns the queued snapshot and delegates once. Asset controller accepts only numeric asset ID and uses stored relative paths. Never include API keys, absolute file paths, or raw Provider response headers.

- [ ] **Step 4: Run tests and commit**

Run focused and full backend tests, then commit:

```bash
git commit -m "feat: expose image run history API"
```

## Task 9: Frontend contracts, API calls, first-level menu, and dirty guard

**Files:**
- Create: `web-react/src/image-story-types.ts`
- Create: `web-react/src/ImageAgentFlowPage.tsx`
- Create: `web-react/src/ImageAgentFlowPage.test.tsx`
- Modify: `web-react/src/api.ts`
- Modify: `web-react/src/App.tsx`
- Modify: `web-react/src/App.test.tsx`
- Modify: `web-react/src/styles.css`

- [ ] **Step 1: Write navigation/API contract RED tests**

Mock `ImageAgentFlowPage` with a controllable dirty callback. Extend `App.test.tsx` to assert four primary menu items in exact order, “图片工作台” immediately after “Agent 工作台”, page entry despite unrelated database failure, and cancel/confirm dirty-leave behavior independent from story Agent dirty state.

```tsx
expect(within(navigation).getAllByRole('menuitem').map(item => item.textContent)).toEqual([
  expect.stringContaining('配置管理'),
  expect.stringContaining('去重单词表'),
  expect.stringContaining('Agent 工作台'),
  expect.stringContaining('图片工作台'),
]);
```

- [ ] **Step 2: Run and verify RED**

Run:

```bash
npm --prefix web-react test -- App.test.tsx ImageAgentFlowPage.test.tsx
```

Expected: image menu/page/API symbols are absent.

- [ ] **Step 3: Define exact TypeScript contracts and API wrappers**

Mirror every nullable backend field with `T | null`, not optional missing keys. Add wrappers for all configuration/style/run/asset endpoints. `imageAssetUrl(assetId)` must build from the same Axios base and URL-encode the ID.

- [ ] **Step 4: Add navigation shell and dirty guard**

Add workspace section `image-agents`, menu label/full/mobile short label, `PictureOutlined` icon, image workspace CSS class, and an independent `imageAgentDirty`/modal guard. Render `ImageAgentFlowPage providers={ai.providers}` before global config loading/error branches, matching the story workbench isolation behavior.

- [ ] **Step 5: Run tests and commit**

Run focused tests, full frontend tests, and build. Commit:

```bash
git commit -m "feat: add image workbench navigation"
```

## Task 10: Image Agent configuration, style/model tabs, and start flow

**Files:**
- Modify: `web-react/src/ImageAgentFlowPage.tsx`
- Modify: `web-react/src/ImageAgentFlowPage.test.tsx`
- Modify: `web-react/src/styles.css`

- [ ] **Step 1: Write behavior RED tests**

Cover fixed flow rendering, Agent selection, text Provider filtering, invalid saved Provider display, Prompt trim/temperature/save payload, late-save race protection, versions/restore concurrency, style create/edit/disable, image Provider filtering by both capabilities, fixed 1536x864/5/20 fields, source-story/style selection, story preview, double-submit prevention, and run creation opening history.

```tsx
it('creates one image run from a story snapshot and style', async () => {
  await user.click(screen.getByRole('button', { name: '开始生成' }));
  await user.selectOptions(screen.getByLabelText('故事批次'), 'story-run-1');
  await user.selectOptions(screen.getByLabelText('画风预设'), 'watercolor');
  expect(screen.getByText('16:9 · 每个 Scene 1–5 张 · 最多 20 张')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: '创建图片批次' }));
  expect(apiMocks.createImageRun).toHaveBeenCalledWith({ storyRunId: 'story-run-1', stylePresetId: 7 });
});
```

- [ ] **Step 2: Run and verify RED**

Run `npm --prefix web-react test -- ImageAgentFlowPage.test.tsx`. Expected: behavior missing from shell.

- [ ] **Step 3: Implement the three tabs and start modal**

Reuse proven story workbench interaction patterns but keep image state isolated. The flow is fixed/clickable; editor is inline. Style presets use a list and form with optimistic timestamps. Image model tab shows only enabled Providers with both image capabilities and a blocking empty state. Start modal lists only source stories and enabled styles, shows final story preview, and submits once.

- [ ] **Step 4: Implement responsive styles and run tests**

Prefix new selectors with `.image-story-`. At `<=1100px`, place editor before the long flow and scroll selected Agent into view. At mobile width, stack style/model forms. Run focused/full tests and build.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: configure image story workflow"
```

## Task 11: Full-screen image run audit and gallery

**Files:**
- Create: `web-react/src/ImageRunHistory.tsx`
- Create: `web-react/src/ImageRunHistory.test.tsx`
- Modify: `web-react/src/ImageAgentFlowPage.tsx`
- Modify: `web-react/src/styles.css`

- [ ] **Step 1: Write history RED tests**

Cover newest-first batches, active polling/terminal stop, run-switch stale response protection, actual step order, raw input/output, reference/final gallery tabs, shot source/caption/dialogue/prompt, image preview, missing/failed assets, and absence of scoring/redraw/review controls.

```tsx
it('shows reference assets and final shots without redraw controls', async () => {
  render(<ImageRunHistory open initialRunId="image-run-1" onClose={vi.fn()} />);
  expect(await screen.findByRole('img', { name: 'Toby 角色设定图' })).toHaveAttribute('src', expect.stringContaining('/api/image-assets/'));
  await user.click(screen.getByRole('tab', { name: '最终分镜图' }));
  expect(screen.getByText('Scene 2 · Shot 3')).toBeInTheDocument();
  expect(screen.getByText('The elephant lifts the cakes high.')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /重绘|重新生成|评分|审核/ })).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Run and verify RED**

Run `npm --prefix web-react test -- ImageRunHistory.test.tsx`. Expected: component missing.

- [ ] **Step 3: Implement full-screen audit**

Use a fixed full-viewport overlay. The upper audit region contains batch list, actual step list, and side-by-side full input/output. The lower region defaults to final-shot gallery and can switch to references. Cards show Scene/shot/source/text/prompt; clicking opens a large image modal. Use request IDs/abort logic so late responses from a prior batch cannot overwrite the selected run.

- [ ] **Step 4: Run full frontend verification and commit**

Run focused tests, `npm --prefix web-react test -- --run`, and `npm --prefix web-react run build`; only the existing chunk-size warning is acceptable. Commit:

```bash
git commit -m "feat: audit generated story images"
```

## Task 12: Documentation, deployment, and acceptance

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/backend/java_server/AGENTS.md`
- Modify: `docs/frontend/web_react/AGENTS.md`
- Modify: `docs/shared/system-overview.md`
- Modify: `docs/shared/runtime-deployment-map.md`
- Modify: `docs/chains/README.md`
- Create: `docs/chains/image-story-generation.md`
- Modify: `.env.local.example`

- [ ] **Step 1: Update current-fact documentation**

Document the new menu, nine Agents, tables, APIs, OpenAI Images adapter, file root, fixed limits, no-review/no-redraw boundary, runtime state machine, and secrets boundary. Add the new chain document under the `## 下级文档` table.

- [ ] **Step 2: Run the completion audit locally**

Run:

```bash
JAVA_HOME=/Users/conchi/Library/Java/JavaVirtualMachines/graalvm-ce-21.0.2/Contents/Home \
  /opt/homebrew/bin/mvn -B -ntp test
npm --prefix web-react test -- --run
npm --prefix web-react run build
git diff --check
git status --short
```

Expected: all backend/frontend tests pass, build succeeds with at most the existing Vite chunk warning, diff check is clean, and only intended documentation changes remain before the final docs commit.

- [ ] **Step 3: Commit documentation**

```bash
git add AGENTS.md docs/backend/java_server/AGENTS.md docs/frontend/web_react/AGENTS.md \
  docs/shared/system-overview.md docs/shared/runtime-deployment-map.md \
  docs/chains/README.md docs/chains/image-story-generation.md .env.local.example
git commit -m "docs: document image story generation"
```

- [ ] **Step 4: Independent review**

Request a specification and code-quality review against `docs/superpowers/specs/2026-08-15-image-story-workbench-design.md`. Resolve every Critical/Important finding with RED/GREEN regression evidence and a separate commit. Repeat the full verification after the final fix.

- [ ] **Step 5: Deploy through Context Router**

Reuse Context Router `task_id = 597`. Call `apply_workspace_changes` once with every actual Workspace-relative changed file, then poll `get_workspace_operation` until `succeeded`. Report the failing step and bounded logs if it reaches any other terminal state.

- [ ] **Step 6: Runtime acceptance without accidental image spend**

First verify the deployed configuration API shows the nine Agents, fixed constraints, style presets, and an explicit blocking state when no image Provider exists. Then add/enable an authorized OpenAI Images-compatible Provider with both capabilities. Run one approved existing story batch and verify 1–5 shots per Scene, <=20 shots, reference assets before shots, one image call per shot, correct bubbles/captions, full Agent audit, and no redraw controls. Do not launch a real image batch until the user has authorized the configured image Provider and expected image count.

---

## Final completion evidence

The feature is complete only when all of the following are directly proven:

1. The first-level menu order includes “图片工作台” immediately after “Agent 工作台”.
2. All nine editable Agents, three program nodes, styles, image Provider, version history, and dirty guards work in the UI and API.
3. A source story snapshot produces 1–5 shots per Scene, at most 20 total, with character/location references generated first.
4. Every shot makes exactly one real image request and uses declared reference assets.
5. Final images are 16:9, image-model output contains no requested text, and deterministic composition renders dialogue bubbles and bottom narration.
6. Run history exposes each Agent input/raw output, references, source text, prompts, and final images, with no visual-review or redraw path.
7. Backend/full frontend tests, production build, independent review, Context Router deployment, and one user-authorized real image batch all succeed.
