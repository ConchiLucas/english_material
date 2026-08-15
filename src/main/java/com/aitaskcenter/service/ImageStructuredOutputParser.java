package com.aitaskcenter.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the auditable, marker-bounded JSON outputs emitted by image-planning agents. */
public final class ImageStructuredOutputParser {
    private static final int MAX_SHOTS_PER_SCENE = 5;
    private static final int MAX_SHOTS_PER_STORY = 20;
    private static final int MAX_CAPTION_LENGTH = 180;
    private static final Pattern DUPLICATE_FIELD = Pattern.compile("Duplicate field '([^']+)'");
    private static final Pattern NO_RENDERED_TEXT = Pattern.compile(
            "(?i)\\b(?:no|without)\\s+(?:(?:visible|written)\\s+)?(?:text|words?|letters?|captions?|subtitles?|signs?|logos?|watermarks?)\\b");
    private static final Pattern RENDERED_TEXT = Pattern.compile(
            "(?i)\\b(?:text|words?|letters?|captions?|subtitles?|signs?|logos?|watermarks?)\\b");
    private static final Pattern CONFLICTING_MOMENT = Pattern.compile(
            "(?i)\\bbefore\\s+and\\s+after\\b|\\bat\\s+first\\b.*\\blater\\b|\\bthen\\b.*\\blater\\b");

    private final ObjectMapper mapper;

    public ImageStructuredOutputParser() {
        this(new ObjectMapper());
    }

    public ImageStructuredOutputParser(ObjectMapper objectMapper) {
        mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public StoryAnalysis storyAnalysis(String raw) {
        JsonNode root = root(raw, "STORY_ANALYSIS", fields(
                "scenes", "beats", "characters", "locations", "props", "dialogues", "narration"));
        List<Scene> scenes = objects(root, "scenes", "STORY_ANALYSIS", "sceneIndex", "title", "sourceExcerpt", "summary").stream()
                .map(node -> new Scene(integer(node, "sceneIndex", "STORY_ANALYSIS.scenes"), text(node, "title", "STORY_ANALYSIS.scenes"),
                        text(node, "sourceExcerpt", "STORY_ANALYSIS.scenes"), text(node, "summary", "STORY_ANALYSIS.scenes")))
                .toList();
        List<Beat> beats = objects(root, "beats", "STORY_ANALYSIS", "beatKey", "sceneIndex", "order", "action", "temporalMoment").stream()
                .map(node -> new Beat(text(node, "beatKey", "STORY_ANALYSIS.beats"), integer(node, "sceneIndex", "STORY_ANALYSIS.beats"),
                        integer(node, "order", "STORY_ANALYSIS.beats"), text(node, "action", "STORY_ANALYSIS.beats"), text(node, "temporalMoment", "STORY_ANALYSIS.beats")))
                .toList();
        List<Character> characters = objects(root, "characters", "STORY_ANALYSIS", "characterKey", "name", "description").stream()
                .map(node -> new Character(text(node, "characterKey", "STORY_ANALYSIS.characters"), text(node, "name", "STORY_ANALYSIS.characters"), text(node, "description", "STORY_ANALYSIS.characters")))
                .toList();
        List<Location> locations = objects(root, "locations", "STORY_ANALYSIS", "locationKey", "name", "description").stream()
                .map(node -> new Location(text(node, "locationKey", "STORY_ANALYSIS.locations"), text(node, "name", "STORY_ANALYSIS.locations"), text(node, "description", "STORY_ANALYSIS.locations")))
                .toList();
        List<Prop> props = objects(root, "props", "STORY_ANALYSIS", "propKey", "name", "description").stream()
                .map(node -> new Prop(text(node, "propKey", "STORY_ANALYSIS.props"), text(node, "name", "STORY_ANALYSIS.props"), text(node, "description", "STORY_ANALYSIS.props")))
                .toList();
        List<Dialogue> dialogues = objects(root, "dialogues", "STORY_ANALYSIS", "sceneIndex", "speaker", "text").stream()
                .map(node -> new Dialogue(integer(node, "sceneIndex", "STORY_ANALYSIS.dialogues"), text(node, "speaker", "STORY_ANALYSIS.dialogues"), text(node, "text", "STORY_ANALYSIS.dialogues")))
                .toList();
        List<Narration> narration = objects(root, "narration", "STORY_ANALYSIS", "sceneIndex", "text").stream()
                .map(node -> new Narration(integer(node, "sceneIndex", "STORY_ANALYSIS.narration"), text(node, "text", "STORY_ANALYSIS.narration")))
                .toList();
        StoryAnalysis analysis = new StoryAnalysis(scenes, beats, characters, locations, props, dialogues, narration);
        validateStoryAnalysis(analysis);
        return analysis;
    }

    public ContinuityBible continuityBible(String raw) {
        JsonNode root = root(raw, "CONTINUITY_BIBLE", fields("characters", "props", "invariants", "forbiddenChanges"));
        List<ContinuityCharacter> characters = objects(root, "characters", "CONTINUITY_BIBLE", "characterKey", "name", "visualDescription", "clothing", "colors", "proportions", "expressionRules").stream()
                .map(node -> new ContinuityCharacter(text(node, "characterKey", "CONTINUITY_BIBLE.characters"), text(node, "name", "CONTINUITY_BIBLE.characters"),
                        text(node, "visualDescription", "CONTINUITY_BIBLE.characters"), text(node, "clothing", "CONTINUITY_BIBLE.characters"), text(node, "colors", "CONTINUITY_BIBLE.characters"), text(node, "proportions", "CONTINUITY_BIBLE.characters"), text(node, "expressionRules", "CONTINUITY_BIBLE.characters")))
                .toList();
        List<ContinuityProp> props = objects(root, "props", "CONTINUITY_BIBLE", "propKey", "visualDescription", "colors", "invariants").stream()
                .map(node -> new ContinuityProp(text(node, "propKey", "CONTINUITY_BIBLE.props"), text(node, "visualDescription", "CONTINUITY_BIBLE.props"), text(node, "colors", "CONTINUITY_BIBLE.props"), text(node, "invariants", "CONTINUITY_BIBLE.props")))
                .toList();
        ContinuityBible bible = new ContinuityBible(characters, props, strings(root, "invariants", "CONTINUITY_BIBLE"), strings(root, "forbiddenChanges", "CONTINUITY_BIBLE"));
        uniqueKeys("ContinuityBible characterKey", bible.characters(), ContinuityCharacter::characterKey);
        uniqueKeys("ContinuityBible propKey", bible.props(), ContinuityProp::propKey);
        return bible;
    }

    public StyleBible styleBible(String raw) {
        JsonNode root = root(raw, "STYLE_BIBLE", fields("palette", "renderingStyle", "lighting", "cameraRules", "environmentRules", "negativeRules"));
        return new StyleBible(text(root, "palette", "STYLE_BIBLE"), text(root, "renderingStyle", "STYLE_BIBLE"), text(root, "lighting", "STYLE_BIBLE"),
                text(root, "cameraRules", "STYLE_BIBLE"), text(root, "environmentRules", "STYLE_BIBLE"), strings(root, "negativeRules", "STYLE_BIBLE"));
    }

    public StoryboardProposal storyboardProposal(String raw) {
        JsonNode root = root(raw, "STORYBOARD_PROPOSAL", fields("shots"));
        List<ProposalShot> shots = objects(root, "shots", "STORYBOARD_PROPOSAL", "sceneIndex", "beat", "action", "characters", "location", "dialogue", "narration", "splitReason").stream()
                .map(node -> new ProposalShot(integer(node, "sceneIndex", "STORYBOARD_PROPOSAL.shots"), text(node, "beat", "STORYBOARD_PROPOSAL.shots"),
                        text(node, "action", "STORYBOARD_PROPOSAL.shots"), stringArrayField(node, "characters", "STORYBOARD_PROPOSAL.shots"), text(node, "location", "STORYBOARD_PROPOSAL.shots"),
                        text(node, "dialogue", "STORYBOARD_PROPOSAL.shots"), text(node, "narration", "STORYBOARD_PROPOSAL.shots"), text(node, "splitReason", "STORYBOARD_PROPOSAL.shots")))
                .toList();
        StoryboardProposal proposal = new StoryboardProposal(shots);
        for (ProposalShot shot : proposal.shots()) {
            positive("StoryboardProposal sceneIndex", shot.sceneIndex());
            noConflictingMoment("StoryboardProposal", shot.action());
            uniqueStrings("StoryboardProposal characters", shot.characters());
            captionLength("StoryboardProposal narration", shot.narration());
        }
        return proposal;
    }

    public FinalStoryboard finalStoryboard(String raw) {
        JsonNode root = root(raw, "FINAL_STORYBOARD", fields("shots"));
        List<FinalShot> shots = objects(root, "shots", "FINAL_STORYBOARD", "shotKey", "sceneIndex", "shotIndex", "sourceExcerpt", "visualGoal", "dialogue", "narration", "speaker", "textAnchor").stream()
                .map(node -> new FinalShot(text(node, "shotKey", "FINAL_STORYBOARD.shots"), integer(node, "sceneIndex", "FINAL_STORYBOARD.shots"),
                        integer(node, "shotIndex", "FINAL_STORYBOARD.shots"), text(node, "sourceExcerpt", "FINAL_STORYBOARD.shots"), text(node, "visualGoal", "FINAL_STORYBOARD.shots"),
                        text(node, "dialogue", "FINAL_STORYBOARD.shots"), text(node, "narration", "FINAL_STORYBOARD.shots"), text(node, "speaker", "FINAL_STORYBOARD.shots"), anchor(node, "FINAL_STORYBOARD.shots")))
                .toList();
        FinalStoryboard storyboard = new FinalStoryboard(shots);
        validateFinalShots("FinalStoryboard", storyboard.shots());
        return storyboard;
    }

    public ReferencePlan referencePlan(String raw) {
        JsonNode root = root(raw, "REFERENCE_PLAN", fields("referenceAssets"));
        List<ReferenceAsset> assets = objects(root, "referenceAssets", "REFERENCE_PLAN", "assetKey", "type", "target", "prompt", "negativePrompt").stream()
                .map(node -> new ReferenceAsset(text(node, "assetKey", "REFERENCE_PLAN.referenceAssets"), text(node, "type", "REFERENCE_PLAN.referenceAssets"), text(node, "target", "REFERENCE_PLAN.referenceAssets"), text(node, "prompt", "REFERENCE_PLAN.referenceAssets"), text(node, "negativePrompt", "REFERENCE_PLAN.referenceAssets")))
                .toList();
        ReferencePlan plan = new ReferencePlan(assets);
        uniqueKeys("ReferencePlan assetKey", plan.referenceAssets(), ReferenceAsset::assetKey);
        for (ReferenceAsset asset : plan.referenceAssets()) positivePrompt("ReferencePlan prompt", asset.prompt());
        return plan;
    }

    public ShotPromptPlan shotPromptPlan(String raw) {
        JsonNode root = root(raw, "SHOT_PROMPT_PLAN", fields("shots"));
        List<ShotPrompt> shots = objects(root, "shots", "SHOT_PROMPT_PLAN", "shotKey", "prompt", "negativePrompt", "referenceAssetKeys").stream()
                .map(node -> new ShotPrompt(text(node, "shotKey", "SHOT_PROMPT_PLAN.shots"), text(node, "prompt", "SHOT_PROMPT_PLAN.shots"),
                        text(node, "negativePrompt", "SHOT_PROMPT_PLAN.shots"), stringArrayField(node, "referenceAssetKeys", "SHOT_PROMPT_PLAN.shots")))
                .toList();
        ShotPromptPlan plan = new ShotPromptPlan(shots);
        uniqueKeys("ShotPromptPlan shotKey", plan.shots(), ShotPrompt::shotKey);
        for (ShotPrompt shot : plan.shots()) {
            positivePrompt("ShotPromptPlan prompt", shot.prompt());
            uniqueStrings("ShotPromptPlan referenceAssetKeys", shot.referenceAssetKeys());
        }
        return plan;
    }

    public PreflightPlan preflight(String raw) {
        JsonNode root = root(raw, "PREFLIGHT_PLAN", fields("referenceAssets", "shots", "auditSummary"));
        List<ReferenceAsset> assets = objects(root, "referenceAssets", "PREFLIGHT_PLAN", "assetKey", "type", "target", "prompt", "negativePrompt").stream()
                .map(node -> new ReferenceAsset(text(node, "assetKey", "PREFLIGHT_PLAN.referenceAssets"), text(node, "type", "PREFLIGHT_PLAN.referenceAssets"), text(node, "target", "PREFLIGHT_PLAN.referenceAssets"), text(node, "prompt", "PREFLIGHT_PLAN.referenceAssets"), text(node, "negativePrompt", "PREFLIGHT_PLAN.referenceAssets")))
                .toList();
        List<PreflightShot> shots = objects(root, "shots", "PREFLIGHT_PLAN", "shotKey", "sceneIndex", "shotIndex", "prompt", "negativePrompt", "referenceAssetKeys", "speaker", "dialogue", "narration", "textAnchor").stream()
                .map(node -> new PreflightShot(text(node, "shotKey", "PREFLIGHT_PLAN.shots"), integer(node, "sceneIndex", "PREFLIGHT_PLAN.shots"), integer(node, "shotIndex", "PREFLIGHT_PLAN.shots"),
                        text(node, "prompt", "PREFLIGHT_PLAN.shots"), text(node, "negativePrompt", "PREFLIGHT_PLAN.shots"), stringArrayField(node, "referenceAssetKeys", "PREFLIGHT_PLAN.shots"),
                        text(node, "speaker", "PREFLIGHT_PLAN.shots"), text(node, "dialogue", "PREFLIGHT_PLAN.shots"), text(node, "narration", "PREFLIGHT_PLAN.shots"), anchor(node, "PREFLIGHT_PLAN.shots")))
                .toList();
        PreflightPlan plan = new PreflightPlan(assets, shots, text(root, "auditSummary", "PREFLIGHT_PLAN"));
        validatePreflightShape(plan);
        return plan;
    }

    public void validateCoverage(StoryAnalysis analysis, FinalStoryboard storyboard) {
        Objects.requireNonNull(analysis, "analysis");
        Objects.requireNonNull(storyboard, "storyboard");
        Set<Integer> scenes = new HashSet<>();
        for (Scene scene : analysis.scenes()) scenes.add(scene.sceneIndex());
        Set<Integer> covered = new HashSet<>();
        Set<String> characters = keys(analysis.characters(), Character::characterKey);
        for (FinalShot shot : storyboard.shots()) {
            if (!scenes.contains(shot.sceneIndex())) throw error("FinalStoryboard sceneIndex 超出 StoryAnalysis 场景范围: " + shot.sceneIndex());
            covered.add(shot.sceneIndex());
            if (hasText(shot.dialogue()) && !characters.contains(trimmed(shot.speaker()))) {
                throw error("FinalStoryboard speaker 未在 StoryAnalysis 中声明: " + shot.speaker());
            }
        }
        for (Scene scene : analysis.scenes()) {
            if (!covered.contains(scene.sceneIndex())) throw error("FinalStoryboard 未覆盖 Scene: " + scene.sceneIndex());
        }
    }

    public void validateContinuityReferences(StoryAnalysis analysis, ContinuityBible bible) {
        Set<String> characters = keys(analysis.characters(), Character::characterKey);
        Set<String> props = keys(analysis.props(), Prop::propKey);
        for (ContinuityCharacter character : bible.characters()) {
            if (!characters.contains(trimmed(character.characterKey()))) throw error("ContinuityBible characterKey 未在 StoryAnalysis 中声明: " + character.characterKey());
        }
        for (ContinuityProp prop : bible.props()) {
            if (!props.contains(trimmed(prop.propKey()))) throw error("ContinuityBible propKey 未在 StoryAnalysis 中声明: " + prop.propKey());
        }
    }

    public void validateProposalReferences(StoryAnalysis analysis, StoryboardProposal proposal) {
        Set<String> characters = keys(analysis.characters(), Character::characterKey);
        Set<String> locations = keys(analysis.locations(), Location::locationKey);
        for (ProposalShot shot : proposal.shots()) {
            for (String character : shot.characters()) {
                if (!characters.contains(trimmed(character))) throw error("StoryboardProposal characterKey 未在 StoryAnalysis 中声明: " + character);
            }
            if (!locations.contains(trimmed(shot.location()))) throw error("StoryboardProposal locationKey 未在 StoryAnalysis 中声明: " + shot.location());
        }
    }

    public void validateReferences(ShotPromptPlan plan, ReferencePlan references) {
        Set<String> assets = keys(references.referenceAssets(), ReferenceAsset::assetKey);
        for (ShotPrompt shot : plan.shots()) {
            for (String reference : shot.referenceAssetKeys()) {
                if (!assets.contains(trimmed(reference))) throw error("ShotPromptPlan 引用了未知 referenceAssetKey: " + reference);
            }
        }
    }

    public void validateShotPrompts(FinalStoryboard storyboard, ShotPromptPlan plan) {
        Set<String> finalShotKeys = keys(storyboard.shots(), FinalShot::shotKey);
        Set<String> planShotKeys = keys(plan.shots(), ShotPrompt::shotKey);
        for (String shotKey : planShotKeys) {
            if (!finalShotKeys.contains(shotKey)) throw error("ShotPromptPlan shotKey 未在 FinalStoryboard 中声明: " + shotKey);
        }
        for (String shotKey : finalShotKeys) {
            if (!planShotKeys.contains(shotKey)) throw error("ShotPromptPlan 缺少 FinalStoryboard shotKey: " + shotKey);
        }
    }

    public void validatePreflight(PreflightPlan plan) {
        validatePreflightShape(plan);
        Set<String> assets = keys(plan.referenceAssets(), ReferenceAsset::assetKey);
        for (PreflightShot shot : plan.shots()) {
            for (String reference : shot.referenceAssetKeys()) {
                if (!assets.contains(trimmed(reference))) throw error("PreflightPlan 引用了未知 referenceAssetKey: " + reference);
            }
        }
    }

    private JsonNode root(String raw, String schema, Set<String> expectedFields) {
        String json = boundedJson(raw, schema);
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || root.isNull() || root.isMissingNode() || json.isBlank()) throw error(schema + " JSON 内容不能为空");
            if (!root.isObject()) throw error(schema + " JSON 内容必须是 object");
            verifyFields(root, expectedFields, schema + " JSON");
            return root;
        } catch (JsonProcessingException exception) {
            Matcher matcher = DUPLICATE_FIELD.matcher(exception.getOriginalMessage());
            if (matcher.find()) throw error(schema + " JSON 存在重复字段: " + matcher.group(1));
            throw error(schema + " JSON 格式无效");
        }
    }

    private static String boundedJson(String raw, String schema) {
        if (raw == null) throw error("缺少 " + schema + " JSON 输出边界");
        String begin = "<" + schema + "_JSON_BEGIN>";
        String end = "<" + schema + "_JSON_END>";
        List<Marker> markers = markers(raw, begin, end);
        int begins = (int) markers.stream().filter(marker -> marker.value().equals(begin)).count();
        int ends = (int) markers.stream().filter(marker -> marker.value().equals(end)).count();
        if (begins == 0 || ends == 0) throw error("缺少 " + schema + " JSON 输出边界");
        if (begins != 1 || ends != 1) throw error(schema + " JSON 输出边界重复");
        Marker first = markers.get(0);
        Marker second = markers.get(1);
        if (!first.value().equals(begin) || !second.value().equals(end)) throw error(schema + " JSON 输出边界顺序错误");
        String json = raw.substring(first.end(), second.start());
        if (json.isBlank()) throw error(schema + " JSON 内容不能为空");
        return json;
    }

    private static List<Marker> markers(String raw, String begin, String end) {
        List<Marker> result = new ArrayList<>();
        int lineStart = 0;
        while (lineStart <= raw.length()) {
            int lineEnd = raw.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = raw.length();
            String line = raw.substring(lineStart, lineEnd);
            String trimmed = line.strip();
            if (trimmed.equals(begin) || trimmed.equals(end)) result.add(new Marker(trimmed, lineStart, lineEnd));
            if (lineEnd == raw.length()) break;
            lineStart = lineEnd + 1;
        }
        return result;
    }

    private static void verifyFields(JsonNode node, Set<String> expected, String context) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        for (String name : actual) if (!expected.contains(name)) throw error(context + " 包含未知字段: " + name);
        for (String name : expected) if (!actual.contains(name)) throw error(context + " 缺少字段: " + name);
    }

    private static List<JsonNode> objects(JsonNode root, String name, String schema, String... itemFields) {
        JsonNode array = root.get(name);
        if (!array.isArray()) throw error(schema + "." + name + " 必须是数组");
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode node : array) {
            if (!node.isObject()) throw error(schema + "." + name + " 的每项必须是 object");
            verifyFields(node, fields(itemFields), schema + "." + name + " item");
            result.add(node);
        }
        return result;
    }

    private static List<String> strings(JsonNode root, String name, String schema) {
        JsonNode array = root.get(name);
        if (!array.isArray()) throw error(schema + "." + name + " 必须是数组");
        List<String> result = new ArrayList<>();
        for (JsonNode node : array) {
            if (!node.isTextual()) throw error(schema + "." + name + " 的每项必须是 string");
            result.add(node.textValue());
        }
        return result;
    }

    private static List<String> stringArrayField(JsonNode node, String name, String context) {
        JsonNode array = node.get(name);
        if (!array.isArray()) throw error(context + "." + name + " 必须是数组");
        List<String> result = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isTextual()) throw error(context + "." + name + " 的每项必须是 string");
            result.add(item.textValue());
        }
        return result;
    }

    private static String text(JsonNode node, String name, String context) {
        verifyRequired(node, name, context);
        JsonNode value = node.get(name);
        if (value.isNull()) return null;
        if (!value.isTextual()) throw error(context + "." + name + " 必须是 string 或 null");
        return value.textValue();
    }

    private static int integer(JsonNode node, String name, String context) {
        verifyRequired(node, name, context);
        JsonNode value = node.get(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw error(context + "." + name + " 必须是整数");
        return value.intValue();
    }

    private static TextAnchor anchor(JsonNode node, String context) {
        verifyRequired(node, "textAnchor", context);
        JsonNode value = node.get("textAnchor");
        if (value.isNull()) return null;
        if (!value.isObject()) throw error(context + ".textAnchor 必须是 object 或 null");
        verifyFields(value, fields("x", "y"), context + ".textAnchor");
        if (!value.get("x").isNumber()) throw error("textAnchor.x 必须是数字");
        if (!value.get("y").isNumber()) throw error("textAnchor.y 必须是数字");
        double x = value.get("x").doubleValue();
        double y = value.get("y").doubleValue();
        if (x < 0 || x > 1) throw error("textAnchor.x 必须在 0 到 1 之间");
        if (y < 0 || y > 1) throw error("textAnchor.y 必须在 0 到 1 之间");
        return new TextAnchor(x, y);
    }

    private static void verifyRequired(JsonNode node, String name, String context) {
        if (!node.has(name)) throw error(context + " 缺少字段: " + name);
    }

    private static void validateStoryAnalysis(StoryAnalysis analysis) {
        uniqueKeys("StoryAnalysis sceneIndex", analysis.scenes(), scene -> String.valueOf(scene.sceneIndex()));
        for (Scene scene : analysis.scenes()) positive("StoryAnalysis sceneIndex", scene.sceneIndex());
        uniqueKeys("StoryAnalysis beatKey", analysis.beats(), Beat::beatKey);
        uniqueKeys("StoryAnalysis characterKey", analysis.characters(), Character::characterKey);
        uniqueKeys("StoryAnalysis locationKey", analysis.locations(), Location::locationKey);
        uniqueKeys("StoryAnalysis propKey", analysis.props(), Prop::propKey);
        for (Beat beat : analysis.beats()) {
            positive("StoryAnalysis beat sceneIndex", beat.sceneIndex());
            positive("StoryAnalysis beat order", beat.order());
        }
        for (Dialogue dialogue : analysis.dialogues()) positive("StoryAnalysis dialogue sceneIndex", dialogue.sceneIndex());
        for (Narration narration : analysis.narration()) {
            positive("StoryAnalysis narration sceneIndex", narration.sceneIndex());
            captionLength("StoryAnalysis narration", narration.text());
        }
    }

    private static void validateFinalShots(String label, List<FinalShot> shots) {
        if (shots.size() > MAX_SHOTS_PER_STORY) throw error(label + " 全篇最多 20 个镜头");
        uniqueKeys(label + " shotKey", shots, FinalShot::shotKey);
        validateOrderedShots(label, shots.stream().map(shot -> new OrderedShot(shot.sceneIndex(), shot.shotIndex())).toList());
        for (FinalShot shot : shots) {
            captionLength(label + " narration", shot.narration());
            if (hasText(shot.dialogue()) && !hasText(shot.speaker())) throw error(label + " dialogue 非空时 speaker 不能为空");
            if (hasText(shot.dialogue()) && shot.textAnchor() == null) throw error(label + " dialogue 非空时 textAnchor 不能为空");
        }
    }

    private static void validatePreflightShape(PreflightPlan plan) {
        uniqueKeys("PreflightPlan assetKey", plan.referenceAssets(), ReferenceAsset::assetKey);
        uniqueKeys("PreflightPlan shotKey", plan.shots(), PreflightShot::shotKey);
        if (plan.shots().size() > MAX_SHOTS_PER_STORY) throw error("PreflightPlan 全篇最多 20 个镜头");
        validateOrderedShots("PreflightPlan", plan.shots().stream().map(shot -> new OrderedShot(shot.sceneIndex(), shot.shotIndex())).toList());
        for (ReferenceAsset asset : plan.referenceAssets()) positivePrompt("PreflightPlan referenceAssets prompt", asset.prompt());
        for (PreflightShot shot : plan.shots()) {
            positivePrompt("PreflightPlan prompt", shot.prompt());
            uniqueStrings("PreflightPlan referenceAssetKeys", shot.referenceAssetKeys());
            captionLength("PreflightPlan narration", shot.narration());
            if (hasText(shot.dialogue()) && !hasText(shot.speaker())) throw error("PreflightPlan dialogue 非空时 speaker 不能为空");
            if (hasText(shot.dialogue()) && shot.textAnchor() == null) throw error("PreflightPlan dialogue 非空时 textAnchor 不能为空");
        }
    }

    private static void validateOrderedShots(String label, List<OrderedShot> shots) {
        Map<Integer, Integer> expectedIndexes = new HashMap<>();
        int priorScene = 0;
        for (OrderedShot shot : shots) {
            positive(label + " sceneIndex", shot.sceneIndex());
            positive(label + " shotIndex", shot.shotIndex());
            if (shot.sceneIndex() < priorScene) throw error(label + " 必须按 sceneIndex、shotIndex 严格升序");
            int expected = expectedIndexes.getOrDefault(shot.sceneIndex(), 1);
            if (shot.shotIndex() != expected) throw error(label + " shotIndex 必须从 1 连续递增");
            expectedIndexes.put(shot.sceneIndex(), expected + 1);
            if (expected > MAX_SHOTS_PER_SCENE) throw error(label + " 每个 Scene 最多 5 个镜头");
            priorScene = shot.sceneIndex();
        }
    }

    private static void positivePrompt(String label, String prompt) {
        if (!hasText(prompt)) throw error(label + " 不能为空");
        String withoutNegativeConstraint = NO_RENDERED_TEXT.matcher(prompt).replaceAll("");
        if (RENDERED_TEXT.matcher(withoutNegativeConstraint).find()) throw error(label + " 不得要求图片模型渲染文字");
    }

    private static void noConflictingMoment(String label, String action) {
        if (action != null && CONFLICTING_MOMENT.matcher(action).find()) throw error(label + " 同一镜头包含互斥时间点");
    }

    private static void captionLength(String label, String text) {
        if (text != null && text.length() > MAX_CAPTION_LENGTH) throw error(label + " 长度不能超过 180");
    }

    private static void positive(String label, int value) {
        if (value <= 0) throw error(label + " 必须为正数");
    }

    private static void uniqueStrings(String label, List<String> values) {
        Set<String> found = new HashSet<>();
        for (String value : values) {
            String key = requiredKey(label, value);
            if (!found.add(key)) throw error(label + " 重复: " + value);
        }
    }

    private static <T> void uniqueKeys(String label, Collection<T> values, Key<T> keyExtractor) {
        Set<String> found = new HashSet<>();
        for (T value : values) {
            String key = requiredKey(label, keyExtractor.get(value));
            if (!found.add(key)) throw error(label + " 重复: " + keyExtractor.get(value));
        }
    }

    private static <T> Set<String> keys(Collection<T> values, Key<T> extractor) {
        Set<String> result = new HashSet<>();
        for (T value : values) result.add(trimmed(extractor.get(value)));
        return result;
    }

    private static String requiredKey(String label, String value) {
        if (!hasText(value)) throw error(label + " 不能为空");
        return trimmed(value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static Set<String> fields(String... values) {
        return Set.of(values);
    }

    private static IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message);
    }

    private interface Key<T> {
        String get(T value);
    }

    private record Marker(String value, int start, int end) {
    }

    private record OrderedShot(int sceneIndex, int shotIndex) {
    }

    public record StoryAnalysis(List<Scene> scenes, List<Beat> beats, List<Character> characters, List<Location> locations, List<Prop> props, List<Dialogue> dialogues, List<Narration> narration) {
        public StoryAnalysis { scenes = immutable(scenes); beats = immutable(beats); characters = immutable(characters); locations = immutable(locations); props = immutable(props); dialogues = immutable(dialogues); narration = immutable(narration); }
    }
    public record Scene(int sceneIndex, String title, String sourceExcerpt, String summary) { }
    public record Beat(String beatKey, int sceneIndex, int order, String action, String temporalMoment) { }
    public record Character(String characterKey, String name, String description) { }
    public record Location(String locationKey, String name, String description) { }
    public record Prop(String propKey, String name, String description) { }
    public record Dialogue(int sceneIndex, String speaker, String text) { }
    public record Narration(int sceneIndex, String text) { }

    public record ContinuityBible(List<ContinuityCharacter> characters, List<ContinuityProp> props, List<String> invariants, List<String> forbiddenChanges) {
        public ContinuityBible { characters = immutable(characters); props = immutable(props); invariants = immutable(invariants); forbiddenChanges = immutable(forbiddenChanges); }
    }
    public record ContinuityCharacter(String characterKey, String name, String visualDescription, String clothing, String colors, String proportions, String expressionRules) { }
    public record ContinuityProp(String propKey, String visualDescription, String colors, String invariants) { }
    public record StyleBible(String palette, String renderingStyle, String lighting, String cameraRules, String environmentRules, List<String> negativeRules) {
        public StyleBible { negativeRules = immutable(negativeRules); }
    }

    public record StoryboardProposal(List<ProposalShot> shots) { public StoryboardProposal { shots = immutable(shots); } }
    public record ProposalShot(int sceneIndex, String beat, String action, List<String> characters, String location, String dialogue, String narration, String splitReason) {
        public ProposalShot { characters = immutable(characters); }
    }
    public record FinalStoryboard(List<FinalShot> shots) { public FinalStoryboard { shots = immutable(shots); } }
    public record FinalShot(String shotKey, int sceneIndex, int shotIndex, String sourceExcerpt, String visualGoal, String dialogue, String narration, String speaker, TextAnchor textAnchor) { }
    public record TextAnchor(double x, double y) { }
    public record ReferencePlan(List<ReferenceAsset> referenceAssets) { public ReferencePlan { referenceAssets = immutable(referenceAssets); } }
    public record ReferenceAsset(String assetKey, String type, String target, String prompt, String negativePrompt) { }
    public record ShotPromptPlan(List<ShotPrompt> shots) { public ShotPromptPlan { shots = immutable(shots); } }
    public record ShotPrompt(String shotKey, String prompt, String negativePrompt, List<String> referenceAssetKeys) { public ShotPrompt { referenceAssetKeys = immutable(referenceAssetKeys); } }
    public record PreflightPlan(List<ReferenceAsset> referenceAssets, List<PreflightShot> shots, String auditSummary) { public PreflightPlan { referenceAssets = immutable(referenceAssets); shots = immutable(shots); } }
    public record PreflightShot(String shotKey, int sceneIndex, int shotIndex, String prompt, String negativePrompt, List<String> referenceAssetKeys, String speaker, String dialogue, String narration, TextAnchor textAnchor) { public PreflightShot { referenceAssetKeys = immutable(referenceAssetKeys); } }

    private static <T> List<T> immutable(List<T> values) {
        return List.copyOf(values == null ? List.of() : values);
    }
}
