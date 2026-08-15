package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aitaskcenter.service.ImageStructuredOutputParser.ContinuityBible;
import com.aitaskcenter.service.ImageStructuredOutputParser.FinalStoryboard;
import com.aitaskcenter.service.ImageStructuredOutputParser.PreflightPlan;
import com.aitaskcenter.service.ImageStructuredOutputParser.ReferencePlan;
import com.aitaskcenter.service.ImageStructuredOutputParser.ShotPromptPlan;
import com.aitaskcenter.service.ImageStructuredOutputParser.StoryAnalysis;
import com.aitaskcenter.service.ImageStructuredOutputParser.StoryboardProposal;
import com.aitaskcenter.service.ImageStructuredOutputParser.StyleBible;
import org.junit.jupiter.api.Test;

class ImageStructuredOutputParserTest {
    private final ImageStructuredOutputParser parser = new ImageStructuredOutputParser();

    @Test
    void parsesEveryCatalogSchemaFromItsUniqueStandaloneJsonBlock() {
        assertEquals(1, parser.storyAnalysis(wrap("STORY_ANALYSIS", storyAnalysisJson())).scenes().size());
        assertEquals(1, parser.continuityBible(wrap("CONTINUITY_BIBLE", continuityBibleJson())).characters().size());
        assertEquals("warm watercolor", parser.styleBible(wrap("STYLE_BIBLE", styleBibleJson())).renderingStyle());
        assertEquals(1, parser.storyboardProposal(wrap("STORYBOARD_PROPOSAL", storyboardProposalJson())).shots().size());
        assertEquals("shot-1", parser.finalStoryboard(wrap("FINAL_STORYBOARD", finalStoryboardJson())).shots().get(0).shotKey());
        assertEquals("asset-amy", parser.referencePlan(wrap("REFERENCE_PLAN", referencePlanJson())).referenceAssets().get(0).assetKey());
        assertEquals("shot-1", parser.shotPromptPlan(wrap("SHOT_PROMPT_PLAN", shotPromptPlanJson())).shots().get(0).shotKey());
        assertEquals("checked", parser.preflight(wrap("PREFLIGHT_PLAN", preflightJson())).auditSummary());
    }

    @Test
    void acceptsAuditTextOutsideMarkersWithoutParsingItAsDownstreamData() {
        StoryAnalysis analysis = parser.storyAnalysis("audit <STORY_ANALYSIS_JSON_BEGIN> ignored\n"
                + wrap("STORY_ANALYSIS", storyAnalysisJson()) + "\npostscript");

        assertEquals("Park visit", analysis.scenes().get(0).title());
    }

    @Test
    void rejectsMissingRepeatedReversedAndNonStandaloneMarkers() {
        assertMessage("缺少 STORY_ANALYSIS JSON 输出边界", () -> parser.storyAnalysis(storyAnalysisJson()));
        assertMessage("STORY_ANALYSIS JSON 输出边界重复", () -> parser.storyAnalysis(wrap("STORY_ANALYSIS", storyAnalysisJson())
                + "\n<STORY_ANALYSIS_JSON_BEGIN>\n{}\n<STORY_ANALYSIS_JSON_END>"));
        assertMessage("STORY_ANALYSIS JSON 输出边界顺序错误", () -> parser.storyAnalysis(
                "<STORY_ANALYSIS_JSON_END>\n<STORY_ANALYSIS_JSON_BEGIN>\n{}"));
        assertMessage("缺少 STORY_ANALYSIS JSON 输出边界", () -> parser.storyAnalysis(
                "prefix <STORY_ANALYSIS_JSON_BEGIN>\n" + storyAnalysisJson() + "\n<STORY_ANALYSIS_JSON_END> suffix"));
    }

    @Test
    void doesNotTreatMarkerTextInsideJsonStringsAsMarkers() {
        String json = storyAnalysisJson().replace("Park visit", "<STORY_ANALYSIS_JSON_END> Park visit");
        assertEquals("<STORY_ANALYSIS_JSON_END> Park visit", parser.storyAnalysis(wrap("STORY_ANALYSIS", json))
                .scenes().get(0).title());
    }

    @Test
    void rejectsEmptyAndNonObjectJsonBlocks() {
        assertMessage("STORY_ANALYSIS JSON 内容不能为空", () -> parser.storyAnalysis(
                "<STORY_ANALYSIS_JSON_BEGIN>\n \n<STORY_ANALYSIS_JSON_END>"));
        assertMessage("STORY_ANALYSIS JSON 内容必须是 object", () -> parser.storyAnalysis(
                wrap("STORY_ANALYSIS", "[]")));
    }

    @Test
    void rejectsDuplicateUnknownMissingAndWrongTypedFields() {
        assertMessage("STORY_ANALYSIS JSON 存在重复字段: scenes", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS",
                "{\"scenes\":[],\"scenes\":[],\"beats\":[],\"characters\":[],\"locations\":[],\"props\":[],\"dialogues\":[],\"narration\":[]}")));
        assertMessage("STORY_ANALYSIS JSON 包含未知字段: extra", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS",
                "{\"scenes\":[],\"beats\":[],\"characters\":[],\"locations\":[],\"props\":[],\"dialogues\":[],\"narration\":[],\"extra\":true}")));
        assertMessage("STORY_ANALYSIS JSON 缺少字段: narration", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS",
                "{\"scenes\":[],\"beats\":[],\"characters\":[],\"locations\":[],\"props\":[],\"dialogues\":[]}")));
        assertMessage("STORY_ANALYSIS.scenes 必须是数组", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS",
                "{\"scenes\":{},\"beats\":[],\"characters\":[],\"locations\":[],\"props\":[],\"dialogues\":[],\"narration\":[]}")));
        assertMessage("STORY_ANALYSIS.scenes item 包含未知字段: extra", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS", storyAnalysisJson().replace("\"summary\":\"Amy visits the park\"", "\"summary\":\"Amy visits the park\",\"extra\":true"))));
    }

    @Test
    void validatesUniqueKeysAndContinuityReferences() {
        assertMessage("StoryAnalysis characterKey 重复: amy", () -> parser.storyAnalysis(wrap("STORY_ANALYSIS", storyAnalysisJson()
                .replace(
                        "}],\"locations\":",
                        "},{\"characterKey\":\"amy\",\"name\":\"Amy Two\",\"description\":\"duplicate\"}],\"locations\":"))));

        ContinuityBible unknownCharacter = parser.continuityBible(wrap("CONTINUITY_BIBLE", continuityBibleJson()
                .replace("\"characterKey\":\"amy\"", "\"characterKey\":\"ben\"")));
        assertMessage("ContinuityBible characterKey 未在 StoryAnalysis 中声明: ben", () ->
                parser.validateContinuityReferences(storyAnalysis(), unknownCharacter));
    }

    @Test
    void validatesProposalCharactersAndLocationsAgainstAnalysis() {
        StoryboardProposal proposal = parser.storyboardProposal(wrap("STORYBOARD_PROPOSAL", storyboardProposalJson()
                .replace("[\"amy\"]", "[\"ben\"]")));
        assertMessage("StoryboardProposal characterKey 未在 StoryAnalysis 中声明: ben", () ->
                parser.validateProposalReferences(storyAnalysis(), proposal));

        StoryboardProposal unknownLocation = parser.storyboardProposal(wrap("STORYBOARD_PROPOSAL", storyboardProposalJson()
                .replace("\"park\"", "\"beach\"")));
        assertMessage("StoryboardProposal locationKey 未在 StoryAnalysis 中声明: beach", () ->
                parser.validateProposalReferences(storyAnalysis(), unknownLocation));
    }

    @Test
    void validatesFinalStoryboardLimitsCoverageOrderDialogueAndAnchor() {
        assertMessage("FinalStoryboard sceneIndex 超出 StoryAnalysis 场景范围: 6", () -> parser.validateCoverage(
                storyAnalysis(), parser.finalStoryboard(wrap("FINAL_STORYBOARD", finalStoryboardJson().replace("\"sceneIndex\":1", "\"sceneIndex\":6")))));
        assertMessage("FinalStoryboard 未覆盖 Scene: 1", () -> parser.validateCoverage(
                storyAnalysis(), parser.finalStoryboard(wrap("FINAL_STORYBOARD", "{\"shots\":[]}"))));
        assertMessage("FinalStoryboard shotIndex 必须从 1 连续递增", () -> parser.finalStoryboard(wrap(
                "FINAL_STORYBOARD", finalStoryboardJson().replace("\"shotIndex\":1", "\"shotIndex\":2"))));
        assertMessage("FinalStoryboard dialogue 非空时 speaker 不能为空", () -> parser.finalStoryboard(wrap(
                "FINAL_STORYBOARD", finalStoryboardJson().replace("\"speaker\":\"amy\"", "\"speaker\":null"))));
        assertMessage("FinalStoryboard dialogue 非空时 textAnchor 不能为空", () -> parser.finalStoryboard(wrap(
                "FINAL_STORYBOARD", finalStoryboardJson().replace("\"textAnchor\":{\"x\":0.2,\"y\":0.3}", "\"textAnchor\":null"))));
        assertMessage("textAnchor.x 必须在 0 到 1 之间", () -> parser.finalStoryboard(wrap(
                "FINAL_STORYBOARD", finalStoryboardJson().replace("\"x\":0.2", "\"x\":1.1"))));
        assertMessage("FinalStoryboard narration 长度不能超过 180", () -> parser.finalStoryboard(wrap(
                "FINAL_STORYBOARD", finalStoryboardJson().replace("a short narration", "x".repeat(181)))));
    }

    @Test
    void rejectsMoreThanFiveShotsPerSceneAndMoreThanTwentyShots() {
        String sixShots = "{\"shots\":[" + finalShot("shot-1", 1, 1) + "," + finalShot("shot-2", 1, 2)
                + "," + finalShot("shot-3", 1, 3) + "," + finalShot("shot-4", 1, 4) + ","
                + finalShot("shot-5", 1, 5) + "," + finalShot("shot-6", 1, 6) + "]}";
        assertMessage("FinalStoryboard 每个 Scene 最多 5 个镜头", () -> parser.finalStoryboard(wrap("FINAL_STORYBOARD", sixShots)));

        StringBuilder shots = new StringBuilder("{\"shots\":[");
        for (int index = 1; index <= 21; index++) {
            if (index > 1) shots.append(',');
            shots.append(finalShot("shot-" + index, index, 1));
        }
        shots.append("]}");
        assertMessage("FinalStoryboard 全篇最多 20 个镜头", () -> parser.finalStoryboard(wrap("FINAL_STORYBOARD", shots.toString())));
    }

    @Test
    void validatesPromptReferencesAndRejectsTextRenderingButAllowsNegativeOrNoText() {
        assertDoesNotThrow(() -> parser.validateReferences(shotPromptPlan(), referencePlan()));
        assertDoesNotThrow(() -> parser.validateShotPrompts(finalStoryboard(), shotPromptPlan()));
        ShotPromptPlan unknownReference = parser.shotPromptPlan(wrap("SHOT_PROMPT_PLAN", shotPromptPlanJson()
                .replace("asset-amy", "asset-missing")));
        assertMessage("ShotPromptPlan 引用了未知 referenceAssetKey: asset-missing", () ->
                parser.validateReferences(unknownReference, referencePlan()));
        assertMessage("ShotPromptPlan prompt 不得要求图片模型渲染文字", () -> parser.shotPromptPlan(wrap(
                "SHOT_PROMPT_PLAN", shotPromptPlanJson().replace("Amy walks", "render text on a sign"))));
        assertDoesNotThrow(() -> parser.shotPromptPlan(wrap("SHOT_PROMPT_PLAN", shotPromptPlanJson()
                .replace("no text", "without words, no text"))));
    }

    @Test
    void rejectsConflictingTemporalMomentsAndPreflightSelfInconsistency() {
        assertMessage("StoryboardProposal 同一镜头包含互斥时间点", () -> parser.storyboardProposal(wrap(
                "STORYBOARD_PROPOSAL", storyboardProposalJson().replace("Amy walks before lunch", "Amy walks before and after lunch"))));
        assertMessage("PreflightPlan shotIndex 必须从 1 连续递增", () -> parser.preflight(wrap(
                "PREFLIGHT_PLAN", preflightJson().replace("\"shots\":[", "\"shots\":[" + preflightShot("shot-2", 1, 2) + ","))));
        PreflightPlan unknownReference = parser.preflight(wrap("PREFLIGHT_PLAN", preflightJson().replace(
                "\"referenceAssetKeys\":[\"asset-amy\"]", "\"referenceAssetKeys\":[\"asset-missing\"]")));
        assertMessage("PreflightPlan 引用了未知 referenceAssetKey: asset-missing", () -> parser.validatePreflight(unknownReference));
    }

    private StoryAnalysis storyAnalysis() {
        return parser.storyAnalysis(wrap("STORY_ANALYSIS", storyAnalysisJson()));
    }

    private FinalStoryboard finalStoryboard() {
        return parser.finalStoryboard(wrap("FINAL_STORYBOARD", finalStoryboardJson()));
    }

    private ReferencePlan referencePlan() {
        return parser.referencePlan(wrap("REFERENCE_PLAN", referencePlanJson()));
    }

    private ShotPromptPlan shotPromptPlan() {
        return parser.shotPromptPlan(wrap("SHOT_PROMPT_PLAN", shotPromptPlanJson()));
    }

    private static void assertMessage(String expected, ThrowingRunnable action) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals(expected, exception.getMessage());
    }

    private static String wrap(String schemaKey, String json) {
        return "<" + schemaKey + "_JSON_BEGIN>\n" + json + "\n<" + schemaKey + "_JSON_END>";
    }

    private static String storyAnalysisJson() {
        return "{\"scenes\":[{\"sceneIndex\":1,\"title\":\"Park visit\",\"sourceExcerpt\":\"Amy walks\",\"summary\":\"Amy visits the park\"}],"
                + "\"beats\":[{\"beatKey\":\"beat-1\",\"sceneIndex\":1,\"order\":1,\"action\":\"Amy walks before lunch\",\"temporalMoment\":\"before lunch\"}],"
                + "\"characters\":[{\"characterKey\":\"amy\",\"name\":\"Amy\",\"description\":\"A child\"}],"
                + "\"locations\":[{\"locationKey\":\"park\",\"name\":\"Park\",\"description\":\"Green park\"}],"
                + "\"props\":[{\"propKey\":\"ball\",\"name\":\"Ball\",\"description\":\"Red ball\"}],"
                + "\"dialogues\":[{\"sceneIndex\":1,\"speaker\":\"amy\",\"text\":\"Hello!\"}],\"narration\":[{\"sceneIndex\":1,\"text\":\"A short narration\"}]}";
    }

    private static String continuityBibleJson() {
        return "{\"characters\":[{\"characterKey\":\"amy\",\"name\":\"Amy\",\"visualDescription\":\"brown hair\",\"clothing\":\"blue coat\",\"colors\":\"blue\",\"proportions\":\"child\",\"expressionRules\":\"kind\"}],"
                + "\"props\":[{\"propKey\":\"ball\",\"visualDescription\":\"round\",\"colors\":\"red\",\"invariants\":\"round\"}],\"invariants\":[\"same coat\"],\"forbiddenChanges\":[\"no age change\"]}";
    }

    private static String styleBibleJson() {
        return "{\"palette\":\"blue\",\"renderingStyle\":\"warm watercolor\",\"lighting\":\"soft\",\"cameraRules\":\"eye level\",\"environmentRules\":\"calm\",\"negativeRules\":[\"no text\"]}";
    }

    private static String storyboardProposalJson() {
        return "{\"shots\":[{\"sceneIndex\":1,\"beat\":\"beat-1\",\"action\":\"Amy walks before lunch\",\"characters\":[\"amy\"],\"location\":\"park\",\"dialogue\":\"Hello!\",\"narration\":\"A short narration\",\"splitReason\":\"opening\"}]}";
    }

    private static String finalStoryboardJson() {
        return "{\"shots\":[" + finalShot("shot-1", 1, 1) + "]}";
    }

    private static String finalShot(String shotKey, int sceneIndex, int shotIndex) {
        return "{\"shotKey\":\"" + shotKey + "\",\"sceneIndex\":" + sceneIndex + ",\"shotIndex\":" + shotIndex
                + ",\"sourceExcerpt\":\"Amy walks\",\"visualGoal\":\"show Amy\",\"dialogue\":\"Hello!\",\"narration\":\"a short narration\",\"speaker\":\"amy\",\"textAnchor\":{\"x\":0.2,\"y\":0.3}}";
    }

    private static String referencePlanJson() {
        return "{\"referenceAssets\":[{\"assetKey\":\"asset-amy\",\"type\":\"CHARACTER\",\"target\":\"amy\",\"prompt\":\"Amy portrait, no text\",\"negativePrompt\":\"text, watermark\"}]}";
    }

    private static String shotPromptPlanJson() {
        return "{\"shots\":[{\"shotKey\":\"shot-1\",\"prompt\":\"Amy walks, no text\",\"negativePrompt\":\"text, words\",\"referenceAssetKeys\":[\"asset-amy\"]}]}";
    }

    private static String preflightJson() {
        return "{\"referenceAssets\":[{\"assetKey\":\"asset-amy\",\"type\":\"CHARACTER\",\"target\":\"amy\",\"prompt\":\"Amy portrait, no text\",\"negativePrompt\":\"text\"}],\"shots\":["
                + preflightShot("shot-1", 1, 1) + "],\"auditSummary\":\"checked\"}";
    }

    private static String preflightShot(String shotKey, int sceneIndex, int shotIndex) {
        return "{\"shotKey\":\"" + shotKey + "\",\"sceneIndex\":" + sceneIndex + ",\"shotIndex\":" + shotIndex
                + ",\"prompt\":\"Amy walks, no text\",\"negativePrompt\":\"text\",\"referenceAssetKeys\":[\"asset-amy\"],\"speaker\":\"amy\",\"dialogue\":\"Hello!\",\"narration\":\"a short narration\",\"textAnchor\":{\"x\":0.2,\"y\":0.3}}";
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
