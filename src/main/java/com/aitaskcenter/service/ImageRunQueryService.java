package com.aitaskcenter.service;

import com.aitaskcenter.config.ImageAgentCatalog;
import com.aitaskcenter.dto.ImageRunDtos.AssetView;
import com.aitaskcenter.dto.ImageRunDtos.AgentSnapshotView;
import com.aitaskcenter.dto.ImageRunDtos.RunDetail;
import com.aitaskcenter.dto.ImageRunDtos.RunStepView;
import com.aitaskcenter.dto.ImageRunDtos.RunSummary;
import com.aitaskcenter.dto.ImageRunDtos.ShotView;
import com.aitaskcenter.dto.ImageRunDtos.SourceStoryView;
import com.aitaskcenter.dto.StoryRunDtos.StoryWord;
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
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ImageRunQueryService {
    private static final int MAX_WORDS = 50;
    private static final int MAX_WORD_LENGTH = 120;
    private static final int MAX_MEANING_LENGTH = 500;
    private static final int MAX_METADATA_LENGTH = 32_000;
    private static final int MAX_AGENT_SNAPSHOT_LENGTH = 256_000;
    private static final int MAX_AGENT_PROMPT_LENGTH = 20_000;
    private static final Set<String> AGENT_SNAPSHOT_FIELDS = Set.of(
            "sequence", "stageKey", "key", "name", "systemPrompt", "promptVersion", "temperature", "provider");
    private static final Set<String> PROVIDER_SNAPSHOT_FIELDS = Set.of(
            "id", "label", "type", "model", "maxTokens", "capabilities", "options");
    private static final Set<String> SOURCE_STATUSES = Set.of("COMPLETED", "LIMIT_REACHED");
    private static final Set<String> SAFE_METADATA_KEYS = Set.of(
            "responseFormat", "quality", "size", "referenceType", "target",
            "referenceAssetKeys", "compositor", "usage");
    private static final Comparator<OffsetDateTime> NEWEST_TIME =
            Comparator.nullsLast(Comparator.reverseOrder());
    private static final Comparator<String> SAFE_TEXT =
            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);

    private final StoryRunRepository storyRepository;
    private final ImageRunRepository runRepository;
    private final ImageRunStepRepository stepRepository;
    private final ImageShotRepository shotRepository;
    private final ImageAssetRepository assetRepository;
    private final ObjectMapper objectMapper;

    public ImageRunQueryService(
            StoryRunRepository storyRepository,
            ImageRunRepository runRepository,
            ImageRunStepRepository stepRepository,
            ImageShotRepository shotRepository,
            ImageAssetRepository assetRepository,
            ObjectMapper objectMapper) {
        this.storyRepository = storyRepository;
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.shotRepository = shotRepository;
        this.assetRepository = assetRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<SourceStoryView> listSourceStories() {
        return storyRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(run -> SOURCE_STATUSES.contains(clean(run.getStatus()).toUpperCase(Locale.ROOT)))
                .filter(run -> StringUtils.hasText(run.getFinalStory()))
                .sorted(Comparator.comparing(StoryRun::getCreatedAt, NEWEST_TIME))
                .map(this::toSourceStory)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RunSummary> listRuns() {
        return runRepository.findAllByOrderByCreatedAtDesc().stream()
                .sorted(Comparator.comparing(ImageRun::getCreatedAt, NEWEST_TIME))
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public RunDetail getRun(String runId) {
        String safeRunId = clean(runId);
        if (!StringUtils.hasText(safeRunId) || safeRunId.length() > 64) {
            throw new IllegalArgumentException("图片运行批次不存在");
        }
        ImageRun run = runRepository.findByRunId(safeRunId)
                .orElseThrow(() -> new IllegalArgumentException("图片运行批次不存在"));
        List<RunStepView> steps = stepRepository.findAllByRunIdOrderBySequenceAsc(safeRunId).stream()
                .sorted(Comparator.comparingInt(ImageRunStep::getSequence).thenComparing(ImageRunStep::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toStep)
                .toList();
        List<ShotView> shots = shotRepository.findAllByRunIdOrderBySequenceAsc(safeRunId).stream()
                .sorted(Comparator.comparingInt(ImageShot::getSequence).thenComparing(ImageShot::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toShot)
                .toList();
        List<AssetView> assets = assetRepository.findAllByRunIdOrderByAssetTypeAscAssetKeyAsc(safeRunId).stream()
                .sorted(Comparator.comparing(ImageAsset::getAssetType, SAFE_TEXT)
                        .thenComparing(ImageAsset::getAssetKey, SAFE_TEXT)
                        .thenComparing(ImageAsset::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toAsset)
                .toList();
        ParsedWords words = parseWordsSafely(run.getInputWordsJson());
        ParsedAgentSnapshots agentSnapshots = parseAgentSnapshotsSafely(run.getAgentSnapshotJson());
        return new RunDetail(run.getRunId(), run.getStoryRunId(), words.values(), words.error(), run.getTargetGrade(),
                run.getStatus(), run.getStorySnapshot(), run.getStylePresetId(), styleName(run.getStyleSnapshotJson()),
                run.getStyleSnapshotJson(), run.getFlowSnapshotJson(), agentSnapshots.values(), agentSnapshots.error(),
                run.getExpectedImageCount(),
                run.getGeneratedImageCount(), run.getTotalTextTokens(), run.getErrorMessage(), run.getCreatedAt(),
                run.getStartedAt(), run.getFinishedAt(), steps, shots, assets);
    }

    private SourceStoryView toSourceStory(StoryRun run) {
        ParsedWords words = parseWordsSafely(run.getInputWordsJson());
        return new SourceStoryView(run.getRunId(), words.values(), words.error(), run.getTargetGrade(), run.getStatus(),
                run.getFinalStory(), run.getCreatedAt(), run.getFinishedAt());
    }

    private RunSummary toSummary(ImageRun run) {
        ParsedWords words = parseWordsSafely(run.getInputWordsJson());
        return new RunSummary(run.getRunId(), run.getStoryRunId(), styleId(run.getStylePresetId()),
                styleName(run.getStyleSnapshotJson()), run.getTargetGrade(), words.values(), words.error(),
                run.getStatus(), run.getExpectedImageCount(), run.getGeneratedImageCount(), run.getTotalTextTokens(),
                run.getErrorMessage(), run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt());
    }

    private RunStepView toStep(ImageRunStep step) {
        return new RunStepView(step.getId(), step.getSequence(), step.getStageKey(), step.getNodeKey(),
                step.getNodeName(), step.getNodeKind(), step.getPromptVersion(), step.getProviderId(),
                step.getProviderModel(), step.getInputJson(), step.getRawOutput(), step.getParsedOutputJson(),
                step.getErrorMessage(), step.getStatus(), step.getInputTokens(), step.getOutputTokens(),
                step.getTotalTokens(), step.getDurationMs(), step.getStartedAt(), step.getFinishedAt(),
                step.getCreatedAt());
    }

    private ShotView toShot(ImageShot shot) {
        return new ShotView(shot.getId(), shot.getShotKey(), shot.getSceneIndex(), shot.getShotIndex(),
                shot.getSequence(), shot.getSourceExcerpt(), shot.getVisualGoal(), shot.getSpeaker(),
                shot.getDialogue(), shot.getCaption(), shot.getTextAnchorJson(), shot.getPrompt(),
                shot.getNegativePrompt(), shot.getReferenceAssetKeysJson(), shot.getStatus(), shot.getCreatedAt());
    }

    private AssetView toAsset(ImageAsset asset) {
        return new AssetView(asset.getId(), asset.getAssetType(), asset.getAssetKey(), asset.getShotKey(),
                asset.getMime(), asset.getWidth(), asset.getHeight(), asset.getSha256(), asset.getProviderId(),
                asset.getProviderModel(), asset.getProviderRequestId(), asset.getPrompt(), asset.getNegativePrompt(),
                safeMetadata(asset.getMetadataJson()), "/api/image-assets/" + asset.getId() + "/content",
                asset.getCreatedAt());
    }

    private ParsedWords parseWordsSafely(String json) {
        try {
            JsonNode root;
            try (JsonParser parser = objectMapper.createParser(json)) {
                parser.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
                root = objectMapper.readTree(parser);
                if (parser.nextToken() != null) throw invalidWords();
            }
            if (root == null || !root.isArray() || root.size() > MAX_WORDS) throw invalidWords();
            List<StoryWord> result = new ArrayList<>();
            for (JsonNode item : root) {
                if (!item.isObject() || item.size() != 2 || !item.has("word") || !item.has("meaning")
                        || !item.path("word").isTextual() || !item.path("meaning").isTextual()) {
                    throw invalidWords();
                }
                String word = item.path("word").textValue().trim();
                String meaning = item.path("meaning").textValue().trim();
                if (!StringUtils.hasText(word) || word.length() > MAX_WORD_LENGTH
                        || !StringUtils.hasText(meaning) || meaning.length() > MAX_MEANING_LENGTH) {
                    throw invalidWords();
                }
                result.add(new StoryWord(word, meaning));
            }
            return new ParsedWords(List.copyOf(result), null);
        } catch (Exception exception) {
            return new ParsedWords(List.of(), "单词快照无法读取");
        }
    }

    private ParsedAgentSnapshots parseAgentSnapshotsSafely(String json) {
        try {
            if (!StringUtils.hasText(json) || json.length() > MAX_AGENT_SNAPSHOT_LENGTH) {
                throw invalidAgentSnapshots();
            }
            JsonNode root;
            try (JsonParser parser = objectMapper.createParser(json)) {
                parser.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
                root = objectMapper.readTree(parser);
                if (parser.nextToken() != null) throw invalidAgentSnapshots();
            }
            if (root == null || !root.isArray() || root.size() != 9) throw invalidAgentSnapshots();
            List<AgentSnapshotView> result = new ArrayList<>();
            Set<Integer> sequences = new HashSet<>();
            Set<String> keys = new HashSet<>();
            for (JsonNode item : root) {
                if (!item.isObject() || !fieldNames(item).equals(AGENT_SNAPSHOT_FIELDS)) {
                    throw invalidAgentSnapshots();
                }
                int sequence = requiredInt(item.get("sequence"), 1, 9);
                String stageKey = requiredText(item.get("stageKey"), 80, true);
                String key = requiredText(item.get("key"), 80, true);
                String name = requiredText(item.get("name"), 120, false);
                String systemPrompt = requiredPrompt(item.get("systemPrompt"));
                int promptVersion = requiredInt(item.get("promptVersion"), 1, Integer.MAX_VALUE);
                double temperature = requiredTemperature(item.get("temperature"));
                JsonNode provider = item.get("provider");
                if (provider == null || !provider.isObject() || !fieldNames(provider).equals(PROVIDER_SNAPSHOT_FIELDS)) {
                    throw invalidAgentSnapshots();
                }
                JsonNode options = provider.get("options");
                if (options == null || !options.isObject() || !options.isEmpty()) throw invalidAgentSnapshots();
                String providerId = requiredText(provider.get("id"), 120, false);
                String providerLabel = requiredText(provider.get("label"), 200, false);
                String providerType = requiredText(provider.get("type"), 80, false);
                String providerModel = requiredText(provider.get("model"), 180, false);
                Integer maxTokens = optionalPositiveInt(provider.get("maxTokens"));
                List<String> capabilities = requiredCapabilities(provider.get("capabilities"));
                if (!sequences.add(sequence) || !keys.add(key)) throw invalidAgentSnapshots();
                result.add(new AgentSnapshotView(sequence, stageKey, key, name, systemPrompt, promptVersion,
                        temperature, providerId, providerLabel, providerType, providerModel, maxTokens, capabilities));
            }
            result.sort(Comparator.comparingInt(AgentSnapshotView::sequence));
            validateCompleteAgentCatalog(result);
            return new ParsedAgentSnapshots(List.copyOf(result), null);
        } catch (Exception exception) {
            return new ParsedAgentSnapshots(List.of(), "Agent 运行快照无法读取");
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void validateCompleteAgentCatalog(List<AgentSnapshotView> snapshots) {
        List<ImageAgentCatalog.NodeDefinition> expected = ImageAgentCatalog.agents();
        if (expected.size() != 9 || snapshots.size() != expected.size()) throw invalidAgentSnapshots();
        for (int index = 0; index < expected.size(); index++) {
            AgentSnapshotView actual = snapshots.get(index);
            ImageAgentCatalog.NodeDefinition definition = expected.get(index);
            String expectedStage = ImageAgentCatalog.stages().stream()
                    .filter(stage -> stage.nodes().stream().anyMatch(node -> node.key().equals(definition.key())))
                    .findFirst()
                    .orElseThrow(ImageRunQueryService::invalidAgentSnapshots)
                    .key();
            if (actual.sequence() != index + 1 || !actual.key().equals(definition.key())
                    || !actual.stageKey().equals(expectedStage)) {
                throw invalidAgentSnapshots();
            }
        }
    }

    private static String requiredText(JsonNode node, int maxLength, boolean identifier) {
        if (node == null || !node.isTextual()) throw invalidAgentSnapshots();
        String value = node.textValue().trim();
        if (!StringUtils.hasText(value) || value.length() > maxLength
                || (identifier && !value.matches("[a-z0-9][a-z0-9-]*"))) {
            throw invalidAgentSnapshots();
        }
        return value;
    }

    private static int requiredInt(JsonNode node, int minimum, int maximum) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) throw invalidAgentSnapshots();
        int value = node.intValue();
        if (value < minimum || value > maximum) throw invalidAgentSnapshots();
        return value;
    }

    private static String requiredPrompt(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalidAgentSnapshots();
        String value = node.textValue();
        if (!StringUtils.hasText(value) || value.length() > MAX_AGENT_PROMPT_LENGTH) throw invalidAgentSnapshots();
        return value;
    }

    private static double requiredTemperature(JsonNode node) {
        if (node == null || !node.isNumber()) throw invalidAgentSnapshots();
        double value = node.doubleValue();
        if (!Double.isFinite(value) || value < 0.0d || value > 2.0d) throw invalidAgentSnapshots();
        return value;
    }

    private static Integer optionalPositiveInt(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return requiredInt(node, 1, 1_000_000);
    }

    private static List<String> requiredCapabilities(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 16) throw invalidAgentSnapshots();
        List<String> values = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode item : node) {
            String capability = requiredText(item, 80, false);
            if (!unique.add(capability)) throw invalidAgentSnapshots();
            values.add(capability);
        }
        return List.copyOf(values);
    }

    private String safeMetadata(String json) {
        if (!StringUtils.hasText(json) || json.length() > MAX_METADATA_LENGTH) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) return null;
            ObjectNode safe = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (SAFE_METADATA_KEYS.contains(field.getKey()) && safeMetadataValue(field.getValue(), 0)) {
                    safe.set(field.getKey(), field.getValue());
                }
            }
            return safe.isEmpty() ? null : objectMapper.writeValueAsString(safe);
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean safeMetadataValue(JsonNode value, int depth) {
        if (value == null || depth > 3) return false;
        if (value.isTextual()) return value.textValue().length() <= 500;
        if (value.isNumber() || value.isBoolean() || value.isNull()) return true;
        if (value.isArray()) {
            if (value.size() > 20) return false;
            for (JsonNode child : value) if (!safeMetadataValue(child, depth + 1)) return false;
            return true;
        }
        if (value.isObject()) {
            if (value.size() > 20) return false;
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (sensitiveKey(field.getKey()) || !safeMetadataValue(field.getValue(), depth + 1)) return false;
            }
            return true;
        }
        return false;
    }

    private static boolean sensitiveKey(String key) {
        String value = clean(key).toLowerCase(Locale.ROOT);
        return value.contains("key") || value.contains("secret") || value.contains("auth")
                || value.contains("header") || value.contains("cookie") || value.contains("credential");
    }

    private String styleName(String snapshot) {
        if (!StringUtils.hasText(snapshot) || snapshot.length() > MAX_METADATA_LENGTH) return null;
        try {
            JsonNode root = objectMapper.readTree(snapshot);
            JsonNode name = root == null ? null : root.get("name");
            return name != null && name.isTextual() && name.textValue().length() <= 200 ? name.textValue() : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static Long styleId(String value) {
        try {
            long id = Long.parseLong(clean(value));
            return id > 0 ? id : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private static IllegalArgumentException invalidWords() {
        return new IllegalArgumentException("单词快照无法读取");
    }

    private static IllegalArgumentException invalidAgentSnapshots() {
        return new IllegalArgumentException("Agent 运行快照无法读取");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private record ParsedWords(List<StoryWord> values, String error) {
    }

    private record ParsedAgentSnapshots(List<AgentSnapshotView> values, String error) {
    }
}
