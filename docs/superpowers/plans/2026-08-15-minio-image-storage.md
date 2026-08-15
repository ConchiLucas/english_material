# MinIO Image Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a secure MinIO configuration page and make MinIO the only storage backend for generated image-story assets.

**Architecture:** Persist one redacted, optimistic-lock protected MinIO configuration in PostgreSQL. A focused MinIO client/verifier owns endpoint normalization, private bucket initialization and write/read/delete probes; `ImageAssetStore` keeps its existing business API but stores validated image bytes under controlled MinIO object keys. The browser continues to fetch assets through the existing asset-ID controller, so credentials and MinIO addresses never reach clients.

**Tech Stack:** Java 17, Spring Boot, Spring Data JPA, MinIO Java SDK 9.0.1, JUnit 5/Mockito, React 19, TypeScript, Ant Design, Vitest/Testing Library, Context Router deployment.

---

## File map

**Backend configuration boundary**

- Create `src/main/java/com/aitaskcenter/model/MinioConfig.java`: singleton persisted configuration with `@Version` inherited timestamps.
- Create `src/main/java/com/aitaskcenter/repository/MinioConfigRepository.java`: lookup by fixed config key.
- Create `src/main/java/com/aitaskcenter/dto/MinioConfigRequest.java`: write/test input; secret may be blank to preserve the saved value.
- Create `src/main/java/com/aitaskcenter/dto/MinioConfigView.java`: redacted response with `secretConfigured`, never the secret.
- Create `src/main/java/com/aitaskcenter/service/MinioConfigService.java`: normalize, redact, optimistic concurrency, save/test orchestration.
- Create `src/main/java/com/aitaskcenter/controller/MinioConfigController.java`: `/api/minio/config` routes.

**MinIO storage boundary**

- Create `src/main/java/com/aitaskcenter/service/MinioClientFactory.java`: build clients from validated resolved configuration.
- Create `src/main/java/com/aitaskcenter/service/MinioConnectionVerifier.java`: ensure private bucket and execute bounded probes.
- Rewrite `src/main/java/com/aitaskcenter/service/ImageAssetStore.java`: retain `assertWritable/store/read/delete` signatures but replace filesystem operations with MinIO operations.
- Modify `pom.xml`: add `io.minio:minio:9.0.1`.

**Frontend**

- Modify `web-react/src/api.ts`: exact MinIO DTOs and three request functions.
- Create `web-react/src/MinioConfigPage.tsx`: isolated load/test/save form.
- Modify `web-react/src/App.tsx`: add the fifth config navigation item after image models.
- Modify `web-react/src/styles.css`: scoped MinIO page styling only.

**Deployment and documentation**

- Modify `src/main/resources/application.yml`, `scripts/start-dev.sh`, `.env.local.example`, `deploy/context-router/fast/compose.yml`, `deploy/context-router/full/compose.yml`, and `deploy/context-router/full/Dockerfile.base`: remove the obsolete local image-storage root and volume plumbing.
- Modify `docs/backend/java_server/AGENTS.md`, `docs/frontend/web_react/AGENTS.md`, `docs/shared/runtime-deployment-map.md`, `docs/chains/config-management.md`, and `docs/chains/image-story-generation.md`: make MinIO the current storage fact.

### Task 1: Persist and redact MinIO configuration

**Files:**
- Create: `src/main/java/com/aitaskcenter/model/MinioConfig.java`
- Create: `src/main/java/com/aitaskcenter/repository/MinioConfigRepository.java`
- Create: `src/main/java/com/aitaskcenter/dto/MinioConfigRequest.java`
- Create: `src/main/java/com/aitaskcenter/dto/MinioConfigView.java`
- Create: `src/main/java/com/aitaskcenter/service/MinioConfigService.java`
- Test: `src/test/java/com/aitaskcenter/service/MinioConfigServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Cover defaults, trimming, endpoint rejection, bucket/base-path syntax, secret redaction, blank-secret preservation and stale timestamp rejection. Use the fixed defaults below:

```java
assertEquals("english-material", service.get().bucketName());
assertEquals("image-story", service.get().basePath());
assertFalse(service.get().secretConfigured());
assertNull(service.get().updatedAt());
```

The stale test must assert `repository.saveAndFlush` is never called when request `updatedAt` differs by one microsecond.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `mvn -B -ntp -Dtest=MinioConfigServiceTest test`

Expected: test compilation fails because the MinIO configuration types do not exist.

- [ ] **Step 3: Implement the minimal entity, DTOs and normalization**

Use a singleton key and never place the secret in the response:

```java
@Entity
@Table(name = "tb_minio_config")
public class MinioConfig extends BaseEntity {
    @Column(nullable = false, unique = true, length = 80)
    private String configKey;
    @Column(nullable = false, length = 255)
    private String endpoint;
    @Column(nullable = false, length = 160)
    private String accessKeyId;
    @Column(nullable = false, length = 512)
    private String secretAccessKey;
    private boolean useSsl;
    @Column(nullable = false, length = 63)
    private String bucketName;
    @Column(nullable = false, length = 240)
    private String basePath;
    private boolean enabled;
}
```

`MinioConfigView` fields are exactly `enabled`, `endpoint`, `accessKeyId`, `useSsl`, `bucketName`, `basePath`, `secretConfigured`, `updatedAt`. Reject endpoint schemes, userinfo, slash, query and fragment; accept only a normalized `host:port`. Bucket must follow S3 bucket naming rules; base path is slash-separated safe segments without leading/trailing slash, `.` or `..`.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run: `mvn -B -ntp -Dtest=MinioConfigServiceTest test`

Expected: all MinIO configuration service tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/aitaskcenter/model/MinioConfig.java \
  src/main/java/com/aitaskcenter/repository/MinioConfigRepository.java \
  src/main/java/com/aitaskcenter/dto/MinioConfigRequest.java \
  src/main/java/com/aitaskcenter/dto/MinioConfigView.java \
  src/main/java/com/aitaskcenter/service/MinioConfigService.java \
  src/test/java/com/aitaskcenter/service/MinioConfigServiceTest.java
git commit -m "feat: persist minio configuration"
```

### Task 2: Add the MinIO client, private bucket and connection probe

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/aitaskcenter/service/MinioClientFactory.java`
- Create: `src/main/java/com/aitaskcenter/service/MinioConnectionVerifier.java`
- Modify: `src/main/java/com/aitaskcenter/service/MinioConfigService.java`
- Test: `src/test/java/com/aitaskcenter/service/MinioConnectionVerifierTest.java`
- Test: `src/test/java/com/aitaskcenter/service/MinioConfigServiceTest.java`

- [ ] **Step 1: Add failing verifier tests**

Use a mockable adapter/factory seam and assert:

```java
verify(client).bucketExists(any(BucketExistsArgs.class));
verify(client).makeBucket(any(MakeBucketArgs.class)); // only when absent
verify(client).putObject(any(PutObjectArgs.class));
verify(client).getObject(any(GetObjectArgs.class));
verify(client).removeObject(any(RemoveObjectArgs.class));
verifyNoMoreInteractions(policyClient); // never publish the bucket
```

Add failure cases for unreadable data, failed delete and errors containing a fake secret; the public exception text must not contain the secret, endpoint or SDK response.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `mvn -B -ntp -Dtest=MinioConnectionVerifierTest,MinioConfigServiceTest test`

Expected: compilation fails because the factory/verifier and MinIO dependency are absent.

- [ ] **Step 3: Add SDK 9.0.1 and implement bounded probing**

Build the endpoint as `http[s]://host:port` only inside the factory. Probe key format:

```java
String key = normalized.basePath() + "/.readiness/" + UUID.randomUUID();
byte[] expected = new byte[] {0x45, 0x4d};
```

Create a missing bucket without setting policy, write two bytes, read at most three bytes, compare exactly, and delete in `finally`. Saving calls `verify(request)` before `saveAndFlush`; testing calls the same verifier without persistence. A blank incoming secret resolves from the existing entity before verification.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `mvn -B -ntp -Dtest=MinioConnectionVerifierTest,MinioConfigServiceTest test`

Expected: all tests pass with no credential text in test output.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java/com/aitaskcenter/service/MinioClientFactory.java \
  src/main/java/com/aitaskcenter/service/MinioConnectionVerifier.java \
  src/main/java/com/aitaskcenter/service/MinioConfigService.java \
  src/test/java/com/aitaskcenter/service/MinioConnectionVerifierTest.java \
  src/test/java/com/aitaskcenter/service/MinioConfigServiceTest.java
git commit -m "feat: verify private minio storage"
```

### Task 3: Expose the redacted configuration API

**Files:**
- Create: `src/main/java/com/aitaskcenter/controller/MinioConfigController.java`
- Test: `src/test/java/com/aitaskcenter/controller/MinioConfigControllerTest.java`

- [ ] **Step 1: Write failing MVC contract tests**

Lock these routes and methods:

```text
GET  /api/minio/config
PUT  /api/minio/config
POST /api/minio/config/test
```

Serialize the response and assert it contains `secretConfigured:true` but contains neither `secretAccessKey` nor the fake secret value. Verify PUT and test each delegate exactly once.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `mvn -B -ntp -Dtest=MinioConfigControllerTest test`

Expected: requests return 404 because the controller is absent.

- [ ] **Step 3: Implement the controller**

```java
@RestController
@RequestMapping("/api/minio/config")
public class MinioConfigController {
    private final MinioConfigService service;

    public MinioConfigController(MinioConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<MinioConfigView> get() {
        return ApiResponse.ok(service.get());
    }

    @PutMapping
    public ApiResponse<MinioConfigView> save(@RequestBody MinioConfigRequest request) {
        return ApiResponse.ok(service.save(request), "MinIO 配置已保存");
    }

    @PostMapping("/test")
    public ApiResponse<Void> test(@RequestBody MinioConfigRequest request) {
        service.test(request);
        return ApiResponse.ok(null, "MinIO 连接测试成功");
    }
}
```

Return the standard `ApiResponse` and bounded Chinese success messages.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run: `mvn -B -ntp -Dtest=MinioConfigControllerTest test`

Expected: all route and redaction tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/aitaskcenter/controller/MinioConfigController.java \
  src/test/java/com/aitaskcenter/controller/MinioConfigControllerTest.java
git commit -m "feat: expose minio configuration api"
```

### Task 4: Replace filesystem image storage with MinIO

**Files:**
- Rewrite: `src/main/java/com/aitaskcenter/service/ImageAssetStore.java`
- Rewrite: `src/test/java/com/aitaskcenter/service/ImageAssetStoreTest.java`
- Modify: `src/test/java/com/aitaskcenter/service/ImageRunExecutionServiceTest.java`

- [ ] **Step 1: Rewrite tests first for MinIO semantics**

Keep every existing public method signature. Test safe object keys, 25 MiB/40 MP limits, actual MIME inspection, duplicate rejection, bounded reads, SHA mismatch, delete-before-hash refusal and generated path:

```java
StoredAsset saved = store.store("run-1", "shot-1-final", "image/png", png);
assertEquals("image-story/run-1/shot-1-final.png", saved.relativePath());
```

Add an execution test proving an unavailable MinIO probe causes zero run saves, zero model calls and zero image calls.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `mvn -B -ntp -Dtest=ImageAssetStoreTest,ImageRunExecutionServiceTest test`

Expected: old filesystem implementation fails the MinIO interactions and object-key expectations.

- [ ] **Step 3: Implement MinIO-only store/read/delete**

Resolve enabled configuration for every operation. Upload known-length bytes with `PutObjectArgs.headers(Map.of("If-None-Match", "*"))`; map only the exact HTTP 412 precondition failure to the duplicate-object error, so the object store enforces create-only publication atomically. After upload, call `statObject` and verify size. Read through `GetObjectResponse` into a bounded stream capped at `MAX_BYTES + 1`, then validate SHA. Delete first reads and verifies SHA, then removes the key. Never list buckets or objects.

Preserve the existing controlled path contract while prefixing with configured `basePath`; the database `relativePath` is the full object key. Remove all `SecureDirectoryStream`, portable filesystem and host-path code.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `mvn -B -ntp -Dtest=ImageAssetStoreTest,ImageRunExecutionServiceTest test`

Expected: store and execution tests pass; no local directory is created.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/aitaskcenter/service/ImageAssetStore.java \
  src/test/java/com/aitaskcenter/service/ImageAssetStoreTest.java \
  src/test/java/com/aitaskcenter/service/ImageRunExecutionServiceTest.java
git commit -m "feat: store image assets in minio"
```

### Task 5: Add the frontend MinIO API and configuration page

**Files:**
- Modify: `web-react/src/api.ts`
- Create: `web-react/src/api.minio.test.ts`
- Create: `web-react/src/MinioConfigPage.tsx`
- Create: `web-react/src/MinioConfigPage.test.tsx`
- Modify: `web-react/src/styles.css`

- [ ] **Step 1: Write failing API and page tests**

Define the exact frontend contract:

```ts
export interface MinioConfigView {
  enabled: boolean;
  endpoint: string;
  accessKeyId: string;
  useSsl: boolean;
  bucketName: string;
  basePath: string;
  secretConfigured: boolean;
  updatedAt: string | null;
}
export interface MinioConfigUpdate extends Omit<MinioConfigView, 'secretConfigured'> {
  secretAccessKey: string;
}
```

Test GET/PUT/POST paths, loading/error states, `secretConfigured` hint, empty secret preservation, field trimming, test connection, save, disabled duplicate submit, stale response protection and no rendering of a server secret field.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `npm --prefix web-react test -- api.minio.test.ts MinioConfigPage.test.tsx`

Expected: module/API/page symbols are missing.

- [ ] **Step 3: Implement the API and isolated page**

Add:

```ts
export const getMinioConfig = () => request.get<ApiResponse<MinioConfigView>>('/minio/config').then(unwrap);
export const saveMinioConfig = (value: MinioConfigUpdate) => request.put<ApiResponse<MinioConfigView>>('/minio/config', value).then(unwrap);
export const testMinioConfig = (value: MinioConfigUpdate) => request.post('/minio/config/test', value).then(unwrap);
```

The page owns its load/error/form state, uses `Password` for the secret, labels the saved-secret state without placing it into form values, and updates `updatedAt` from each successful save. Prefix all new CSS classes with `minio-config-`.

- [ ] **Step 4: Run focused tests and build**

Run: `npm --prefix web-react test -- api.minio.test.ts MinioConfigPage.test.tsx`

Run: `npm --prefix web-react run build`

Expected: focused tests and TypeScript production build pass.

- [ ] **Step 5: Commit**

```bash
git add web-react/src/api.ts web-react/src/api.minio.test.ts \
  web-react/src/MinioConfigPage.tsx web-react/src/MinioConfigPage.test.tsx \
  web-react/src/styles.css
git commit -m "feat: configure minio storage"
```

### Task 6: Integrate the fifth configuration menu without global loading coupling

**Files:**
- Modify: `web-react/src/App.tsx`
- Modify: `web-react/src/App.test.tsx`

- [ ] **Step 1: Add failing App navigation tests**

Assert the exact sidebar order:

```ts
expect(items).toEqual([
  '数据库配置', 'AI 配置', '本地 CLI 配置', '图片模型配置', 'MinIO 配置',
]);
```

Mock `MinioConfigPage`, click its menu item and assert the page renders even when database, AI or CLI loading fails. Also assert switching away does not remount unrelated configuration loaders.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `npm --prefix web-react test -- App.test.tsx`

Expected: menu/order/page assertions fail.

- [ ] **Step 3: Integrate the page**

Extend `ConfigTab` with `minio`, append `<CloudServerOutlined /> MinIO 配置`, and render `<MinioConfigPage />` before the global database/AI/CLI loading-error branches, matching the image-model isolation pattern.

- [ ] **Step 4: Run focused and full frontend tests**

Run: `npm --prefix web-react test -- App.test.tsx MinioConfigPage.test.tsx api.minio.test.ts`

Run: `npm --prefix web-react test`

Expected: all frontend tests pass.

- [ ] **Step 5: Commit**

```bash
git add web-react/src/App.tsx web-react/src/App.test.tsx
git commit -m "feat: add minio configuration navigation"
```

### Task 7: Remove obsolete local image-volume deployment plumbing and update facts

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `scripts/start-dev.sh`
- Modify: `.env.local.example`
- Modify: `deploy/context-router/fast/compose.yml`
- Modify: `deploy/context-router/full/compose.yml`
- Modify: `deploy/context-router/full/Dockerfile.base`
- Modify: `src/test/java/com/aitaskcenter/config/ImageDeploymentConfigTest.java`
- Modify: `docs/backend/java_server/AGENTS.md`
- Modify: `docs/frontend/web_react/AGENTS.md`
- Modify: `docs/shared/runtime-deployment-map.md`
- Modify: `docs/chains/config-management.md`
- Modify: `docs/chains/image-story-generation.md`

- [ ] **Step 1: Make deployment tests fail on the old local volume contract**

Replace assertions for `IMAGE_STORY_STORAGE_ROOT`, portable mode, volume init and `english-material-image-story` with assertions that these strings are absent from Fast/Full Compose, local startup, environment template and Dockerfile. Add assertions that runtime image reads remain available only through `/api/image-assets/{id}/content` documentation.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `mvn -B -ntp -Dtest=ImageDeploymentConfigTest test`

Expected: assertions fail because local volume wiring still exists.

- [ ] **Step 3: Remove filesystem configuration and update documentation**

Delete the two `image-story.*` filesystem properties, local env variables, Compose init service/volume/mount/env, and Dockerfile image-storage directory setup. Document the new table, three MinIO endpoints, private bucket, object-key/SHA rules, and the fact that the browser never receives MinIO credentials or direct object URLs.

- [ ] **Step 4: Validate deployment files and documentation**

Run: `mvn -B -ntp -Dtest=ImageDeploymentConfigTest test`

Run: `sh -n scripts/start-dev.sh deploy/context-router/fast/deploy.sh deploy/context-router/full/deploy.sh`

Run: `docker compose -f deploy/context-router/fast/compose.yml config --quiet`

Run: `docker compose -f deploy/context-router/full/compose.yml config --quiet`

Run: `git diff --check`

Expected: all commands succeed and no credential value appears in the diff.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.yml scripts/start-dev.sh .env.local.example \
  deploy/context-router/fast/compose.yml deploy/context-router/full/compose.yml \
  deploy/context-router/full/Dockerfile.base \
  src/test/java/com/aitaskcenter/config/ImageDeploymentConfigTest.java \
  docs/backend/java_server/AGENTS.md docs/frontend/web_react/AGENTS.md \
  docs/shared/runtime-deployment-map.md docs/chains/config-management.md \
  docs/chains/image-story-generation.md
git commit -m "docs: switch image storage to minio"
```

### Task 8: Full verification, deployment and secure live configuration

**Files:**
- No source edits expected; if verification finds a defect, return to the owning task and add a RED test before changing code.

- [ ] **Step 1: Run clean backend verification**

Run: `mvn -B -ntp clean test`

Expected: all backend tests pass; any local loopback fixture is rerun with the minimum required host permission.

- [ ] **Step 2: Run clean frontend verification**

Run: `npm --prefix web-react test`

Run: `npm --prefix web-react run build`

Expected: all frontend tests pass and the production build succeeds; the existing chunk-size warning is non-blocking.

- [ ] **Step 3: Review the complete diff and credential boundary**

Run: `git status --short`

Run: `git diff --check`

Run a bounded secret-pattern scan over changed source, tests and docs. Expected: no MinIO Secret Key, complete connection string or copied `ai-datahub` credential appears in Git content or test output.

- [ ] **Step 4: Deploy through Context Router task 612**

Call `apply_workspace_changes(task_id=612, changed_files=[all real Workspace-relative changed files])` exactly once. Poll `get_workspace_operation` to a terminal state and require both backend and frontend health checks to succeed.

- [ ] **Step 5: Configure and verify MinIO without exposing credentials**

Read the existing `ai-datahub` MinIO values locally, submit them only to `PUT /api/minio/config` with Bucket `english-material`, base path `image-story`, and enabled true. Never print the request body. Verify with a sanitized GET response containing only enabled, bucket, base path, SSL flag, secretConfigured and updatedAt; then call the test endpoint with a blank secret so it reuses the saved value.

- [ ] **Step 6: Perform browser acceptance without generating images**

Open the deployed frontend, navigate to “MinIO 配置”, verify the redacted configured state and run “测试连接”. Do not click “创建图片批次” and do not call an image Provider.

- [ ] **Step 7: Final status**

Run: `git status --short`

Expected: clean current branch. Report test counts, deployment operation terminal state, redacted MinIO connection success, and that no image-model quota was consumed.
