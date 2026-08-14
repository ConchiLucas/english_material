# Pure Story Output Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Guarantee that every new story run stores and displays only a plain-English multi-scene story as its final result.

**Architecture:** Keep editable creative prompts and raw step outputs intact, but append a non-editable runtime contract to story-producing Agent calls. Validate and extract the framed story before it becomes the candidate passed downstream or `StoryRun.finalStory`; invalid outputs fail explicitly instead of falling back to the complete model response.

**Tech Stack:** Java 17, Spring Boot 3.3, Jackson, JUnit 5, Mockito, React/Vitest for regression verification, Context Router deployment.

---

### Task 1: Strict Story Output Contract and Extraction

**Files:**
- Modify: `src/test/java/com/aitaskcenter/service/StoryRunExecutionServiceTest.java`
- Modify: `src/main/java/com/aitaskcenter/service/StoryRunExecutionService.java`

- [ ] **Step 1: Write failing extraction tests**

Add tests that express the public behavior of the package-private extractor:

```java
@Test
void extractsPlainEnglishMultiSceneStory() {
    String output = """
            STORY_TEXT_BEGIN
            Scene 1: The Blue Bag

            Ben opens his blue bag.

            Scene 2: The Red Juice

            The red juice spills.
            STORY_TEXT_END
            """;

    assertEquals("""
            Scene 1: The Blue Bag

            Ben opens his blue bag.

            Scene 2: The Red Juice

            The red juice spills.
            """.strip(), StoryRunExecutionService.extractStory(output));
}

@Test
void rejectsUnframedOrNonPlainStoryOutput() {
    for (String invalid : List.of(
            "Here is the story:\nScene 1: A Story",
            "STORY_TEXT_BEGIN\n**Scene 1: A Story**\nSTORY_TEXT_END",
            "STORY_TEXT_BEGIN\n### Scene 1: A Story\nSTORY_TEXT_END",
            "STORY_TEXT_BEGIN\n故事说明\nScene 1: A Story\nSTORY_TEXT_END",
            "STORY_TEXT_BEGIN\nTarget Words Checklist\nSTORY_TEXT_END")) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> StoryRunExecutionService.extractStory(invalid));
        assertEquals("故事输出格式错误：只允许纯英文场景标题和故事正文", error.getMessage());
    }
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
JAVA_HOME=/Users/conchi/Library/Java/JavaVirtualMachines/graalvm-ce-21.0.2/Contents/Home \
  /opt/homebrew/bin/mvn -B -ntp -Dtest=StoryRunExecutionServiceTest test
```

Expected: the valid framed case may pass, while the invalid cases fail because the existing extractor returns the full response or accepts Markdown/Chinese content.

- [ ] **Step 3: Add failing runtime contract and downstream-candidate tests**

Capture `generationService.generateWithUsage` arguments during a PASS run and a REVISE run. Assert:

```java
assertTrue(writerSystemPrompt.contains("STORY_TEXT_BEGIN"));
assertTrue(writerSystemPrompt.contains("STORY_TEXT_END"));
assertTrue(writerSystemPrompt.contains("禁止 Markdown"));
assertTrue(reviserSystemPrompt.contains("STORY_TEXT_BEGIN"));

assertTrue(writerStep.getOutputText().contains("STORY_TEXT_BEGIN"));
assertEquals("COMPLETED", writerStep.getStatus());
assertTrue(reviewInputJson.contains("A plain story"));
assertFalse(reviewInputJson.contains("STORY_TEXT_BEGIN"));
```

Add a format-failure run where the writer returns an unframed report, then assert the writer step is `FAILED`, its raw output remains in `outputText`, the run is `FAILED`, and `finalStory` is null.

- [ ] **Step 4: Run the focused tests and verify RED**

Run the same focused Maven command. Expected: failures show that the writer/reviser system prompts lack the runtime contract, downstream reviewers receive raw framed output, and invalid writer output is currently accepted.

- [ ] **Step 5: Implement the minimal strict protocol**

In `StoryRunExecutionService`:

```java
private static final String STORY_OUTPUT_CONTRACT = """

        [运行时最终输出协议：此协议优先于前文的输出结构要求]
        只输出以下边界之间的纯英文故事，不得在边界外输出任何内容：
        STORY_TEXT_BEGIN
        Scene 1: Plain English Title

        Plain English story paragraphs only.
        STORY_TEXT_END
        故事块内禁止 Markdown、中文说明、目标词清单、评分信息、变更记录、表格或代码围栏。
        """;

private static boolean producesStory(String agentKey) {
    return "story-writer".equals(agentKey) || "targeted-reviser".equals(agentKey);
}
```

Build `effectiveSystemPrompt` from the editable prompt plus this contract for the two story-producing Agents. Use that effective prompt for budget reservation, generation and token estimation.

After generation, preserve the raw response in `StoryRunStep.outputText`, but return `extractStory(rawOutput)` from `call` for story-producing Agents. On format validation failure, preserve the raw response, mark the step `FAILED`, account for its actual or estimated tokens exactly once, and let the run become `FAILED` with the bounded format message.

Replace the permissive extractor with an anchored block parser and explicit plain-text validation:

```java
private static final Pattern STORY_BLOCK = Pattern.compile(
        "\\A\\s*STORY_TEXT_BEGIN\\s*(.*?)\\s*STORY_TEXT_END\\s*\\z",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
private static final Pattern CJK = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]");
private static final Pattern MARKDOWN = Pattern.compile(
        "(?m)^\\s*(?:#{1,6}\\s+|[-*+]\\s+|\\|)|\\*\\*|```|^.*\\|.*\\|.*$");

static String extractStory(String output) {
    String value = output == null ? "" : output.trim();
    Matcher matcher = STORY_BLOCK.matcher(value);
    if (!matcher.matches()) throw invalidStoryOutput();
    String story = matcher.group(1).trim();
    String normalized = story.toLowerCase(Locale.ROOT);
    if (story.isEmpty()
            || CJK.matcher(story).find()
            || MARKDOWN.matcher(story).find()
            || normalized.contains("target words checklist")
            || normalized.contains("revision log")) {
        throw invalidStoryOutput();
    }
    return story;
}
```

- [ ] **Step 6: Run focused tests and verify GREEN**

Run the focused Maven command. Expected: all `StoryRunExecutionServiceTest` tests pass.

- [ ] **Step 7: Commit Task 1**

```bash
git add src/main/java/com/aitaskcenter/service/StoryRunExecutionService.java \
  src/test/java/com/aitaskcenter/service/StoryRunExecutionServiceTest.java
git diff --cached --check
git commit -m "fix: enforce pure story output"
```

### Task 2: Align Default Story Prompts and Documentation

**Files:**
- Modify: `src/test/java/com/aitaskcenter/config/StoryAgentCatalogTest.java`
- Modify: `src/main/java/com/aitaskcenter/config/StoryAgentCatalog.java`
- Modify: `docs/backend/java_server/AGENTS.md`
- Modify: `docs/frontend/web_react/AGENTS.md`
- Modify: `docs/chains/story-agent-flow-config.md`

- [ ] **Step 1: Write failing catalog tests**

Add assertions for the default writer and reviser prompts:

```java
String writerPrompt = StoryAgentCatalog.require("story-writer").systemPrompt();
String reviserPrompt = StoryAgentCatalog.require("targeted-reviser").systemPrompt();
assertTrue(writerPrompt.contains("只输出完整英文故事正文"));
assertFalse(writerPrompt.contains("位置清单"));
assertTrue(reviserPrompt.contains("只输出修订后的完整英文故事正文"));
assertFalse(reviserPrompt.contains("变更记录"));
```

- [ ] **Step 2: Run the catalog test and verify RED**

Run:

```bash
JAVA_HOME=/Users/conchi/Library/Java/JavaVirtualMachines/graalvm-ce-21.0.2/Contents/Home \
  /opt/homebrew/bin/mvn -B -ntp -Dtest=StoryAgentCatalogTest test
```

Expected: assertions fail because both current defaults explicitly request audit sections.

- [ ] **Step 3: Make default prompts non-conflicting**

Change the writer output paragraph to require only a complete English story with plain-text scene titles. Change the reviser output paragraph to require only the revised complete English story. Keep creative, vocabulary, protection and routing responsibilities unchanged.

Do not update existing database prompt rows: user-edited prompts remain intact, and the runtime contract from Task 1 guarantees formatting.

- [ ] **Step 4: Update current-fact documentation**

Document that:

- writer/reviser raw responses remain in `tb_story_run_step`;
- only the validated story block reaches downstream reviewers and `tb_story_run.final_story`;
- final history output is plain English scene titles and paragraphs;
- missing or invalid story framing fails the run instead of persisting the full response;
- no extra Agent call, table or API is introduced.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run:

```bash
JAVA_HOME=/Users/conchi/Library/Java/JavaVirtualMachines/graalvm-ce-21.0.2/Contents/Home \
  /opt/homebrew/bin/mvn -B -ntp -Dtest=StoryAgentCatalogTest,StoryRunExecutionServiceTest test
```

Expected: all focused tests pass.

- [ ] **Step 6: Commit Task 2**

```bash
git add src/main/java/com/aitaskcenter/config/StoryAgentCatalog.java \
  src/test/java/com/aitaskcenter/config/StoryAgentCatalogTest.java \
  docs/backend/java_server/AGENTS.md \
  docs/frontend/web_react/AGENTS.md \
  docs/chains/story-agent-flow-config.md
git diff --cached --check
git commit -m "docs: align story delivery contract"
```

### Task 3: Full Verification, Deployment, and Real Acceptance

**Files:**
- Verify: all files changed in Tasks 1 and 2
- Update through Context Router: all Workspace-relative changed files from Tasks 1 and 2

- [ ] **Step 1: Run complete backend tests**

```bash
JAVA_HOME=/Users/conchi/Library/Java/JavaVirtualMachines/graalvm-ce-21.0.2/Contents/Home \
  /opt/homebrew/bin/mvn -B -ntp test
```

Expected: all backend tests pass with zero failures and errors.

- [ ] **Step 2: Run frontend regression tests and production build**

```bash
npm --prefix web-react test -- --run
npm --prefix web-react run build
```

Expected: all Vitest tests pass and the production build succeeds; the existing Vite chunk-size warning is non-blocking.

- [ ] **Step 3: Check repository integrity**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors and no uncommitted files after task commits.

- [ ] **Step 4: Deploy through Context Router**

Reuse task ID `595`. Call `apply_workspace_changes` once with every production, test and current-fact documentation file changed in Tasks 1 and 2. Poll the returned Workspace operation with `get_workspace_operation` until it reaches a terminal state.

Expected: operation status `succeeded` and both registered services healthy.

- [ ] **Step 5: Run a real acceptance batch**

Create one new batch using 20 random words from the configured third-grade first-semester library. Poll until terminal, then assert:

- `finalStory` starts with `Scene 1:` and contains only English story paragraphs;
- it has no `**`, `###`, table rows, Chinese text, checklist or revision log;
- the run history still shows every raw Agent input and output;
- the bottom result pane displays only the extracted story.

- [ ] **Step 6: Finish the development branch workflow**

Use `verification-before-completion`, then `finishing-a-development-branch`. The user explicitly authorized direct work in the current directory on `main`, so report the committed main state without creating, merging or deleting another branch.
