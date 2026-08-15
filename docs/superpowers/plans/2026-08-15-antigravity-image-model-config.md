# Antigravity Image Model Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dedicated multi-provider image-model configuration page, securely bootstrap `gemini-3-pro-image` from an existing Antigravity credential, and make the image adapter compatible with Antigravity multi-reference and non-exact 16:9 output.

**Architecture:** Keep `AiConfig` as the only Provider store. A narrowly scoped bootstrap endpoint clones Base URL/API Key inside the backend without returning the secret; the React image-model page edits only image-capability Providers while preserving all text Providers. `AiImageGenerationService` uses Antigravity-compatible multipart names and normalizes validated output to the requested pixel dimensions before assets are persisted.

**Tech Stack:** Java 17, Spring Boot 3.3, Jackson, Java HTTP Client, Java2D/ImageIO, JUnit 5, Mockito, React 19, TypeScript, Ant Design, Axios, Vitest, Testing Library.

---

## File map

- Modify `src/main/java/com/aitaskcenter/service/AiImageGenerationService.java`: multipart reference naming and safe output normalization.
- Modify `src/test/java/com/aitaskcenter/service/AiImageGenerationServiceTest.java`: Antigravity multipart and dimension regressions.
- Create `src/main/java/com/aitaskcenter/dto/ImageProviderBootstrapRequest.java`: source Provider ID request contract.
- Modify `src/main/java/com/aitaskcenter/service/AiConfigService.java`: credential-safe fixed Antigravity image Provider bootstrap.
- Modify `src/main/java/com/aitaskcenter/controller/AiConfigController.java`: bootstrap HTTP route.
- Create `src/test/java/com/aitaskcenter/service/AiConfigServiceTest.java`: persistence, conflict, policy, and secret tests.
- Create `src/test/java/com/aitaskcenter/controller/AiConfigControllerTest.java`: route and redacted response contract.
- Create `web-react/src/image-provider-policy.ts`: frontend image classification and execution-eligibility mirror.
- Modify `web-react/src/ImageAgentFlowPage.tsx`: consume the shared frontend policy.
- Create `web-react/src/ImageModelConfigPage.tsx`: dedicated multi-image-Provider editor.
- Create `web-react/src/ImageModelConfigPage.test.tsx`: page behavior and secret-safe bootstrap tests.
- Modify `web-react/src/api.ts`: bootstrap request/response API.
- Modify `web-react/src/api.image-story.test.ts`: bootstrap route contract.
- Modify `web-react/src/App.tsx`: fourth configuration menu row, shared Provider state, and automatic valid flow selection.
- Modify `web-react/src/App.test.tsx`: navigation order and page/workbench propagation.
- Modify `web-react/src/styles.css`: scoped image-model page details only if existing editor styles are insufficient.
- Modify `docs/frontend/web_react/AGENTS.md`, `docs/backend/java_server/AGENTS.md`, and `docs/chains/image-story-generation.md`: current menu, bootstrap, and adapter facts.

### Task 1: Make the image adapter Antigravity-compatible

**Files:**
- Modify: `src/test/java/com/aitaskcenter/service/AiImageGenerationServiceTest.java`
- Modify: `src/main/java/com/aitaskcenter/service/AiImageGenerationService.java`

- [ ] **Step 1: Change the multipart regression to require distinct field names**

Replace the two-reference expectation with assertions that inspect the real multipart body:

```java
assertEquals(1, occurrences(body, "name=\"image\""));
assertEquals(1, occurrences(body, "name=\"image1\""));
assertTrue(body.indexOf("name=\"image\"") < body.indexOf("name=\"image1\""));
assertTrue(body.contains("filename=\"first.png\""));
assertTrue(body.contains("filename=\"second.png\""));
```

Add a three-reference assertion for `image2` so numbering cannot regress after the second item.

- [ ] **Step 2: Add output-normalization regressions**

Add tests that return a generated `4x3` PNG while requesting `6x3`, then verify:

```java
ImageResult result = service.generate(provider(baseUrl()), "wide scene", "", 6, 3, List.of());
assertEquals(6, result.width());
assertEquals(3, result.height());
assertEquals("image/png", result.mimeType());
BufferedImage normalized = ImageIO.read(new ByteArrayInputStream(result.bytes()));
assertEquals(6, normalized.getWidth());
assertEquals(3, normalized.getHeight());
```

Keep the existing exact-size test and assert `assertArrayEquals(generatedImage, result.bytes())` to prove exact results are not re-encoded. Add a tall-image case with distinct colored bands so the test proves center crop rather than stretch.

- [ ] **Step 3: Run the focused test and verify RED**

Run:

```bash
mvn -B -ntp -Dtest=AiImageGenerationServiceTest test
```

Expected: FAIL because both reference parts are named `image`, and the returned dimensions remain the Provider dimensions.

- [ ] **Step 4: Number multipart image fields**

Change the loop and helper signature:

```java
for (int index = 0; index < references.size(); index++) {
    writeImagePart(body, boundary, index == 0 ? "image" : "image" + index, references.get(index));
}

private void writeImagePart(ByteArrayOutputStream body, String boundary, String fieldName,
                            ImageReference reference) throws IOException {
    body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fieldName
            + "\"; filename=\"" + safeFilename(reference.filename())
            + "\"\r\nContent-Type: " + reference.mimeType() + "\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.write(reference.bytes());
    body.write("\r\n".getBytes(StandardCharsets.UTF_8));
}
```

The field name is generated internally and never accepts external text.

- [ ] **Step 5: Normalize decoded output after all existing safety checks**

Add a bounded helper that returns the original result when dimensions match, otherwise center-crops and resizes with Java2D:

```java
private ImageResult normalizeDimensions(ImageResult source, int requestedWidth, int requestedHeight) {
    int width = requestedWidth > 0 ? requestedWidth : DEFAULT_WIDTH;
    int height = requestedHeight > 0 ? requestedHeight : DEFAULT_HEIGHT;
    if (source.width() == width && source.height() == height) return source;
    BufferedImage decoded = readValidatedImage(source.bytes());
    double targetRatio = (double) width / height;
    int cropWidth = decoded.getWidth();
    int cropHeight = decoded.getHeight();
    if ((double) cropWidth / cropHeight > targetRatio) cropWidth = (int) Math.round(cropHeight * targetRatio);
    else cropHeight = (int) Math.round(cropWidth / targetRatio);
    int cropX = (decoded.getWidth() - cropWidth) / 2;
    int cropY = (decoded.getHeight() - cropHeight) / 2;
    BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = output.createGraphics();
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(decoded, 0, 0, width, height,
                cropX, cropY, cropX + cropWidth, cropY + cropHeight, null);
    } finally {
        graphics.dispose();
    }
    byte[] png = encodePng(output);
    return new ImageResult(png, "image/png", width, height, source.providerRequestId(), source.metadata());
}
```

Call this after response decoding and before returning from `generate`. Reuse the service's byte/pixel limits when reading and encoding; reject an encoding failure with the static Chinese error `图片尺寸归一化失败`.

- [ ] **Step 6: Run focused and full backend tests**

Run:

```bash
mvn -B -ntp -Dtest=AiImageGenerationServiceTest test
mvn -B -ntp test
```

Expected: focused tests PASS; full backend suite PASS with the existing single conditional skip only.

- [ ] **Step 7: Commit adapter compatibility**

```bash
git add src/main/java/com/aitaskcenter/service/AiImageGenerationService.java \
  src/test/java/com/aitaskcenter/service/AiImageGenerationServiceTest.java
git commit -m "fix: support antigravity image responses"
```

### Task 2: Add secret-safe Antigravity Provider bootstrap

**Files:**
- Create: `src/main/java/com/aitaskcenter/dto/ImageProviderBootstrapRequest.java`
- Modify: `src/main/java/com/aitaskcenter/service/AiConfigService.java`
- Modify: `src/main/java/com/aitaskcenter/controller/AiConfigController.java`
- Create: `src/test/java/com/aitaskcenter/service/AiConfigServiceTest.java`
- Create: `src/test/java/com/aitaskcenter/controller/AiConfigControllerTest.java`

- [ ] **Step 1: Write service RED tests around a real serialized config entity**

Use a mocked `AiConfigRepository`, a real `ObjectMapper`, and an `AiConfig` whose Provider JSON contains source API key `local-secret-never-return`. Test this public API:

```java
AiConfigRequest result = service.bootstrapAntigravityImageProvider(
        new ImageProviderBootstrapRequest("antigravity-gemini-3-1-pro"));
```

Assert the saved JSON contains target values:

```java
assertEquals("antigravity-gemini-image", target.getId());
assertEquals("Antigravity Gemini Image", target.getLabel());
assertEquals("gemini-3-pro-image", target.getModel());
assertEquals(List.of("IMAGE_GENERATION", "IMAGE_REFERENCE"), target.getCapabilities());
assertEquals(Map.of("responseFormat", "b64_json", "quality", "hd", "size", "1536x864"), target.getOptions());
assertEquals("local-secret-never-return", persistedTarget.getApiKey());
assertNull(result.getProviders().stream().filter(p -> p.getId().equals(target.getId())).findFirst().orElseThrow().getApiKey());
```

Add separate tests for missing source, disabled/non-OpenAI source, missing source key, and existing target. In every rejection assert `verify(repository, never()).save(any())` and assert the exception message excludes the secret and source URL.

- [ ] **Step 2: Write controller RED tests**

Using `@WebMvcTest(AiConfigController.class)`, post:

```json
{"sourceProviderId":"antigravity-gemini-3-1-pro"}
```

to `/api/ai/config/image/bootstrap`. Assert HTTP success wrapper data contains `antigravity-gemini-image` and `api_key` is null. Assert a null/blank body produces a bounded validation error without a Java exception or secret.

- [ ] **Step 3: Run service/controller tests and verify RED**

Run:

```bash
mvn -B -ntp -Dtest=AiConfigServiceTest,AiConfigControllerTest test
```

Expected: test compilation fails because the request DTO, service method, and controller route do not exist.

- [ ] **Step 4: Add the request DTO**

Create:

```java
package com.aitaskcenter.dto;

public record ImageProviderBootstrapRequest(String sourceProviderId) { }
```

- [ ] **Step 5: Implement the fixed bootstrap transaction**

Add constants in `AiConfigService` for the target ID/model/label. Implement one transaction that loads the unredacted source from persisted JSON, validates it, constructs the fixed target, calls `ImageProviderPolicy.requireExecutable(target)`, appends it while preserving `active`, persists through the existing save path, and returns `getProviders()` so API keys are redacted.

The source validation must be explicit:

```java
if (!source.isEnabled()
        || !OPENAI_COMPATIBLE.equals(effectiveProviderProtocol(source))
        || !StringUtils.hasText(source.getBaseUrl())
        || !StringUtils.hasText(source.getApiKey())) {
    throw new IllegalArgumentException("来源 Antigravity Provider 配置不可复用");
}
```

The fixed target must use the source Base URL/API Key but must not inherit its text capabilities, model, options, voice, or default status.

- [ ] **Step 6: Expose the route**

Add to `AiConfigController`:

```java
@PostMapping("/config/image/bootstrap")
public ApiResponse<AiConfigRequest> bootstrapImageProvider(
        @RequestBody(required = false) ImageProviderBootstrapRequest request) {
    return ApiResponse.ok(service.bootstrapAntigravityImageProvider(request), "图片模型配置已创建");
}
```

Pass null to the service so it returns a controlled validation error rather than dereferencing the body.

- [ ] **Step 7: Run focused and full backend tests**

Run:

```bash
mvn -B -ntp -Dtest=AiConfigServiceTest,AiConfigControllerTest,ImageProviderPolicyTest test
mvn -B -ntp test
```

Expected: all focused and full tests PASS; serialized HTTP output does not contain `local-secret-never-return`.

- [ ] **Step 8: Commit bootstrap support**

```bash
git add src/main/java/com/aitaskcenter/dto/ImageProviderBootstrapRequest.java \
  src/main/java/com/aitaskcenter/service/AiConfigService.java \
  src/main/java/com/aitaskcenter/controller/AiConfigController.java \
  src/test/java/com/aitaskcenter/service/AiConfigServiceTest.java \
  src/test/java/com/aitaskcenter/controller/AiConfigControllerTest.java
git commit -m "feat: bootstrap antigravity image provider"
```

### Task 3: Centralize frontend image Provider rules and API contract

**Files:**
- Create: `web-react/src/image-provider-policy.ts`
- Create: `web-react/src/image-provider-policy.test.ts`
- Modify: `web-react/src/ImageAgentFlowPage.tsx`
- Modify: `web-react/src/ImageAgentFlowPage.test.tsx`
- Modify: `web-react/src/api.ts`
- Modify: `web-react/src/api.image-story.test.ts`

- [ ] **Step 1: Add RED tests for classification, eligibility, and bootstrap routing**

Add a test module that verifies:

```ts
expect(isImageProviderConfig(textProvider)).toBe(false);
expect(isImageProviderConfig({ ...textProvider, capabilities: ['IMAGE_GENERATION'] })).toBe(true);
expect(isExecutableImageProvider(validImageProvider)).toBe(true);
expect(isExecutableImageProvider({ ...validImageProvider, options: { quality: 'ultra' } })).toBe(false);
```

In `api.image-story.test.ts`, mock Axios and assert:

```ts
await bootstrapAntigravityImageProvider('antigravity-gemini-3-1-pro');
expect(request.post).toHaveBeenCalledWith('/ai/config/image/bootstrap', {
  sourceProviderId: 'antigravity-gemini-3-1-pro',
});
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
npm --prefix web-react test -- image-provider-policy.test.ts api.image-story.test.ts
```

Expected: FAIL because the shared policy and API function do not exist.

- [ ] **Step 3: Extract the existing policy without changing behavior**

Move the current normalized capability, URL, options, and complete eligibility logic from `ImageAgentFlowPage.tsx` into exports:

```ts
export const isImageProviderConfig = (provider: AIProviderConfigItem) =>
  hasCapability(provider, 'IMAGE_GENERATION') || hasCapability(provider, 'IMAGE_REFERENCE');

export const isExecutableImageProvider = (provider: AIProviderConfigItem) =>
  !!provider.id?.trim()
  && !!provider.model?.trim()
  && !!provider.base_url?.trim()
  && provider.type?.trim().toLowerCase() === 'openai-compatible'
  && provider.enabled !== false
  && hasCapability(provider, 'IMAGE_GENERATION')
  && hasCapability(provider, 'IMAGE_REFERENCE')
  && validImageProviderUrl(provider.base_url)
  && validImageProviderOptions(provider.options);
```

Make `ImageAgentFlowPage` import `isExecutableImageProvider`; keep all existing provider filtering tests green.

- [ ] **Step 4: Add the bootstrap API**

Add:

```ts
export interface ImageProviderBootstrapRequest { sourceProviderId: string; }

export const bootstrapAntigravityImageProvider = (sourceProviderId: string) =>
  request.post<ApiResponse<AIConfig>>('/ai/config/image/bootstrap', { sourceProviderId }).then(unwrap);
```

- [ ] **Step 5: Run focused frontend tests**

Run:

```bash
npm --prefix web-react test -- image-provider-policy.test.ts api.image-story.test.ts ImageAgentFlowPage.test.tsx
```

Expected: all focused tests PASS with no unhandled promise rejections.

- [ ] **Step 6: Commit the shared policy and route contract**

```bash
git add web-react/src/image-provider-policy.ts web-react/src/image-provider-policy.test.ts \
  web-react/src/ImageAgentFlowPage.tsx web-react/src/ImageAgentFlowPage.test.tsx \
  web-react/src/api.ts web-react/src/api.image-story.test.ts
git commit -m "feat: expose image provider bootstrap api"
```

### Task 4: Build the dedicated multi-model configuration page

**Files:**
- Create: `web-react/src/ImageModelConfigPage.tsx`
- Create: `web-react/src/ImageModelConfigPage.test.tsx`
- Modify: `web-react/src/styles.css`

- [ ] **Step 1: Write page RED tests**

Render the page with one text Provider and two image Providers. Cover these behaviors as independent tests:

1. The list shows only image-capability Providers; the text Provider is absent.
2. “添加图片模型” creates a draft with ID `antigravity-gemini-image`, model `gemini-3-pro-image`, `hd`, `b64_json`, and `1536x864`.
3. A conflicting ID becomes `antigravity-gemini-image-2` without changing existing Providers.
4. Editing quality/model/enabled calls `onChange` with the full AI config, including untouched text Providers.
5. Delete removes only the selected image Provider after confirmation.
6. Save failure keeps the draft and “有未保存更改”; success clears it.
7. Bootstrap source options include enabled OpenAI-compatible Antigravity Providers and do not display or submit API keys.
8. Bootstrap success calls `onBootstrap(sourceId)` once and renders the returned target.

Use a prop contract that keeps persistence outside the component:

```ts
interface ImageModelConfigPageProps {
  config: AIConfig;
  saving: boolean;
  onChange: (next: AIConfig) => void;
  onSave: (next: AIConfig) => Promise<void>;
  onBootstrap: (sourceProviderId: string) => Promise<void>;
}
```

- [ ] **Step 2: Run the component test and verify RED**

Run:

```bash
npm --prefix web-react test -- ImageModelConfigPage.test.tsx
```

Expected: test compilation fails because `ImageModelConfigPage` does not exist.

- [ ] **Step 3: Implement the minimum page**

Use existing Ant Design `Form`, `Select`, `Switch`, `Input.Password`, list, confirmation, and editor styles. Build image drafts with a fixed helper:

```ts
const newImageProvider = (providers: AIProviderConfigItem[]): AIProviderConfigItem => ({
  id: nextImageProviderId(providers),
  label: 'Antigravity Gemini Image',
  type: 'openai-compatible',
  base_url: '',
  api_key: '',
  model: 'gemini-3-pro-image',
  max_tokens: 4096,
  capabilities: ['IMAGE_GENERATION', 'IMAGE_REFERENCE'],
  options: { responseFormat: 'b64_json', quality: 'hd', size: '1536x864' },
  enabled: true,
});
```

Before `onSave`, trim ID/label/Base URL/model, rebuild the fixed capabilities, and rebuild options from the quality field plus fixed format/size. Reject blank ID/Base URL/model in the form. Do not include `active` controls because the global default text Provider is unrelated to the image selection.

- [ ] **Step 4: Add only scoped CSS needed by the page**

Reuse `.panel-page`, `.page-head`, `.editor-layout`, `.config-list`, `.config-editor`, and `.form-section-title`. Add selectors beginning with `.image-model-` only for the fixed capability/spec summary and bootstrap source row. At the existing narrow breakpoint, ensure the editor appears before the long list or scrolls into view after selection.

- [ ] **Step 5: Run focused tests and build**

Run:

```bash
npm --prefix web-react test -- ImageModelConfigPage.test.tsx
npm --prefix web-react run build
```

Expected: component tests PASS and `tsc -b && vite build` succeeds with only the existing chunk-size warning.

- [ ] **Step 6: Commit the page**

```bash
git add web-react/src/ImageModelConfigPage.tsx web-react/src/ImageModelConfigPage.test.tsx web-react/src/styles.css
git commit -m "feat: add image model configuration page"
```

### Task 5: Integrate navigation, persistence, and default flow selection

**Files:**
- Modify: `web-react/src/App.tsx`
- Modify: `web-react/src/App.test.tsx`

- [ ] **Step 1: Add navigation and integration RED tests**

Extend the configuration test setup with a valid text source and mock the bootstrap, image flow, and flow update APIs. Assert configuration menu items are exactly:

```ts
['数据库配置', 'AI 配置', '本地 CLI 配置', '图片模型配置']
```

Click “图片模型配置” and assert the page heading. Bootstrap and verify:

```ts
expect(apiMocks.bootstrapAntigravityImageProvider)
  .toHaveBeenCalledWith('antigravity-gemini-3-1-pro');
expect(apiMocks.updateImageFlowConfig).toHaveBeenCalledWith({
  imageProviderId: 'antigravity-gemini-image',
  width: 1536,
  height: 864,
  maxShotsPerScene: 5,
  maxShotsPerStory: 20,
  updatedAt: '2026-08-15T01:00:00Z',
});
```

Add a second test where the current flow already references another executable image Provider and assert `updateImageFlowConfig` is not called. Navigate to the image workbench and assert the newly bootstrapped Provider is passed in props.

- [ ] **Step 2: Run App tests and verify RED**

Run:

```bash
npm --prefix web-react test -- App.test.tsx
```

Expected: FAIL because the fourth configuration entry and page integration do not exist.

- [ ] **Step 3: Add the fourth configuration tab and render the page**

Extend:

```ts
type ConfigTab = 'database' | 'ai' | 'cli' | 'image-model';
```

Append a `PictureOutlined` “图片模型配置” navigation item after CLI. Render `ImageModelConfigPage` for that tab and keep the global `ai` state as the single state passed to Agent workbenches.

- [ ] **Step 4: Implement save and bootstrap handlers without secret exposure**

For ordinary save, call `saveAIConfig(next)`, clear the dedicated dirty state only after success, then call `reload()` so the backend-redacted state replaces form secrets.

For bootstrap:

```ts
const updated = await bootstrapAntigravityImageProvider(sourceProviderId);
setAi(updated);
const flow = await getImageAgentFlow();
const current = updated.providers.find((provider) => provider.id === flow.config.imageProviderId);
if (!current || !isExecutableImageProvider(current)) {
  await updateImageFlowConfig({
    imageProviderId: 'antigravity-gemini-image',
    width: flow.config.width,
    height: flow.config.height,
    maxShotsPerScene: flow.config.maxShotsPerScene,
    maxShotsPerStory: flow.config.maxShotsPerStory,
    updatedAt: flow.config.updatedAt,
  });
}
```

If the flow update fails after bootstrap succeeds, show a bounded warning that the Provider was created but must be selected in the image workbench; do not delete the successfully created Provider.

- [ ] **Step 5: Run focused, full frontend, and build verification**

Run:

```bash
npm --prefix web-react test -- App.test.tsx ImageModelConfigPage.test.tsx ImageAgentFlowPage.test.tsx api.image-story.test.ts image-provider-policy.test.ts
npm --prefix web-react test
npm --prefix web-react run build
```

Expected: focused and full tests PASS; production build succeeds with only the existing chunk-size warning.

- [ ] **Step 6: Commit navigation and integration**

```bash
git add web-react/src/App.tsx web-react/src/App.test.tsx
git commit -m "feat: integrate image model configuration"
```

### Task 6: Document, verify, deploy, and create the live Antigravity image config

**Files:**
- Modify: `docs/frontend/web_react/AGENTS.md`
- Modify: `docs/backend/java_server/AGENTS.md`
- Modify: `docs/chains/image-story-generation.md`

- [ ] **Step 1: Update current-fact documentation**

Document the fourth configuration menu, shared `tb_ai_config` storage, secret-safe bootstrap route, fixed default model/options, multipart field numbering, and center-crop/resize output normalization. State that configuration and bootstrap do not call the image Provider.

- [ ] **Step 2: Run complete fresh verification**

Run:

```bash
mvn -B -ntp clean test
npm --prefix web-react test
npm --prefix web-react run build
git diff --check
git status --short
```

Expected: backend suite PASS with the single existing conditional skip; frontend suite PASS; production build succeeds with only the existing chunk-size warning; diff check is empty; status contains only the three documentation files.

- [ ] **Step 3: Commit documentation**

```bash
git add docs/frontend/web_react/AGENTS.md docs/backend/java_server/AGENTS.md \
  docs/chains/image-story-generation.md
git commit -m "docs: document image model configuration"
```

- [ ] **Step 4: Apply code changes through Context Router**

Reuse prepared Context Router task ID `610`. Call `apply_workspace_changes` with this exact workspace-relative file set, omitting a listed file only when the final implementation did not change it:

```text
src/main/java/com/aitaskcenter/service/AiImageGenerationService.java
src/main/java/com/aitaskcenter/dto/ImageProviderBootstrapRequest.java
src/main/java/com/aitaskcenter/service/AiConfigService.java
src/main/java/com/aitaskcenter/controller/AiConfigController.java
src/test/java/com/aitaskcenter/service/AiImageGenerationServiceTest.java
src/test/java/com/aitaskcenter/service/AiConfigServiceTest.java
src/test/java/com/aitaskcenter/controller/AiConfigControllerTest.java
web-react/src/image-provider-policy.ts
web-react/src/image-provider-policy.test.ts
web-react/src/ImageAgentFlowPage.tsx
web-react/src/ImageAgentFlowPage.test.tsx
web-react/src/ImageModelConfigPage.tsx
web-react/src/ImageModelConfigPage.test.tsx
web-react/src/api.ts
web-react/src/api.image-story.test.ts
web-react/src/App.tsx
web-react/src/App.test.tsx
web-react/src/styles.css
docs/frontend/web_react/AGENTS.md
docs/backend/java_server/AGENTS.md
docs/chains/image-story-generation.md
```

Poll `get_workspace_operation(operation_id)` until `succeeded`, `failed`, `cancelled`, or `interrupted`. Do not report deployment success from a queued/running state.

- [ ] **Step 5: Bootstrap the current Antigravity image Provider without printing secrets**

After deployment succeeds, first GET `/api/ai/config` and select the existing enabled OpenAI-compatible Provider with ID `antigravity-gemini-3-1-pro`. POST only:

```json
{"sourceProviderId":"antigravity-gemini-3-1-pro"}
```

to `/api/ai/config/image/bootstrap`. Never print the raw persisted Provider JSON or Authorization value. If `antigravity-gemini-image` already exists, verify its redacted fields instead of overwriting it.

Then GET `/api/image-agents/flow`. If `imageProviderId` is null or does not reference an executable Provider in the redacted AI config, PUT `/api/image-agents/flow/config` with `antigravity-gemini-image`, the returned fixed width/height/count fields, and the returned `updatedAt`. If the current flow already references an executable image Provider, preserve it.

- [ ] **Step 6: Verify configuration without generating images**

GET `/api/ai/config` and `/api/image-agents/flow`. Assert in a local redacted check that:

```text
target id = antigravity-gemini-image
model = gemini-3-pro-image
capabilities = IMAGE_GENERATION, IMAGE_REFERENCE
responseFormat = b64_json
quality = hd
size = 1536x864
flow imageProviderId = antigravity-gemini-image, unless a previously valid image Provider was intentionally preserved
api_key is absent/null in every HTTP response
```

Do not call `/v1/images/generations`, `/v1/images/edits`, or create an image run. Report that compatibility is code- and configuration-verified but paid generation remains untested until the user authorizes one image.

- [ ] **Step 7: Final repository audit**

Run:

```bash
git log --oneline -8
git status --short
git diff HEAD~6..HEAD --check
```

Expected: implementation commits are present, worktree is clean, and diff check has no output.
