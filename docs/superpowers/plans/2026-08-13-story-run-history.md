# Story Run History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build formal multi-Agent story execution with persistent per-Agent input/output history and the confirmed full-screen run-record viewer.

**Architecture:** Spring Boot owns an asynchronous, bounded execution service and persists immutable run/step records in PostgreSQL. React creates runs, polls details, and renders one full-screen three-column record page with a fixed final-result quarter. External word libraries are queried read-only only when previewing random words; the created run stores its own word snapshot.

**Tech Stack:** Java 17, Spring Boot 3.3, Spring Data JPA, Java HttpClient, PostgreSQL, React 19, TypeScript, Ant Design, Vitest.

---

### Task 1: Run persistence and read API

**Files:**
- Create: `src/main/java/com/aitaskcenter/model/StoryRun.java`
- Create: `src/main/java/com/aitaskcenter/model/StoryRunStep.java`
- Create: `src/main/java/com/aitaskcenter/repository/StoryRunRepository.java`
- Create: `src/main/java/com/aitaskcenter/repository/StoryRunStepRepository.java`
- Create: `src/main/java/com/aitaskcenter/dto/StoryRunDtos.java`
- Create: `src/main/java/com/aitaskcenter/service/StoryRunQueryService.java`
- Create: `src/main/java/com/aitaskcenter/controller/StoryRunController.java`
- Test: `src/test/java/com/aitaskcenter/service/StoryRunQueryServiceTest.java`
- Test: `src/test/java/com/aitaskcenter/controller/StoryRunControllerTest.java`

- [ ] Write failing tests that require newest-first run summaries, ordered steps, full untruncated input/output, and 404-style validation for unknown run IDs.
- [ ] Run `mvn -B -ntp -Dtest=StoryRunQueryServiceTest,StoryRunControllerTest test` and confirm compilation/test RED is caused by missing run types.
- [ ] Add the two entities, repositories, DTO records, query service, and list/detail controller methods.
- [ ] Run the targeted tests and confirm GREEN.
- [ ] Commit `feat: persist story run history`.

### Task 2: Random word preview and run creation validation

**Files:**
- Modify: `src/main/java/com/aitaskcenter/service/ConnectionConfigService.java`
- Create: `src/main/java/com/aitaskcenter/service/StoryWordSourceService.java`
- Modify: `src/main/java/com/aitaskcenter/controller/StoryRunController.java`
- Modify: `src/main/java/com/aitaskcenter/dto/StoryRunDtos.java`
- Test: `src/test/java/com/aitaskcenter/service/StoryWordSourceServiceTest.java`
- Test: `src/test/java/com/aitaskcenter/controller/StoryRunControllerTest.java`

- [ ] Write failing tests for 1–50 manual words, case-insensitive de-duplication, parameterized random selection from `word_library`/`word`, unknown library, and invalid count.
- [ ] Run the targeted tests and confirm RED.
- [ ] Expose a package-safe configured-connection callback in `ConnectionConfigService`; implement read-only word source queries and request normalization.
- [ ] Add `POST /api/story-runs/random-words` and `POST /api/story-runs` contracts, with the latter delegating to the execution service introduced in Task 3.
- [ ] Run targeted tests and confirm GREEN.
- [ ] Commit `feat: prepare story run inputs`.

### Task 3: Bounded multi-Agent execution

**Files:**
- Modify: `src/main/java/com/aitaskcenter/service/AiTextGenerationService.java`
- Create: `src/main/java/com/aitaskcenter/service/StoryRunExecutionService.java`
- Create: `src/main/java/com/aitaskcenter/service/StoryRunStepService.java`
- Create: `src/main/java/com/aitaskcenter/config/StoryRunExecutorConfig.java`
- Modify: `src/main/java/com/aitaskcenter/controller/StoryRunController.java`
- Test: `src/test/java/com/aitaskcenter/service/StoryRunExecutionServiceTest.java`

- [ ] Write failing tests for fixed planning/pitch/director/writer/review/score/decision order, parallel groups appended deterministically, repeated quality-round steps, revision limit, Token stop, successful final result, and failed Agent persistence.
- [ ] Run the targeted test and confirm RED.
- [ ] Return generation text plus usage metadata from `AiTextGenerationService` without logging secrets.
- [ ] Implement a fixed-size executor, transaction-safe step appends, JSON input snapshots, story extraction, decision parsing, budget checks, and terminal run updates.
- [ ] Run targeted and full backend tests; confirm GREEN.
- [ ] Commit `feat: execute story agent runs`.

### Task 4: Frontend contracts and start-run dialog

**Files:**
- Modify: `web-react/src/story-flow-types.ts`
- Modify: `web-react/src/api.ts`
- Modify: `web-react/src/StoryAgentFlowPage.tsx`
- Modify: `web-react/src/StoryAgentFlowPage.test.tsx`

- [ ] Write failing tests for the “开始运行” button, manual input validation, random word preview, and successful creation opening the selected run record.
- [ ] Run `npm --prefix web-react test -- StoryAgentFlowPage.test.tsx` and confirm RED.
- [ ] Add run types/API methods and the start-run modal with manual/random source modes.
- [ ] Run targeted tests and confirm GREEN.
- [ ] Commit `feat: start story agent runs`.

### Task 5: Full-screen run record viewer

**Files:**
- Create: `web-react/src/StoryRunHistory.tsx`
- Create: `web-react/src/StoryRunHistory.test.tsx`
- Modify: `web-react/src/StoryAgentFlowPage.tsx`
- Modify: `web-react/src/styles.css`

- [ ] Write failing tests for the “运行记录” button beside quality budget, newest-first batch list, full single-line words, batch-to-step linkage, repeated Agent rows, input/output copy areas, polling an active run, and the bottom final-result quarter.
- [ ] Run `npm --prefix web-react test -- StoryRunHistory.test.tsx StoryAgentFlowPage.test.tsx` and confirm RED.
- [ ] Implement the full-screen viewer with fixed header, batch/Agent columns, side-by-side input/output, bottom final result, independent scrolling, responsive stacking, empty/loading/error states, and copy buttons.
- [ ] Run targeted and full frontend tests, then `npm --prefix web-react run build`; confirm GREEN with no new errors.
- [ ] Commit `feat: inspect story agent run history`.

### Task 6: Documentation, deployment, and acceptance

**Files:**
- Modify: `docs/backend/java_server/AGENTS.md`
- Modify: `docs/frontend/web_react/AGENTS.md`
- Modify: `docs/chains/story-agent-flow-config.md`
- Modify: `docs/shared/data-boundaries.md`

- [ ] Update current capability boundaries, tables, endpoints, execution lifecycle, read-only external word queries, and secret exclusions.
- [ ] Run `git diff --check`, full Maven tests/package, full frontend tests/build, and confirm a clean worktree after commits.
- [ ] Request an independent code review and address all Critical/Important findings with TDD.
- [ ] Call Context Router `apply_workspace_changes` once with every changed Workspace-relative file and poll to a terminal success state.
- [ ] Create one real bounded run with the local Provider; verify database run/step counts and that no API response contains an API key.
- [ ] Use the browser to verify the confirmed full-screen layout, batch/Agent linkage, complete input/output, final result quarter, manual start, and random preview.
