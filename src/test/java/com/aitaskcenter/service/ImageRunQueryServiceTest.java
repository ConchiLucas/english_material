package com.aitaskcenter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aitaskcenter.config.ImageAgentSnapshotContract;
import com.aitaskcenter.dto.ImageRunDtos.RunDetail;
import com.aitaskcenter.dto.ImageRunDtos.SourceStoryView;
import com.aitaskcenter.model.ImageAsset;
import com.aitaskcenter.model.ImageRun;
import com.aitaskcenter.model.ImageRunStep;
import com.aitaskcenter.model.ImageShot;
import com.aitaskcenter.model.StoryRun;
import com.aitaskcenter.repository.ImageAssetRepository;
import com.aitaskcenter.repository.ImageRunRepository;
import com.aitaskcenter.repository.ImageRunStepRepository;
import com.aitaskcenter.repository.ImageShotRepository;
import com.aitaskcenter.repository.StoryRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class ImageRunQueryServiceTest {
    private StoryRunRepository stories;
    private ImageRunRepository runs;
    private ImageRunStepRepository steps;
    private ImageShotRepository shots;
    private ImageAssetRepository assets;
    private ImageRunQueryService service;

    @BeforeEach
    void setUp() {
        stories = mock(StoryRunRepository.class);
        runs = mock(ImageRunRepository.class);
        steps = mock(ImageRunStepRepository.class);
        shots = mock(ImageShotRepository.class);
        assets = mock(ImageAssetRepository.class);
        service = new ImageRunQueryService(stories, runs, steps, shots, assets, new ObjectMapper());
    }

    @Test
    void listsOnlyUsableSourceStoriesNewestFirstWithoutOneBadSnapshotBreakingTheList() {
        StoryRun completed = story("story-completed", "COMPLETED", "A bright story.",
                "[{\"word\":\"book\",\"meaning\":\"书\"}]", "2026-08-15T10:00:00+08:00");
        StoryRun malformed = story("story-malformed", "LIMIT_REACHED", "Another story.",
                "[{\"word\":12,\"meaning\":\"错误\"}]", "2026-08-15T11:00:00+08:00");
        StoryRun oversized = story("story-oversized", "COMPLETED", "A".repeat(20_001), "[]",
                "2026-08-15T13:00:00+08:00");
        when(stories.findImageSourceStories(any(), eq(20_000), any(Pageable.class)))
                .thenReturn(List.of(completed, malformed, oversized));

        List<SourceStoryView> result = service.listSourceStories();

        assertThat(result).extracting(SourceStoryView::runId)
                .containsExactly("story-malformed", "story-completed");
        assertThat(result.get(0).words()).isEmpty();
        assertThat(result.get(0).wordsError()).isEqualTo("单词快照无法读取");
        assertThat(result.get(1).words()).singleElement().satisfies(word -> {
            assertThat(word.word()).isEqualTo("book");
            assertThat(word.meaning()).isEqualTo("书");
        });
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(stories).findImageSourceStories(any(), eq(20_000), page.capture());
        assertThat(page.getValue().getPageSize()).isLessThanOrEqualTo(100);
    }

    @Test
    void rejectsOversizedWordSnapshotBeforeInvokingJsonParser() {
        ObjectMapper guardedMapper = mock(ObjectMapper.class);
        ImageRunQueryService guarded = new ImageRunQueryService(
                stories, runs, steps, shots, assets, guardedMapper);
        StoryRun oversizedWords = story("story-oversized-words", "COMPLETED", "A story.",
                "x".repeat(64 * 1024 + 1), "2026-08-15T10:00:00+08:00");
        when(stories.findImageSourceStories(any(), eq(20_000), any(Pageable.class)))
                .thenReturn(List.of(oversizedWords));

        List<SourceStoryView> result = guarded.listSourceStories();

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.words()).isEmpty();
            assertThat(item.wordsError()).isEqualTo("单词快照无法读取");
        });
        verifyNoInteractions(guardedMapper);
    }

    @Test
    void acceptsExactWordSnapshotLimitAndIsolatesOversizeAlongsideNormalHistory() {
        StoryRun boundary = story("story-boundary", "COMPLETED", "Boundary story.",
                "[" + " ".repeat(64 * 1024 - 2) + "]", "2026-08-15T12:00:00+08:00");
        StoryRun oversized = story("story-oversized", "COMPLETED", "Oversized words story.",
                "x".repeat(64 * 1024 + 1), "2026-08-15T11:00:00+08:00");
        StoryRun normal = story("story-normal", "COMPLETED", "Normal story.",
                "[{\"word\":\"book\",\"meaning\":\"书\"}]", "2026-08-15T10:00:00+08:00");
        when(stories.findImageSourceStories(any(), eq(20_000), any(Pageable.class)))
                .thenReturn(List.of(normal, oversized, boundary));

        List<SourceStoryView> result = service.listSourceStories();

        assertThat(result).extracting(SourceStoryView::runId)
                .containsExactly("story-boundary", "story-oversized", "story-normal");
        assertThat(result.get(0).wordsError()).isNull();
        assertThat(result.get(1).wordsError()).isEqualTo("单词快照无法读取");
        assertThat(result.get(2).words()).singleElement().satisfies(word -> assertThat(word.word()).isEqualTo("book"));
    }

    @Test
    void listsImageRunsNewestFirstAndRepresentsMalformedWordsAsABoundedError() {
        ImageRun older = imageRun("image-old", "[{\"word\":\"book\",\"meaning\":\"书\"}]",
                "2026-08-15T10:00:00+08:00");
        ImageRun newer = imageRun("image-new", "not-json", "2026-08-15T12:00:00+08:00");
        when(runs.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(List.of(older, newer));

        var result = service.listRuns();

        assertThat(result).extracting(item -> item.runId()).containsExactly("image-new", "image-old");
        assertThat(result.get(0).words()).isEmpty();
        assertThat(result.get(0).wordsError()).isEqualTo("单词快照无法读取");
        assertThat(result.get(1).stylePresetName()).isEqualTo("Paper Cut");
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(runs).findAllByOrderByCreatedAtDesc(page.capture());
        assertThat(page.getValue().getPageSize()).isLessThanOrEqualTo(100);
    }

    @Test
    void returnsCompleteDetailInDeterministicOrderWithoutStoragePathsOrSecrets() throws Exception {
        ImageRun run = imageRun("image-1", "[{\"word\":\"book\",\"meaning\":\"书\"}]",
                "2026-08-15T10:00:00+08:00");
        ImageRunStep secondStep = step(2, "image-art-director");
        ImageRunStep firstStep = step(1, "image-story-analyst");
        ImageShot secondShot = shot(2, "scene-1-shot-2");
        ImageShot firstShot = shot(1, "scene-1-shot-1");
        ImageAsset finalAsset = asset(12L, "FINAL", "scene-1-shot-1", "secret/run/final.png");
        finalAsset.setMetadataJson("""
                {"responseFormat":"b64_json","quality":"high","size":"1536x864",
                 "referenceType":"CHARACTER","target":"character-toby",
                 "referenceAssetKeys":["character-toby"],"compositor":"java2d",
                 "usage":{"token":"sk-secret"},"Authorization":"Bearer secret","unknown":"/Users/private"}
                """);
        ImageAsset referenceAsset = asset(11L, "REFERENCE", "character-toby", "/absolute/secret.png");
        referenceAsset.setMetadataJson("""
                {"target":"sk-do-not-leak","referenceAssetKeys":["token-secret","a..b"],
                 "usage":{"Authorization":"Bearer secret"},"path":"/Users/private/key"}
                """);
        when(runs.findByRunId("image-1")).thenReturn(java.util.Optional.of(run));
        when(steps.findAllByRunIdOrderBySequenceAsc(eq("image-1"), any(Pageable.class)))
                .thenReturn(List.of(secondStep, firstStep));
        when(shots.findAllByRunIdOrderBySequenceAsc(eq("image-1"), any(Pageable.class)))
                .thenReturn(List.of(secondShot, firstShot));
        when(assets.findAllByRunIdOrderByAssetTypeAscAssetKeyAsc(eq("image-1"), any(Pageable.class)))
                .thenReturn(List.of(referenceAsset, finalAsset));

        RunDetail detail = service.getRun("image-1");

        assertThat(detail.steps()).extracting(item -> item.sequence()).containsExactly(1, 2);
        assertThat(detail.shots()).extracting(item -> item.sequence()).containsExactly(1, 2);
        assertThat(detail.assets()).extracting(item -> item.assetType() + ":" + item.assetKey())
                .containsExactly("FINAL:scene-1-shot-1", "REFERENCE:character-toby");
        assertThat(detail.steps().get(0).inputJson()).isEqualTo("{\"input\":true}");
        assertThat(detail.steps().get(0).rawOutput()).isEqualTo("raw-image-story-analyst");
        assertThat(detail.steps().get(0).parsedOutputJson()).isEqualTo("{\"parsed\":true}");
        assertThat(detail.shots().get(0).dialogue()).isEqualTo("Hello!");
        assertThat(detail.shots().get(0).caption()).isEqualTo("Toby opens the book.");
        assertThat(detail.agentSnapshots()).hasSize(9);
        assertThat(detail.agentSnapshots().get(0)).satisfies(snapshot -> {
            assertThat(snapshot.key()).isEqualTo("image-story-analyst");
            assertThat(snapshot.systemPrompt()).isEqualTo("Analyze the saved story into visual beats.");
            assertThat(snapshot.temperature()).isEqualTo(0.35d);
            assertThat(snapshot.providerModel()).isEqualTo("text-model-v1");
            assertThat(snapshot.capabilities()).containsExactly("TEXT_GENERATION");
        });
        assertThat(detail.agentSnapshotError()).isNull();
        assertThat(detail.agentSnapshotSchemaVersion()).isEqualTo(1);
        assertThat(detail.assets().get(1).contentUrl()).isEqualTo("/api/image-assets/11/content");
        assertThat(detail.assets().get(0).providerMetadataJson()).isEqualTo(
                "{\"responseFormat\":\"b64_json\",\"quality\":\"high\",\"size\":\"1536x864\","
                        + "\"referenceType\":\"CHARACTER\",\"target\":\"character-toby\","
                        + "\"referenceAssetKeys\":[\"character-toby\"],\"compositor\":\"java2d\"}");
        assertThat(detail.assets().get(1).providerMetadataJson()).isNull();
        String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(detail);
        assertThat(serialized).doesNotContain("absolute", "secret.png", "Bearer secret", "Authorization",
                "sk-do-not-leak", "token-secret", "a..b", "/Users/private", "usage");
        ArgumentCaptor<Pageable> stepPage = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Pageable> shotPage = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<Pageable> assetPage = ArgumentCaptor.forClass(Pageable.class);
        verify(steps).findAllByRunIdOrderBySequenceAsc(eq("image-1"), stepPage.capture());
        verify(shots).findAllByRunIdOrderBySequenceAsc(eq("image-1"), shotPage.capture());
        verify(assets).findAllByRunIdOrderByAssetTypeAscAssetKeyAsc(eq("image-1"), assetPage.capture());
        assertThat(stepPage.getValue().getPageSize()).isEqualTo(13);
        assertThat(shotPage.getValue().getPageSize()).isEqualTo(21);
        assertThat(assetPage.getValue().getPageSize()).isEqualTo(61);
    }

    @Test
    void isolatesUnsafeOrMalformedAgentSnapshotsWithoutSerializingSecrets() throws Exception {
        ImageRun unsafe = imageRun("image-unsafe", "[]", "2026-08-15T10:00:00+08:00");
        unsafe.setAgentSnapshotJson("""
                [{
                  "sequence": 1,
                  "stageKey": "story-understanding",
                  "key": "image-story-analyst",
                  "name": "故事结构分析",
                  "systemPrompt": "Analyze the story.",
                  "promptVersion": 1,
                  "temperature": 0.3,
                  "provider": {
                    "id": "text-provider",
                    "label": "Text Provider",
                    "type": "OPENAI_COMPATIBLE",
                    "model": "text-model-v1",
                    "maxTokens": 4096,
                    "capabilities": ["TEXT_GENERATION"],
                    "options": {},
                    "apiKey": "sk-do-not-leak",
                    "baseUrl": "/Users/private/provider",
                    "unknownSecret": "hidden"
                  }
                }]
                """);
        when(runs.findByRunId("image-unsafe")).thenReturn(java.util.Optional.of(unsafe));

        RunDetail detail = service.getRun("image-unsafe");
        String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(detail);

        assertThat(detail.agentSnapshots()).isEmpty();
        assertThat(detail.agentSnapshotError()).isEqualTo("Agent 运行快照无法读取");
        assertThat(serialized).doesNotContain("sk-do-not-leak", "/Users/private/provider",
                "apiKey", "baseUrl", "unknownSecret");

        ImageRun malformed = imageRun("image-malformed-agent", "[]", "2026-08-15T10:00:00+08:00");
        malformed.setAgentSnapshotJson("[{broken]");
        when(runs.findByRunId("image-malformed-agent")).thenReturn(java.util.Optional.of(malformed));

        RunDetail malformedDetail = service.getRun("image-malformed-agent");
        assertThat(malformedDetail.agentSnapshots()).isEmpty();
        assertThat(malformedDetail.agentSnapshotError()).isEqualTo("Agent 运行快照无法读取");
    }

    @Test
    void requiresTheCompleteCatalogWithExactStagesAndContiguousSequence() throws Exception {
        ArrayNode missing = agentSnapshotsNode();
        missing.remove(missing.size() - 1);
        assertAgentSnapshotRejected("missing", missing);

        ArrayNode programNode = agentSnapshotsNode();
        ((ObjectNode) programNode.get(0)).put("key", "reference-image-generator");
        assertAgentSnapshotRejected("program-node", programNode);

        ArrayNode unknownKey = agentSnapshotsNode();
        ((ObjectNode) unknownKey.get(0)).put("key", "unknown-agent");
        assertAgentSnapshotRejected("unknown-key", unknownKey);

        ArrayNode wrongStage = agentSnapshotsNode();
        ((ObjectNode) wrongStage.get(0)).put("stageKey", "delivery");
        assertAgentSnapshotRejected("wrong-stage", wrongStage);

        ArrayNode sequenceGap = agentSnapshotsNode();
        ((ObjectNode) sequenceGap.get(sequenceGap.size() - 1)).put("sequence", 10);
        assertAgentSnapshotRejected("sequence-gap", sequenceGap);

        ArrayNode wrongSequence = agentSnapshotsNode();
        ((ObjectNode) wrongSequence.get(0)).put("sequence", 2);
        ((ObjectNode) wrongSequence.get(1)).put("sequence", 1);
        assertAgentSnapshotRejected("wrong-sequence", wrongSequence);
    }

    @Test
    void readsLegacyV1FromTheFrozenContractAndRejectsUnknownEnvelopeVersions() throws Exception {
        ImageRun legacy = imageRun("image-legacy", "[]", "2026-08-15T10:00:00+08:00");
        legacy.setAgentSnapshotJson(new ObjectMapper().writeValueAsString(agentSnapshotsNode()));
        when(runs.findByRunId("image-legacy")).thenReturn(java.util.Optional.of(legacy));

        RunDetail legacyDetail = service.getRun("image-legacy");

        assertThat(legacyDetail.agentSnapshotSchemaVersion()).isEqualTo(1);
        assertThat(legacyDetail.agentSnapshots()).extracting(item -> item.key())
                .containsExactlyElementsOf(ImageAgentSnapshotContract.v1Agents().stream()
                        .map(item -> item.key()).toList());

        ObjectNode unknownEnvelope = new ObjectMapper().createObjectNode();
        unknownEnvelope.put("schemaVersion", 99);
        unknownEnvelope.set("agents", agentSnapshotsNode());
        ImageRun unknown = imageRun("image-unknown-schema", "[]", "2026-08-15T10:00:00+08:00");
        unknown.setAgentSnapshotJson(new ObjectMapper().writeValueAsString(unknownEnvelope));
        when(runs.findByRunId("image-unknown-schema")).thenReturn(java.util.Optional.of(unknown));

        RunDetail unknownDetail = service.getRun("image-unknown-schema");
        assertThat(unknownDetail.agentSnapshotSchemaVersion()).isNull();
        assertThat(unknownDetail.agentSnapshots()).isEmpty();
        assertThat(unknownDetail.agentSnapshotError()).isEqualTo("Agent 运行快照无法读取");
    }

    @Test
    void capsUnpagedRunAndSourceHistoryAtOneHundredDefensively() {
        List<ImageRun> imageRuns = new ArrayList<>();
        List<StoryRun> sourceRuns = new ArrayList<>();
        for (int index = 0; index < 105; index++) {
            imageRuns.add(imageRun("image-" + index, "[]", "2026-08-15T10:00:00+08:00"));
            sourceRuns.add(story("story-" + index, "COMPLETED", "Story " + index, "[]",
                    "2026-08-15T10:00:00+08:00"));
        }
        when(runs.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(imageRuns);
        when(stories.findImageSourceStories(any(), eq(20_000), any(Pageable.class))).thenReturn(sourceRuns);

        assertThat(service.listRuns()).hasSize(100);
        assertThat(service.listSourceStories()).hasSize(100);
    }

    @Test
    void boundsDetailCollectionsAndRejectsOversizedHistory() {
        ImageRun corruptSteps = imageRun("image-corrupt-steps", "[]", "2026-08-15T10:00:00+08:00");
        when(runs.findByRunId("image-corrupt-steps")).thenReturn(java.util.Optional.of(corruptSteps));
        when(steps.findAllByRunIdOrderBySequenceAsc(eq("image-corrupt-steps"), any(Pageable.class)))
                .thenReturn(java.util.Collections.nCopies(13, step(1, "image-story-analyst")));
        assertThatThrownBy(() -> service.getRun("image-corrupt-steps"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("图片运行历史步骤数量超限");

        ImageRun corruptShots = imageRun("image-corrupt-shots", "[]", "2026-08-15T10:00:00+08:00");
        when(runs.findByRunId("image-corrupt-shots")).thenReturn(java.util.Optional.of(corruptShots));
        when(shots.findAllByRunIdOrderBySequenceAsc(eq("image-corrupt-shots"), any(Pageable.class)))
                .thenReturn(java.util.Collections.nCopies(21, shot(1, "scene-1-shot-1")));
        assertThatThrownBy(() -> service.getRun("image-corrupt-shots"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("图片运行历史分镜数量超限");

        ImageRun corruptAssets = imageRun("image-corrupt-assets", "[]", "2026-08-15T10:00:00+08:00");
        when(runs.findByRunId("image-corrupt-assets")).thenReturn(java.util.Optional.of(corruptAssets));
        when(assets.findAllByRunIdOrderByAssetTypeAscAssetKeyAsc(eq("image-corrupt-assets"), any(Pageable.class)))
                .thenReturn(java.util.Collections.nCopies(61, asset(1L, "FINAL", "scene-1-shot-1", "safe/a.png")));
        assertThatThrownBy(() -> service.getRun("image-corrupt-assets"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("图片运行历史资产数量超限");
    }

    @Test
    void rejectsMissingRunWithBoundedMessage() {
        when(runs.findByRunId("missing")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getRun("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("图片运行批次不存在");
    }

    private StoryRun story(String runId, String status, String finalStory, String words, String createdAt) {
        StoryRun run = new StoryRun();
        run.setRunId(runId);
        run.setStatus(status);
        run.setFinalStory(finalStory);
        run.setInputWordsJson(words);
        run.setTargetGrade("小学三年级上册");
        ReflectionTestUtils.setField(run, "createdAt", OffsetDateTime.parse(createdAt));
        return run;
    }

    private ImageRun imageRun(String runId, String words, String createdAt) {
        ImageRun run = new ImageRun();
        run.setRunId(runId);
        run.setStoryRunId("story-1");
        run.setInputWordsJson(words);
        run.setTargetGrade("小学三年级上册");
        run.setStorySnapshot("Scene 1\nToby opens a book.");
        run.setStylePresetId("7");
        run.setStyleSnapshotJson("{\"id\":7,\"name\":\"Paper Cut\",\"positivePrompt\":\"paper\"}");
        run.setFlowSnapshotJson("{\"width\":1536,\"height\":864,\"imageProvider\":{\"id\":\"image-provider\",\"model\":\"image-v1\"}}");
        run.setAgentSnapshotJson(agentSnapshotsJson());
        run.setStatus("COMPLETED");
        run.setExpectedImageCount(2);
        run.setGeneratedImageCount(2);
        run.setTotalTextTokens(123);
        ReflectionTestUtils.setField(run, "createdAt", OffsetDateTime.parse(createdAt));
        return run;
    }

    private ImageRunStep step(int sequence, String key) {
        ImageRunStep step = new ImageRunStep();
        step.setId((long) sequence);
        step.setSequence(sequence);
        step.setStageKey("story-understanding");
        step.setNodeKey(key);
        step.setNodeName(key);
        step.setNodeKind("AGENT");
        step.setPromptVersion(1);
        step.setProviderId("text-provider");
        step.setProviderModel("text-model");
        step.setInputJson("{\"input\":true}");
        step.setRawOutput("raw-" + key);
        step.setParsedOutputJson("{\"parsed\":true}");
        step.setStatus("COMPLETED");
        step.setInputTokens(10);
        step.setOutputTokens(20);
        step.setTotalTokens(30);
        step.setDurationMs(40);
        return step;
    }

    private ImageShot shot(int sequence, String key) {
        ImageShot shot = new ImageShot();
        shot.setId((long) sequence);
        shot.setSequence(sequence);
        shot.setShotKey(key);
        shot.setSceneIndex(1);
        shot.setShotIndex(sequence);
        shot.setSourceExcerpt("Toby opens a book.");
        shot.setVisualGoal("Show the action clearly.");
        shot.setSpeaker("Toby");
        shot.setDialogue("Hello!");
        shot.setCaption("Toby opens the book.");
        shot.setTextAnchorJson("{\"x\":0.3,\"y\":0.2}");
        shot.setPrompt("Toby opens a book, no text");
        shot.setNegativePrompt("letters, watermark");
        shot.setReferenceAssetKeysJson("[\"character-toby\"]");
        shot.setStatus("COMPLETED");
        return shot;
    }

    private ImageAsset asset(Long id, String type, String key, String relativePath) {
        ImageAsset asset = new ImageAsset();
        asset.setId(id);
        asset.setAssetType(type);
        asset.setAssetKey(key);
        asset.setShotKey(type.equals("REFERENCE") ? null : "scene-1-shot-1");
        asset.setRelativePath(relativePath);
        asset.setMime("image/png");
        asset.setWidth(1536);
        asset.setHeight(864);
        asset.setSha256("a".repeat(64));
        asset.setProviderId("image-provider");
        asset.setProviderModel("image-v1");
        asset.setProviderRequestId("request-1");
        asset.setPrompt("prompt");
        asset.setNegativePrompt("negative");
        asset.setMetadataJson("{\"usage\":3}");
        return asset;
    }

    private void assertAgentSnapshotRejected(String suffix, ArrayNode snapshot) throws Exception {
        ImageRun run = imageRun("image-" + suffix, "[]", "2026-08-15T10:00:00+08:00");
        run.setAgentSnapshotJson(new ObjectMapper().writeValueAsString(snapshot));
        when(runs.findByRunId("image-" + suffix)).thenReturn(java.util.Optional.of(run));

        RunDetail detail = service.getRun("image-" + suffix);

        assertThat(detail.agentSnapshots()).as(suffix).isEmpty();
        assertThat(detail.agentSnapshotError()).as(suffix).isEqualTo("Agent 运行快照无法读取");
    }

    private String agentSnapshotsJson() {
        try {
            ObjectNode envelope = new ObjectMapper().createObjectNode();
            envelope.put("schemaVersion", 1);
            envelope.set("agents", agentSnapshotsNode());
            return new ObjectMapper().writeValueAsString(envelope);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private ArrayNode agentSnapshotsNode() {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode snapshots = mapper.createArrayNode();
        int sequence = 1;
        for (ImageAgentSnapshotContract.AgentDefinition agent : ImageAgentSnapshotContract.v1Agents()) {
            ObjectNode provider = mapper.createObjectNode();
            provider.put("id", "text-provider");
            provider.put("label", "Text Provider");
            provider.put("type", "OPENAI_COMPATIBLE");
            provider.put("model", "text-model-v1");
            provider.put("maxTokens", 4096);
            provider.putArray("capabilities").add("TEXT_GENERATION");
            provider.putObject("options");
            ObjectNode snapshot = snapshots.addObject();
            snapshot.put("sequence", sequence);
            snapshot.put("stageKey", agent.stageKey());
            snapshot.put("key", agent.key());
            snapshot.put("name", "历史显示名 " + sequence);
            snapshot.put("systemPrompt", sequence == 1
                    ? "Analyze the saved story into visual beats."
                    : "Historical prompt for " + agent.key());
            snapshot.put("promptVersion", sequence + 2);
            snapshot.put("temperature", sequence == 1 ? 0.35d : 0.2d);
            snapshot.set("provider", provider);
            sequence++;
        }
        assertThat(snapshots).hasSize(9);
        return snapshots;
    }
}
