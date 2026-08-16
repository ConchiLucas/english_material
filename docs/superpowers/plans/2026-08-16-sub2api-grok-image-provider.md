# Sub2API Grok Image Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the CodeBuddy Sub2API credential as a dedicated Grok image Provider, select it for the image workflow, and prove the project-compatible request can generate a valid image.

**Architecture:** Reuse the existing AI configuration and image-flow HTTP APIs; do not modify application source or PostgreSQL JSON directly. The backend container reaches the host-published Sub2API port through `host.docker.internal`, while the secret is read locally from CodeBuddy and sent only in the configuration request body.

**Tech Stack:** Spring Boot REST API, PostgreSQL-backed `tb_ai_config`, Docker Desktop host networking, jq, curl, Sub2API OpenAI-compatible Images API.

---

## File map

- Read: `/Users/conchi/.codebuddy/models.json` — source the already-working Sub2API API key without printing it.
- Persist through API: `tb_ai_config` — append `sub2api-grok-image` while preserving every existing Provider and secret.
- Persist through API: `tb_image_flow_config` — switch only `image_provider_id`, retaining fixed dimensions and shot limits.
- Create test artifact outside the repository: `/Users/conchi/Documents/Codex/2026-08-16/new-chat/outputs/english-material-grok-provider-test.jpg` — decoded verification image.
- No application source files are modified.

### Task 1: Establish the failing acceptance state

**Files:**
- Read: project HTTP APIs only

- [ ] **Step 1: Assert the desired Provider and flow selection are not both present yet**

Run:

```bash
curl --silent --show-error --fail http://127.0.0.1:18744/api/ai/config -o /tmp/english-ai-before.json
curl --silent --show-error --fail http://127.0.0.1:18744/api/image-agents/flow -o /tmp/english-image-flow-before.json
test "$(jq '[.data.providers[] | select(.id == "sub2api-grok-image")] | length' /tmp/english-ai-before.json)" = "1" \
  && test "$(jq -r '.data.config.imageProviderId' /tmp/english-image-flow-before.json)" = "sub2api-grok-image"
```

Expected: FAIL before configuration because the Provider is absent or the image flow still selects another Provider. This is the configuration equivalent of the RED acceptance check; no source code is involved.

### Task 2: Persist the Grok Provider through the supported API

**Files:**
- Read: `/Users/conchi/.codebuddy/models.json`
- Persist through API: `tb_ai_config`

- [ ] **Step 1: Build a complete request with the new Provider and no secret output**

Read the current redacted configuration, extract the `grok-4.6` key locally, and create a mode-600 temporary request. Replace any same-ID entry to keep the operation idempotent:

```bash
request_file=$(mktemp /tmp/english-grok-provider.XXXXXX.json)
chmod 600 "$request_file"
cb_grok_key=$(jq -er '.models[] | select(.id == "grok-4.6") | .apiKey | select(length > 0)' /Users/conchi/.codebuddy/models.json)
curl --silent --show-error --fail http://127.0.0.1:18744/api/ai/config \
  | jq --arg grok_key "$cb_grok_key" '.data
      | .providers = ([.providers[] | select(.id != "sub2api-grok-image")] + [{
          id: "sub2api-grok-image",
          label: "Sub2API Grok Image",
          type: "openai-compatible",
          base_url: "http://host.docker.internal:18046/v1",
          api_key: $grok_key,
          model: "grok-imagine-image",
          max_tokens: 4096,
          capabilities: ["IMAGE_GENERATION", "IMAGE_REFERENCE"],
          options: {responseFormat: "b64_json", quality: "hd", size: "1536x864"},
          enabled: true
        }])' > "$request_file"
unset cb_grok_key
```

Expected: command exits 0; nothing prints the key.

- [ ] **Step 2: Save through `POST /api/ai/config` and clean up the temporary secret**

Run:

```bash
curl --silent --show-error --fail \
  -H 'Content-Type: application/json' \
  --data-binary @"$request_file" \
  http://127.0.0.1:18744/api/ai/config \
  -o /tmp/english-ai-save-response.json
rm -f "$request_file"
unset request_file
jq -e '.code == 0 and ([.data[] | select(.id == "sub2api-grok-image")] | length == 1)' \
  /tmp/english-ai-save-response.json >/dev/null
```

Expected: PASS. The response is redacted by `AiConfigController` and does not contain the API key.

- [ ] **Step 3: Verify the persisted public Provider contract**

Run:

```bash
curl --silent --show-error --fail http://127.0.0.1:18744/api/ai/config \
  | jq -e '.data.providers[] | select(
      .id == "sub2api-grok-image"
      and .base_url == "http://host.docker.internal:18046/v1"
      and .model == "grok-imagine-image"
      and .enabled == true
      and .api_key == null
      and (.capabilities == ["IMAGE_GENERATION", "IMAGE_REFERENCE"])
      and .options.responseFormat == "b64_json"
      and .options.quality == "hd"
      and .options.size == "1536x864"
    )' >/dev/null
```

Expected: PASS. A null `api_key` proves the read API remains redacted, not that the database key is missing.

### Task 3: Select Grok for the project image workflow

**Files:**
- Persist through API: `tb_image_flow_config`

- [ ] **Step 1: Read the current optimistic-lock timestamp and fixed settings**

Run:

```bash
curl --silent --show-error --fail http://127.0.0.1:18744/api/image-agents/flow \
  -o /tmp/english-image-flow-current.json
jq '.data.config + {imageProviderId: "sub2api-grok-image"}' \
  /tmp/english-image-flow-current.json > /tmp/english-image-flow-update.json
```

Expected: the request retains `width=1536`, `height=864`, `maxShotsPerScene=5`, `maxShotsPerStory=20`, and the current `updatedAt`.

- [ ] **Step 2: Save and verify the flow selection**

Run:

```bash
curl --silent --show-error --fail -X PUT \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/english-image-flow-update.json \
  http://127.0.0.1:18744/api/image-agents/flow/config \
  -o /tmp/english-image-flow-save-response.json
jq -e '.code == 0
  and .data.imageProviderId == "sub2api-grok-image"
  and .data.width == 1536
  and .data.height == 864
  and .data.maxShotsPerScene == 5
  and .data.maxShotsPerStory == 20' /tmp/english-image-flow-save-response.json >/dev/null
```

Expected: PASS.

### Task 4: Verify a project-compatible Grok generation

**Files:**
- Create: `/Users/conchi/Documents/Codex/2026-08-16/new-chat/outputs/english-material-grok-provider-test.jpg`

- [ ] **Step 1: Call Sub2API with the exact fields used by the project adapter**

Use the same Base URL, key, model, response format, quality, and size that `AiImageGenerationService` will send. Do not print the response because it contains base64 image data:

```bash
cb_grok_key=$(jq -er '.models[] | select(.id == "grok-4.6") | .apiKey | select(length > 0)' /Users/conchi/.codebuddy/models.json)
curl --silent --show-error --max-time 240 \
  -H "Authorization: Bearer ${cb_grok_key}" \
  -H 'Content-Type: application/json' \
  --data '{"model":"grok-imagine-image","prompt":"A friendly red panda studying English picture cards at a warm wooden desk, polished children storybook illustration, no text, no letters, no watermark","n":1,"response_format":"b64_json","quality":"hd","size":"1536x864"}' \
  http://127.0.0.1:18046/v1/images/generations \
  -o /tmp/english-grok-image-response.json
unset cb_grok_key
```

Expected: HTTP request exits 0 and response JSON contains one `data[0].b64_json`. If Sub2API returns local account-concurrency 429, wait for the single Grok account slot to become idle and retry once without changing concurrency.

- [ ] **Step 2: Decode and validate the generated image**

Run:

```bash
mkdir -p /Users/conchi/Documents/Codex/2026-08-16/new-chat/outputs
jq -er '.data[0].b64_json | select(length > 0)' /tmp/english-grok-image-response.json \
  | base64 --decode > /Users/conchi/Documents/Codex/2026-08-16/new-chat/outputs/english-material-grok-provider-test.jpg
file /Users/conchi/Documents/Codex/2026-08-16/new-chat/outputs/english-material-grok-provider-test.jpg
sips -g pixelWidth -g pixelHeight -g format \
  /Users/conchi/Documents/Codex/2026-08-16/new-chat/outputs/english-material-grok-provider-test.jpg
```

Expected: a non-empty JPEG or PNG that ImageIO can decode. A Provider result such as 1280×720 is acceptable because the application already center-crops and normalizes it to 1536×864 before persistence.

- [ ] **Step 3: Roll back only the flow selection if generation verification fails**

Skip this step when generation succeeds. On failure, preserve the new Provider for diagnosis but restore the Provider ID captured in `/tmp/english-image-flow-before.json`, using the latest optimistic-lock timestamp:

```bash
previous_provider=$(jq -er '.data.config.imageProviderId | select(length > 0)' /tmp/english-image-flow-before.json)
curl --silent --show-error --fail http://127.0.0.1:18744/api/image-agents/flow \
  | jq --arg previous_provider "$previous_provider" '.data.config + {imageProviderId: $previous_provider}' \
  > /tmp/english-image-flow-rollback.json
curl --silent --show-error --fail -X PUT \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/english-image-flow-rollback.json \
  http://127.0.0.1:18744/api/image-agents/flow/config \
  | jq -e --arg previous_provider "$previous_provider" \
      '.code == 0 and .data.imageProviderId == $previous_provider' >/dev/null
unset previous_provider
```

Expected: PASS and the project resumes using its previous Antigravity image Provider. Report the exact bounded Sub2API failure without changing account concurrency or other Providers.

### Task 5: Run the final acceptance gate

**Files:**
- Read: project HTTP APIs and generated artifact

- [ ] **Step 1: Re-run the original state assertion and artifact checks**

Run:

```bash
curl --silent --show-error --fail http://127.0.0.1:18744/api/ai/config -o /tmp/english-ai-after.json
curl --silent --show-error --fail http://127.0.0.1:18744/api/image-agents/flow -o /tmp/english-image-flow-after.json
test "$(jq '[.data.providers[] | select(.id == "sub2api-grok-image")] | length' /tmp/english-ai-after.json)" = "1"
test "$(jq -r '.data.config.imageProviderId' /tmp/english-image-flow-after.json)" = "sub2api-grok-image"
test -s /Users/conchi/Documents/Codex/2026-08-16/new-chat/outputs/english-material-grok-provider-test.jpg
test "$(jq -r '.data.providers[] | select(.id == "sub2api-grok-image") | .api_key' /tmp/english-ai-after.json)" = "null"
```

Expected: all commands exit 0. Report that the Provider is persisted, selected, secret-redacted on reads, and capable of returning a decodable image.

No `apply_workspace_changes` call or application commit is required because implementation changes runtime configuration only and modifies no registered source file.
