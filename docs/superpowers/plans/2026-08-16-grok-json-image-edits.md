# Grok JSON Image Edits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route Grok reference-image requests through JSON `/v1/images/edits`, collapse more than three references into one bounded composite board, and leave Gemini multipart editing unchanged.

**Architecture:** Keep provider routing inside `AiImageGenerationService`, using the existing `grok-imagine-image` model-prefix check. Add a Grok-only JSON edit builder and a deterministic in-memory compositor; retain all existing validation, response parsing, normalization, and the non-Grok multipart builder.

**Tech Stack:** Java 17, Spring Boot, `java.net.http.HttpClient`, Jackson, Java2D/ImageIO, JUnit 5, Maven.

---

## File map

- Modify: `src/main/java/com/aitaskcenter/service/AiImageGenerationService.java` — select the edit protocol, encode Grok data-URI references, and compose reference boards.
- Modify: `src/test/java/com/aitaskcenter/service/AiImageGenerationServiceTest.java` — prove Grok JSON payloads, reference order, composite contents, invalid-input behavior, and unchanged Gemini multipart behavior.
- Read: `docs/superpowers/specs/2026-08-16-grok-json-image-edits-design.md` — accepted behavior contract.

The two source files already contain related uncommitted work. Do not stage or commit those files automatically; preserve the existing changes and let the owner decide how to group the final source commit.

### Task 1: Route one to three Grok references through JSON

**Files:**
- Modify: `src/test/java/com/aitaskcenter/service/AiImageGenerationServiceTest.java`
- Modify: `src/main/java/com/aitaskcenter/service/AiImageGenerationService.java`

- [ ] **Step 1: Replace the old Grok multipart assertion with failing JSON edit tests**

Add tests that parse the captured request body as JSON and assert the exact provider contract:

```java
@Test
void postsSingleGrokReferenceAsJsonImageDataUri() throws Exception {
    AiProviderConfigItem provider = grokProvider();
    byte[] referenceBytes = png(1, 1, Color.RED);
    service().generate(provider, "keep the character", "no letters", 1536, 864,
            List.of(new ImageReference("reference.png", "image/png", referenceBytes)));
    CapturedRequest request = requests.get(0);
    JsonNode body = objectMapper.readTree(request.body());
    assertEquals("/v1/images/edits", request.path());
    assertTrue(request.contentType().startsWith("application/json"));
    assertEquals("image_url", body.path("image").path("type").asText());
    assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(referenceBytes),
            body.path("image").path("url").asText());
    assertEquals("16:9", body.path("aspect_ratio").asText());
    assertEquals("keep the character\n\nAvoid: no letters", body.path("prompt").asText());
    assertFalse(body.has("negative_prompt"));
    assertFalse(body.has("quality"));
    assertFalse(body.has("size"));
}

@Test
void postsThreeGrokReferencesAsOrderedJsonImages() throws Exception {
    AiProviderConfigItem provider = grokProvider();
    List<ImageReference> references = List.of(
            new ImageReference("first.png", "image/png", png(1, 1, Color.RED)),
            new ImageReference("second.png", "image/png", png(1, 1, Color.GREEN)),
            new ImageReference("third.png", "image/png", png(1, 1, Color.BLUE)));
    service().generate(provider, "combine", "", 3, 2, references);
    JsonNode images = objectMapper.readTree(requests.get(0).body()).path("images");
    assertEquals(3, images.size());
    for (int index = 0; index < references.size(); index++) {
        ImageReference reference = references.get(index);
        assertEquals("image_url", images.path(index).path("type").asText());
        assertEquals("data:" + reference.mimeType() + ";base64,"
                        + Base64.getEncoder().encodeToString(reference.bytes()),
                images.path(index).path("url").asText());
    }
}
```

Add a `grokProvider()` helper that selects `grok-imagine-image-quality` with the existing `b64_json`, `hd`, and `1536x864` options.

- [ ] **Step 2: Run the two tests and verify RED**

Run:

```bash
mvn -Dtest=AiImageGenerationServiceTest#postsSingleGrokReferenceAsJsonImageDataUri+postsThreeGrokReferencesAsOrderedJsonImages test
```

Expected: FAIL because the current edit body is multipart and cannot be parsed as JSON.

- [ ] **Step 3: Add the minimal Grok JSON edit builder**

Change request selection to keep generation and Gemini paths intact:

```java
HttpRequest request;
if (safeReferences.isEmpty()) {
    request = jsonRequest(provider, prompt, negativePrompt, options, baseUri);
} else if (isGrokImagine(provider)) {
    request = grokEditJsonRequest(provider, prompt, negativePrompt, options, safeReferences, baseUri);
} else {
    request = multipartRequest(provider, prompt, negativePrompt, options, safeReferences, baseUri);
}
```

Build JSON edit references as data URIs:

```java
private HttpRequest grokEditJsonRequest(AiProviderConfigItem provider, String prompt, String negativePrompt,
                                        ImageOptions options, List<ImageReference> references, URI baseUri)
        throws Exception {
    Map<String, Object> body = commonFields(provider, prompt, negativePrompt, options);
    List<Map<String, String>> encoded = references.stream().map(this::grokReference).toList();
    if (encoded.size() == 1) {
        body.put("image", encoded.get(0));
    } else {
        body.put("images", encoded);
    }
    return requestBuilder(provider, endpoint(baseUri, "/images/edits"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();
}

private Map<String, String> grokReference(ImageReference reference) {
    return Map.of("type", "image_url",
            "url", "data:" + reference.mimeType() + ";base64,"
                    + Base64.getEncoder().encodeToString(reference.bytes()));
}
```

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the Step 2 command again.

Expected: both tests PASS and one-reference payload uses `image` while three-reference payload uses ordered `images`.

### Task 2: Compose four to eight Grok references into one board

**Files:**
- Modify: `src/test/java/com/aitaskcenter/service/AiImageGenerationServiceTest.java`
- Modify: `src/main/java/com/aitaskcenter/service/AiImageGenerationService.java`

- [ ] **Step 1: Write a failing four-reference composite test**

Add a `solidPng` helper that fills every source pixel, then verify one decoded board contains all four colors in input order:

```java
@Test
void combinesMoreThanThreeGrokReferencesIntoOneOrderedBoard() throws Exception {
    List<ImageReference> references = List.of(
            new ImageReference("red.png", "image/png", solidPng(2, 2, Color.RED)),
            new ImageReference("green.png", "image/png", solidPng(2, 2, Color.GREEN)),
            new ImageReference("blue.png", "image/png", solidPng(2, 2, Color.BLUE)),
            new ImageReference("yellow.png", "image/png", solidPng(2, 2, Color.YELLOW)));
    service().generate(grokProvider(), "combine", "", 8, 4, references);
    JsonNode body = objectMapper.readTree(requests.get(0).body());
    assertTrue(body.has("image"));
    assertFalse(body.has("images"));
    String dataUri = body.path("image").path("url").asText();
    assertTrue(dataUri.startsWith("data:image/png;base64,"));
    BufferedImage board = ImageIO.read(new ByteArrayInputStream(
            Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1))));
    assertEquals(8, board.getWidth());
    assertEquals(4, board.getHeight());
    assertEquals(Color.RED.getRGB(), board.getRGB(2, 1));
    assertEquals(Color.GREEN.getRGB(), board.getRGB(6, 1));
    assertEquals(Color.BLUE.getRGB(), board.getRGB(2, 3));
    assertEquals(Color.YELLOW.getRGB(), board.getRGB(6, 3));
}
```

- [ ] **Step 2: Run the composite test and verify RED**

Run:

```bash
mvn -Dtest=AiImageGenerationServiceTest#combinesMoreThanThreeGrokReferencesIntoOneOrderedBoard test
```

Expected: FAIL because the new Grok builder still sends four separate `images`.

- [ ] **Step 3: Implement the bounded deterministic compositor**

Before encoding Grok references, collapse lists larger than three:

```java
List<ImageReference> effectiveReferences = references.size() <= GROK_MAX_EDIT_REFERENCES
        ? references
        : List.of(compositeReferenceBoard(references, options.size()));
```

Implement `compositeReferenceBoard` with a neutral RGB canvas, `ceil(sqrt(count))` columns, enough rows for all inputs, bicubic scaling, and proportional letterboxing. Add focused helpers:

```java
private ImageReference compositeReferenceBoard(List<ImageReference> references, String requestedSize) {
    Dimensions dimensions = dimensions(requestedSize);
    int columns = (int) Math.ceil(Math.sqrt(references.size()));
    int rows = (int) Math.ceil((double) references.size() / columns);
    BufferedImage board = new BufferedImage(dimensions.width(), dimensions.height(), BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = board.createGraphics();
    try {
        graphics.setColor(new Color(245, 245, 245));
        graphics.fillRect(0, 0, board.getWidth(), board.getHeight());
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        for (int index = 0; index < references.size(); index++) {
            BufferedImage source = decodeReferenceImage(references.get(index));
            drawLetterboxed(graphics, source, index % columns, index / columns,
                    columns, rows, board.getWidth(), board.getHeight());
        }
    } finally {
        graphics.dispose();
    }
    byte[] encoded = encodePng(board);
    if (encoded.length > MAX_REFERENCE_BYTES) {
        throw new IllegalArgumentException("Grok 参考图板过大");
    }
    return new ImageReference("grok-reference-board.png", "image/png", encoded);
}
```

Use these exact helpers to validate dimensions, decode bounded inputs, letterbox without cropping, and encode a bounded PNG:

```java
private Dimensions dimensions(String size) {
    try {
        int separator = size.indexOf('x');
        int width = Integer.parseInt(size.substring(0, separator));
        int height = Integer.parseInt(size.substring(separator + 1));
        if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
            throw new IllegalArgumentException();
        }
        return new Dimensions(width, height);
    } catch (RuntimeException exception) {
        throw new IllegalArgumentException("图片尺寸配置无效");
    }
}

private BufferedImage decodeReferenceImage(ImageReference reference) {
    try {
        return decodeImage(reference.bytes()).image();
    } catch (IllegalArgumentException exception) {
        throw exception;
    } catch (IOException exception) {
        throw new IllegalArgumentException("参考图数据无效");
    }
}

private void drawLetterboxed(Graphics2D graphics, BufferedImage source, int column, int row,
                             int columns, int rows, int boardWidth, int boardHeight) {
    int left = column * boardWidth / columns;
    int right = (column + 1) * boardWidth / columns;
    int top = row * boardHeight / rows;
    int bottom = (row + 1) * boardHeight / rows;
    double scale = Math.min((double) (right - left) / source.getWidth(),
            (double) (bottom - top) / source.getHeight());
    int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
    int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
    int x = left + (right - left - width) / 2;
    int y = top + (bottom - top - height) / 2;
    graphics.drawImage(source, x, y, width, height, null);
}

private byte[] encodePng(BufferedImage image) {
    try {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output) || output.size() > MAX_DECODED_BYTES) {
            throw new IllegalArgumentException("Grok 参考图板生成失败");
        }
        return output.toByteArray();
    } catch (IOException exception) {
        throw new IllegalArgumentException("Grok 参考图板生成失败");
    }
}

private record Dimensions(int width, int height) {
}
```

- [ ] **Step 4: Run the composite test and verify GREEN**

Run the Step 2 command again.

Expected: PASS with a single 8×4 PNG data URI and four ordered color cells.

- [ ] **Step 5: Add and verify the eight-reference and invalid-reference boundaries**

Add one test with eight small valid images asserting one `image`, and one test with four references where one byte array is not an image asserting failure before HTTP:

```java
@Test
void combinesEightGrokReferencesIntoOneBoard() throws Exception {
    List<ImageReference> references = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
        references.add(new ImageReference("reference-" + index + ".png", "image/png",
                solidPng(2, 2, new Color(index * 20, index * 20, index * 20))));
    }
    service().generate(grokProvider(), "combine", "", 12, 8, references);
    JsonNode body = objectMapper.readTree(requests.get(0).body());
    assertTrue(body.has("image"));
    assertFalse(body.has("images"));
}

@Test
void rejectsInvalidGrokCompositeReferenceBeforeRequest() {
    List<ImageReference> references = List.of(
            new ImageReference("one.png", "image/png", solidPng(2, 2, Color.RED)),
            new ImageReference("two.png", "image/png", solidPng(2, 2, Color.GREEN)),
            new ImageReference("bad.png", "image/png", new byte[] {1, 2, 3}),
            new ImageReference("four.png", "image/png", solidPng(2, 2, Color.BLUE)));
    assertThrows(IllegalArgumentException.class,
            () -> service().generate(grokProvider(), "combine", "", 8, 4, references));
    assertTrue(requests.isEmpty());
}
```

Run:

```bash
mvn -Dtest=AiImageGenerationServiceTest#combinesEightGrokReferencesIntoOneBoard+rejectsInvalidGrokCompositeReferenceBeforeRequest test
```

Expected: PASS without further production behavior: the compositor is count-independent through eight references and validation precedes request construction. If either fails, make only the smallest correction and rerun.

### Task 3: Prove Gemini compatibility and complete verification

**Files:**
- Modify: `src/test/java/com/aitaskcenter/service/AiImageGenerationServiceTest.java`
- Verify: `src/main/java/com/aitaskcenter/service/AiImageGenerationService.java`

- [ ] **Step 1: Make the existing multipart test explicitly represent Gemini**

Set its provider model explicitly and retain the exact assertions for `multipart/form-data`, ordered `image`, `image1`, `image2`, filenames, and `response_format`:

```java
AiProviderConfigItem provider = provider(baseUrl() + "/v1");
provider.setModel("gemini-3.1-flash-image");
service.generate(provider, "combine", "", 3, 2, List.of(first, second, third));
```

- [ ] **Step 2: Run all image-service tests**

Run:

```bash
mvn -Dtest=AiImageGenerationServiceTest test
```

Expected: all `AiImageGenerationServiceTest` tests PASS with zero failures and errors.

- [ ] **Step 3: Run the full backend test suite**

Run:

```bash
mvn test
```

Expected: BUILD SUCCESS with zero failures and errors.

- [ ] **Step 4: Inspect the final scoped diff**

Run:

```bash
git diff --check -- src/main/java/com/aitaskcenter/service/AiImageGenerationService.java src/test/java/com/aitaskcenter/service/AiImageGenerationServiceTest.java
git diff --stat -- src/main/java/com/aitaskcenter/service/AiImageGenerationService.java src/test/java/com/aitaskcenter/service/AiImageGenerationServiceTest.java
```

Expected: no whitespace errors. Do not stage the source files because both contained pre-existing uncommitted changes before this task.

- [ ] **Step 5: Apply registered workspace changes**

Call Context Router `apply_workspace_changes` with task `615` and these workspace-relative paths:

```text
src/main/java/com/aitaskcenter/service/AiImageGenerationService.java
src/test/java/com/aitaskcenter/service/AiImageGenerationServiceTest.java
```

Poll the returned operation with `get_workspace_operation` until terminal. Expected: `succeeded`; otherwise report the exact failed step and bounded log tail.

- [ ] **Step 6: Run a real Sub2API Grok edit smoke test**

After deployment succeeds, invoke one minimal Grok JSON edit using the already approved public-reference smoke-test approach. Never print the API key or base64 response. Decode the result into `/Users/conchi/Documents/Codex/2026-08-16/new-chat/outputs/` and verify it with `file` and `sips`.

Expected: HTTP 2xx, non-empty `data[0].b64_json`, and a decodable image.
