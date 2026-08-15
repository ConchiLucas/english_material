package com.aitaskcenter.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertMessage("STORY_ANALYSIS JSON 存在重复字段", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS",
                "{\"scenes\":[],\"scenes\":[],\"beats\":[],\"characters\":[],\"locations\":[],\"props\":[],\"dialogues\":[],\"narration\":[]}")));
        assertMessage("STORY_ANALYSIS JSON 包含未知字段", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS",
                "{\"scenes\":[],\"beats\":[],\"characters\":[],\"locations\":[],\"props\":[],\"dialogues\":[],\"narration\":[],\"extra\":true}")));
        assertMessage("STORY_ANALYSIS JSON 缺少字段: narration", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS",
                "{\"scenes\":[],\"beats\":[],\"characters\":[],\"locations\":[],\"props\":[],\"dialogues\":[]}")));
        assertMessage("STORY_ANALYSIS.scenes 必须是数组", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS",
                "{\"scenes\":{},\"beats\":[],\"characters\":[],\"locations\":[],\"props\":[],\"dialogues\":[],\"narration\":[]}")));
        assertMessage("STORY_ANALYSIS.scenes item 包含未知字段", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS", storyAnalysisJson().replace("\"summary\":\"Amy visits the park\"", "\"summary\":\"Amy visits the park\",\"extra\":true"))));
    }

    @Test
    void validatesUniqueKeysAndContinuityReferences() {
        assertMessage("StoryAnalysis characterKey 存在重复值", () -> parser.storyAnalysis(wrap("STORY_ANALYSIS", storyAnalysisJson()
                .replace(
                        "}],\"locations\":",
                        "},{\"characterKey\":\"amy\",\"name\":\"Amy Two\",\"description\":\"duplicate\"}],\"locations\":"))));

        ContinuityBible unknownCharacter = parser.continuityBible(wrap("CONTINUITY_BIBLE", continuityBibleJson()
                .replace("\"characterKey\":\"amy\"", "\"characterKey\":\"ben\"")));
        assertMessage("ContinuityBible characterKey 必须引用 StoryAnalysis", () ->
                parser.validateContinuityReferences(storyAnalysis(), unknownCharacter));
    }

    @Test
    void validatesProposalCharactersAndLocationsAgainstAnalysis() {
        StoryboardProposal proposal = parser.storyboardProposal(wrap("STORYBOARD_PROPOSAL", storyboardProposalJson()
                .replace("[\"amy\"]", "[\"ben\"]")));
        assertMessage("StoryboardProposal characterKey 必须引用 StoryAnalysis", () ->
                parser.validateProposalReferences(storyAnalysis(), proposal));

        StoryboardProposal unknownLocation = parser.storyboardProposal(wrap("STORYBOARD_PROPOSAL", storyboardProposalJson()
                .replace("\"park\"", "\"beach\"")));
        assertMessage("StoryboardProposal locationKey 必须引用 StoryAnalysis", () ->
                parser.validateProposalReferences(storyAnalysis(), unknownLocation));
    }

    @Test
    void validatesFinalStoryboardLimitsCoverageOrderDialogueAndAnchor() {
        assertMessage("FinalStoryboard sceneIndex 必须引用 StoryAnalysis Scene", () -> parser.validateCoverage(
                storyAnalysis(), parser.finalStoryboard(wrap("FINAL_STORYBOARD", finalStoryboardJson().replace("\"sceneIndex\":1", "\"sceneIndex\":6")))));
        assertMessage("FinalStoryboard 必须覆盖 StoryAnalysis 全部 Scene", () -> parser.validateCoverage(
                storyAnalysis(), parser.finalStoryboard(wrap("FINAL_STORYBOARD", "{\"shots\":[]}"))));
        assertMessage("FinalStoryboard shotIndex 必须从 1 连续递增", () -> parser.finalStoryboard(wrap(
                "FINAL_STORYBOARD", finalStoryboardJson().replace("\"shotIndex\":1", "\"shotIndex\":2"))));
        assertMessage("FinalStoryboard dialogue 非空时 speaker 不能为空", () -> parser.finalStoryboard(wrap(
                "FINAL_STORYBOARD", finalStoryboardJson().replace("\"speaker\":\"amy\"", "\"speaker\":\"\""))));
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
        assertDoesNotThrow(() -> parser.validateReferences(shotPromptPlan(), referencePlan(), finalStoryboard()));
        assertDoesNotThrow(() -> parser.validateShotPrompts(finalStoryboard(), shotPromptPlan()));
        ShotPromptPlan unknownReference = parser.shotPromptPlan(wrap("SHOT_PROMPT_PLAN", shotPromptPlanJson()
                .replace("asset-amy", "asset-missing")));
        assertMessage("ShotPromptPlan referenceAssetKeys 必须引用 ReferencePlan", () ->
                parser.validateReferences(unknownReference, referencePlan(), finalStoryboard()));
        assertMessage("图片提示词不得要求模型绘制文字", () -> parser.shotPromptPlan(wrap(
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
        assertMessage("PreflightPlan 分镜引用了未知参考资产", () -> parser.preflight(wrap(
                "PREFLIGHT_PLAN", preflightJson().replace(
                        "\"referenceAssetKeys\":[\"asset-amy\",\"asset-park\"]", "\"referenceAssetKeys\":[\"asset-missing\",\"asset-park\"]"))));
    }

    @Test
    void rejectsJsonNullForEveryNonNullableCatalogStringField() {
        assertMessage("STYLE_BIBLE.palette 必须是 string", () -> parser.styleBible(wrap(
                "STYLE_BIBLE", styleBibleJson().replace("\"palette\":\"blue\"", "\"palette\":null"))));
        assertMessage("STORYBOARD_PROPOSAL.shots.action 必须是 string", () -> parser.storyboardProposal(wrap(
                "STORYBOARD_PROPOSAL", storyboardProposalJson().replace("\"action\":\"Amy walks before lunch\"", "\"action\":null"))));
        assertMessage("SHOT_PROMPT_PLAN.shots.prompt 必须是 string", () -> parser.shotPromptPlan(wrap(
                "SHOT_PROMPT_PLAN", shotPromptPlanJson().replace("\"prompt\":\"Amy walks, no text\"", "\"prompt\":null"))));
    }

    @Test
    void rejectsExplicitPositivePromptTextInstructionsButAllowsNegativeConstraints() {
        assertMessage("图片提示词不得要求模型绘制文字", () -> parser.shotPromptPlan(wrap(
                "SHOT_PROMPT_PLAN", shotPromptPlanJson().replace("Amy walks, no text", "write HELLO on the image"))));
        assertMessage("图片提示词不得要求模型绘制文字", () -> parser.shotPromptPlan(wrap(
                "SHOT_PROMPT_PLAN", shotPromptPlanJson().replace("Amy walks, no text", "spell \\\"BOOK\\\" on a sign"))));
        assertMessage("图片提示词不得要求模型绘制文字", () -> parser.shotPromptPlan(wrap(
                "SHOT_PROMPT_PLAN", shotPromptPlanJson().replace("Amy walks, no text", "show the word CAT"))));
        assertDoesNotThrow(() -> parser.shotPromptPlan(wrap("SHOT_PROMPT_PLAN", shotPromptPlanJson()
                .replace("Amy walks, no text", "Amy walks, no text, no letters, no watermark"))));
        assertDoesNotThrow(() -> parser.shotPromptPlan(wrap("SHOT_PROMPT_PLAN", shotPromptPlanJson()
                .replace("\"negativePrompt\":\"text, words\"", "\"negativePrompt\":\"text, words, watermark\""))));
    }

    @Test
    void rejectsOnlyExplicitlyConflictingActionsAndUnknownProposalBeats() {
        assertMessage("StoryboardProposal 同一镜头包含互斥时间点", () -> parser.storyboardProposal(wrap(
                "STORYBOARD_PROPOSAL", storyboardProposalJson().replace("Amy walks before lunch", "Amy is asleep and running at the same time"))));
        assertMessage("StoryboardProposal 同一镜头包含互斥时间点", () -> parser.storyboardProposal(wrap(
                "STORYBOARD_PROPOSAL", storyboardProposalJson().replace("Amy walks before lunch", "the door is open and closed simultaneously"))));
        assertMessage("StoryboardProposal 同一镜头包含互斥时间点", () -> parser.storyboardProposal(wrap(
                "STORYBOARD_PROPOSAL", storyboardProposalJson().replace("Amy walks before lunch", "he is sitting and standing"))));
        StoryboardProposal proposal = parser.storyboardProposal(wrap("STORYBOARD_PROPOSAL", storyboardProposalJson()
                .replace("\"beat-1\"", "\"beat-missing\"")));
        assertMessage("StoryboardProposal 分镜引用了未知故事节拍", () ->
                parser.validateProposalReferences(storyAnalysis(), proposal));
    }

    @Test
    void boundsRawJsonStructureStringsAndArraysBeforeMaterializingRecords() {
        assertMessage("图片规划原始输出超过最大长度", () -> parser.storyAnalysis("x".repeat(512_001)));
        String deeplyNested = "{\"x\":".repeat(33) + "0" + "}".repeat(33);
        assertMessage("STORY_ANALYSIS JSON 格式无效", () -> parser.storyAnalysis(wrap("STORY_ANALYSIS", deeplyNested)));
        assertMessage("STYLE_BIBLE JSON 格式无效", () -> parser.styleBible(wrap(
                "STYLE_BIBLE", styleBibleJson().replace("warm watercolor", "x".repeat(20_001)))));
        assertMessage("STYLE_BIBLE.negativeRules 数量不能超过 200", () -> parser.styleBible(wrap(
                "STYLE_BIBLE", styleBibleJson().replace(
                        "[\"no text\"]", "[" + String.join(",", java.util.Collections.nCopies(201, "\"no text\"")) + "]"))));
    }

    @Test
    void boundsReferenceGenerationCostAndPerShotAdapterInputs() {
        String twentyOneReferences = "{\"referenceAssets\":" + referenceAssetsJson(21)
                + ",\"shots\":[],\"auditSummary\":\"checked\"}";
        assertMessage("PreflightPlan referenceAssets 数量不能超过 20", () ->
                parser.preflight(wrap("PREFLIGHT_PLAN", twentyOneReferences)));

        String nineKeys = referenceKeysJson(9);
        String nineReferencePreflight = "{\"referenceAssets\":" + referenceAssetsJson(9)
                + ",\"shots\":[" + preflightShot("shot-1", 1, 1)
                        .replace("[\"asset-amy\",\"asset-park\"]", nineKeys)
                + "],\"auditSummary\":\"checked\"}";
        assertMessage("PreflightPlan referenceAssetKeys 数量不能超过 8", () ->
                parser.preflight(wrap("PREFLIGHT_PLAN", nineReferencePreflight)));

        String nineReferencePromptPlan = shotPromptPlanJson()
                .replace("[\"asset-amy\",\"asset-park\"]", nineKeys);
        assertMessage("ShotPromptPlan referenceAssetKeys 数量不能超过 8", () ->
                parser.shotPromptPlan(wrap("SHOT_PROMPT_PLAN", nineReferencePromptPlan)));
    }

    @Test
    void rejectsDuplicateNormalizedReferenceTargets() {
        String duplicateTarget = "{\"referenceAssets\":["
                + "{\"assetKey\":\"asset-amy-1\",\"type\":\"character\",\"target\":\"Amy\",\"prompt\":\"Amy portrait, no text\",\"negativePrompt\":\"text\"},"
                + "{\"assetKey\":\"asset-amy-2\",\"type\":\" CHARACTER \",\"target\":\" amy \",\"prompt\":\"Amy portrait, no text\",\"negativePrompt\":\"text\"}],"
                + "\"shots\":[],\"auditSummary\":\"checked\"}";

        assertMessage("PreflightPlan type + target 存在重复值", () ->
                parser.preflight(wrap("PREFLIGHT_PLAN", duplicateTarget)));
    }

    @Test
    void enforcesCanonicalStorageSafeAssetAndShotKeysAcrossFinalPlans() {
        assertMessage("ReferencePlan assetKey 必须使用安全存储字符且长度不能超过 100", () ->
                parser.referencePlan(wrap("REFERENCE_PLAN", referencePlanJson()
                        .replace("asset-amy", "asset/amy"))));
        assertMessage("ReferencePlan assetKey 必须使用安全存储字符且长度不能超过 100", () ->
                parser.referencePlan(wrap("REFERENCE_PLAN", referencePlanJson()
                        .replace("asset-amy", "a".repeat(101)))));
        assertMessage("FinalStoryboard shotKey 必须使用安全存储字符且长度不能超过 80", () ->
                parser.finalStoryboard(wrap("FINAL_STORYBOARD", finalStoryboardJson()
                        .replace("shot-1", "shot/1"))));
        assertMessage("FinalStoryboard shotKey 必须使用安全存储字符且长度不能超过 80", () ->
                parser.finalStoryboard(wrap("FINAL_STORYBOARD", finalStoryboardJson()
                        .replace("shot-1", "s".repeat(81)))));
        assertMessage("ShotPromptPlan referenceAssetKeys 必须使用安全存储字符且长度不能超过 100", () ->
                parser.shotPromptPlan(wrap("SHOT_PROMPT_PLAN", shotPromptPlanJson()
                        .replace("[\"asset-amy\",\"asset-park\"]", "[\"asset/amy\"]"))));
    }

    @Test
    void trimsStorageKeysBeforeValidationAndCrossReferenceComparison() {
        PreflightPlan plan = parser.preflight(wrap("PREFLIGHT_PLAN", preflightJson()
                .replace("\"asset-amy\"", "\"  asset-amy  \"")
                .replace("\"shot-1\"", "\"  shot-1  \"")));

        assertEquals("asset-amy", plan.referenceAssets().get(0).assetKey());
        assertEquals("asset-amy", plan.shots().get(0).referenceAssetKeys().get(0));
        assertEquals("shot-1", plan.shots().get(0).shotKey());
    }

    @Test
    void validatesAnalysisSceneReferencesContinuousBeatOrderAndProposalSceneBeatConsistency() {
        assertMessage("StoryAnalysis scenes 不能为空", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS", storyAnalysisJson().replace("[{\"sceneIndex\":1,\"title\":\"Park visit\",\"sourceExcerpt\":\"Amy walks\",\"summary\":\"Amy visits the park\"}]", "[]"))));
        assertMessage("StoryAnalysis beat sceneIndex 必须引用已有 Scene", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS", storyAnalysisJson().replace("\"sceneIndex\":1,\"order\":1", "\"sceneIndex\":2,\"order\":1"))));
        assertMessage("StoryAnalysis dialogue sceneIndex 必须引用已有 Scene", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS", storyAnalysisJson().replace(
                        "\"dialogues\":[{\"sceneIndex\":1,\"speaker\":\"amy\"",
                        "\"dialogues\":[{\"sceneIndex\":2,\"speaker\":\"amy\""))));
        assertMessage("StoryAnalysis narration sceneIndex 必须引用已有 Scene", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS", storyAnalysisJson().replace("\"narration\":[{\"sceneIndex\":1", "\"narration\":[{\"sceneIndex\":2"))));
        assertMessage("StoryAnalysis beat order 必须从 1 连续递增", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS", storyAnalysisJson().replace("\"order\":1", "\"order\":2"))));
        assertMessage("StoryAnalysis dialogue speaker 必须引用已有 characterKey", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS", storyAnalysisJson().replace("\"speaker\":\"amy\"", "\"speaker\":\"ben\""))));

        StoryboardProposal unknownScene = parser.storyboardProposal(wrap("STORYBOARD_PROPOSAL", storyboardProposalJson()
                .replace("\"sceneIndex\":1", "\"sceneIndex\":2")));
        assertMessage("StoryboardProposal sceneIndex 必须引用 StoryAnalysis Scene", () ->
                parser.validateProposalReferences(storyAnalysis(), unknownScene));
        assertMessage("StoryboardProposal beat 必须属于该 Scene", () -> parser.validateProposalReferences(
                twoSceneAnalysis(), parser.storyboardProposal(wrap("STORYBOARD_PROPOSAL", storyboardProposalJson().replace("\"beat-1\"", "\"beat-2\"")))));
    }

    @Test
    void requiresOneToFiveBeatsPerSceneAndAtLeastOneCharacterAndLocation() {
        assertMessage("StoryAnalysis 每个 Scene 必须包含 1 到 5 个节拍", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS", storyAnalysisJson().replace(
                        "\"beats\":[{\"beatKey\":\"beat-1\",\"sceneIndex\":1,\"order\":1,\"action\":\"Amy walks before lunch\",\"temporalMoment\":\"before lunch\"}]",
                        "\"beats\":[]"))));
        assertMessage("StoryAnalysis 每个 Scene 必须包含 1 到 5 个节拍", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS", analysisWithBeatCount(6))));
        assertMessage("STORY_ANALYSIS.beats.action 不能为空", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS", storyAnalysisJson().replace(
                        "\"action\":\"Amy walks before lunch\"", "\"action\":\"\""))));
        assertMessage("StoryAnalysis characters 不能为空", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS", storyAnalysisJson().replace(
                        "\"characters\":[{\"characterKey\":\"amy\",\"name\":\"Amy\",\"description\":\"A child\"}]",
                        "\"characters\":[]"))));
        assertMessage("StoryAnalysis locations 不能为空", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS", storyAnalysisJson().replace(
                        "\"locations\":[{\"locationKey\":\"park\",\"name\":\"Park\",\"description\":\"Green park\"}]",
                        "\"locations\":[]"))));
        assertMessage("StoryAnalysis 角色和地点参考资产总数不能超过 20", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS", analysisWithReferenceTargetCount(11, 10))));
        assertMessage("StoryAnalysis 全篇节拍总数不能超过 20", () -> parser.storyAnalysis(wrap(
                "STORY_ANALYSIS", analysisWithFiveScenesAndFiveBeatsEach())));
    }

    @Test
    void requiresEachStoryboardProposalToCoverEveryBeatWithoutCrossingScenes() {
        StoryAnalysis analysis = twoBeatAnalysis();
        StoryboardProposal missingBeat = parser.storyboardProposal(wrap(
                "STORYBOARD_PROPOSAL", storyboardProposalJson()));
        assertMessage("StoryboardProposal 必须覆盖 StoryAnalysis 全部 beatKey", () ->
                parser.validateProposalReferences(analysis, missingBeat));

        String duplicateKey = "{\"shots\":[" + proposalShot("proposal-1", "beat-1", 1)
                + "," + proposalShot("proposal-1", "beat-2", 1) + "]}";
        assertMessage("StoryboardProposal shotKey 存在重复值", () -> parser.storyboardProposal(wrap(
                "STORYBOARD_PROPOSAL", duplicateKey)));
    }

    @Test
    void requiresFinalStoryboardToPreserveEveryBeatAndOriginateFromAProposal() {
        StoryAnalysis analysis = twoBeatAnalysis();
        StoryboardProposal proposal = parser.storyboardProposal(wrap(
                "STORYBOARD_PROPOSAL", twoBeatProposalJson()));
        FinalStoryboard missingBeat = parser.finalStoryboard(wrap(
                "FINAL_STORYBOARD", finalStoryboardJson()));
        assertMessage("FinalStoryboard 必须覆盖 StoryAnalysis 全部 beatKey", () ->
                parser.validateCoverage(analysis, proposal, proposal, missingBeat));

        FinalStoryboard fullCoverage = parser.finalStoryboard(wrap(
                "FINAL_STORYBOARD", twoBeatFinalStoryboardJson()));
        assertDoesNotThrow(() -> parser.validateCoverage(analysis, proposal, proposal, fullCoverage));

        FinalStoryboard unknownProposalShot = parser.finalStoryboard(wrap(
                "FINAL_STORYBOARD", finalStoryboardJson().replace("shot-1", "shot-unknown")));
        assertMessage("FinalStoryboard shotKey 必须来自 StoryboardProposal", () ->
                parser.validateCoverage(storyAnalysis(), storyboardProposal(), storyboardProposal(), unknownProposalShot));

        assertMessage("FINAL_STORYBOARD.shots.action 不能为空", () -> parser.finalStoryboard(wrap(
                "FINAL_STORYBOARD", finalStoryboardJson().replace(
                        "\"action\":\"Amy walks before lunch\"", "\"action\":\"\""))));
    }

    @Test
    void requiresExactlyOneReferenceForEveryDeclaredCharacterAndLocation() {
        ReferencePlan missingLocation = parser.referencePlan(wrap(
                "REFERENCE_PLAN", referencePlanJson().replace(
                        ",{\"assetKey\":\"asset-park\",\"type\":\"LOCATION\",\"target\":\"park\",\"prompt\":\"Green park, no text\",\"negativePrompt\":\"text\"}", "")));
        assertMessage("ReferencePlan 必须为 StoryAnalysis 每个角色和地点各生成一个参考资产", () ->
                parser.validateReferenceTargets(missingLocation, storyAnalysis(), continuityBible()));
        assertMessage("ReferencePlan referenceAssets 不能为空", () -> parser.validateReferenceTargets(
                parser.referencePlan(wrap("REFERENCE_PLAN", "{\"referenceAssets\":[]}")),
                storyAnalysis(), continuityBible()));
        assertMessage("ReferencePlan referenceAssets 不能为空", () -> parser.referencePlan(wrap(
                "REFERENCE_PLAN", "{\"referenceAssets\":[]}")));
        assertDoesNotThrow(() -> parser.validateReferenceTargets(referencePlan(), storyAnalysis(), continuityBible()));
    }

    @Test
    void requiresEveryShotToReferenceItsLocationAndAllAppearingCharacters() {
        ShotPromptPlan missingLocation = parser.shotPromptPlan(wrap(
                "SHOT_PROMPT_PLAN", shotPromptPlanJson().replace(
                        "[\"asset-amy\",\"asset-park\"]", "[\"asset-amy\"]")));
        assertMessage("ShotPromptPlan 分镜必须引用所属地点参考资产", () ->
                parser.validateReferences(missingLocation, referencePlan(), finalStoryboard()));

        ShotPromptPlan missingCharacter = parser.shotPromptPlan(wrap(
                "SHOT_PROMPT_PLAN", shotPromptPlanJson().replace(
                        "[\"asset-amy\",\"asset-park\"]", "[\"asset-park\"]")));
        assertMessage("ShotPromptPlan 分镜必须引用全部出场角色参考资产", () ->
                parser.validateReferences(missingCharacter, referencePlan(), finalStoryboard()));

        PreflightPlan preflightMissingLocation = parser.preflight(wrap(
                "PREFLIGHT_PLAN", preflightJson().replace(
                        "[\"asset-amy\",\"asset-park\"]", "[\"asset-amy\"]")));
        assertMessage("PreflightPlan 分镜必须引用所属地点参考资产", () -> parser.validatePreflight(
                preflightMissingLocation, finalStoryboard(), storyAnalysis(), continuityBible()));
    }

    @Test
    void validatesClosedReferenceTypesTargetsAndCompletePreflightCrossPlan() {
        assertEquals("CHARACTER", parser.referencePlan(wrap("REFERENCE_PLAN", referencePlanJson())).referenceAssets().get(0).type());
        assertEquals("LOCATION", parser.referencePlan(wrap("REFERENCE_PLAN", referencePlanJson()
                .replace("\"type\":\"CHARACTER\"", "\"type\":\"location\""))).referenceAssets().get(0).type());
        assertMessage("ReferencePlan type 必须为 CHARACTER 或 LOCATION", () -> parser.referencePlan(wrap(
                "REFERENCE_PLAN", referencePlanJson().replace("\"type\":\"CHARACTER\"", "\"type\":\"PROP\""))));
        assertMessage("ReferencePlan CHARACTER target 必须引用角色", () -> parser.validateReferenceTargets(
                parser.referencePlan(wrap("REFERENCE_PLAN", referencePlanJson().replace("\"target\":\"amy\"", "\"target\":\"ben\""))),
                storyAnalysis(), continuityBible()));
        assertDoesNotThrow(() -> parser.validatePreflight(preflight(), finalStoryboard(), storyAnalysis(), continuityBible()));
        assertMessage("PreflightPlan 分镜必须与 FinalStoryboard 完全一致", () -> parser.validatePreflight(
                parser.preflight(wrap("PREFLIGHT_PLAN", preflightJson().replace("\"shotKey\":\"shot-1\"", "\"shotKey\":\"shot-x\""))),
                finalStoryboard(), storyAnalysis(), continuityBible()));
        assertMessage("PreflightPlan dialogue speaker 必须引用 StoryAnalysis", () -> parser.validatePreflight(
                parser.preflight(wrap("PREFLIGHT_PLAN", preflightJson().replace("\"speaker\":\"amy\"", "\"speaker\":\"ben\""))),
                finalStoryboard(), storyAnalysis(), continuityBible()));
    }

    @Test
    void detectsOnlyBoundedExplicitTextInstructionsAndTokenizedActionConflicts() {
        assertMessage("图片提示词不得要求模型绘制文字", () -> parser.shotPromptPlan(wrap(
                "SHOT_PROMPT_PLAN", shotPromptPlanJson().replace("Amy walks, no text", "display a placard that says HELLO"))));
        assertMessage("图片提示词不得要求模型绘制文字", () -> parser.shotPromptPlan(wrap(
                "SHOT_PROMPT_PLAN", shotPromptPlanJson().replace("Amy walks, no text", "put word HELLO on chalkboard"))));
        assertDoesNotThrow(() -> parser.shotPromptPlan(wrap("SHOT_PROMPT_PLAN", shotPromptPlanJson()
                .replace("Amy walks, no text", "do not display text"))));
        assertDoesNotThrow(() -> parser.shotPromptPlan(wrap("SHOT_PROMPT_PLAN", shotPromptPlanJson()
                .replace("Amy walks, no text", "no text, letters, logos, watermark"))));
        assertMessage("图片提示词不得要求模型绘制文字", () -> parser.shotPromptPlan(wrap(
                "SHOT_PROMPT_PLAN", shotPromptPlanJson().replace("Amy walks, no text", "no text, show the word CAT"))));
        assertDoesNotThrow(() -> parser.storyboardProposal(wrap("STORYBOARD_PROPOSAL", storyboardProposalJson()
                .replace("Amy walks before lunch", "walking " + "slowly ".repeat(2_000)))));
    }

    @Test
    void normalizesCrossReferencesRejectsNonFiniteAnchorsAndNeverLeaksModelValuesInErrors() {
        FinalStoryboard storyboard = parser.finalStoryboard(wrap("FINAL_STORYBOARD", finalStoryboardJson()
                .replace("\"shotKey\":\"shot-1\"", "\"shotKey\":\" shot-1 \"")));
        ShotPromptPlan prompts = parser.shotPromptPlan(wrap("SHOT_PROMPT_PLAN", shotPromptPlanJson()
                .replace("\"shotKey\":\"shot-1\"", "\"shotKey\":\" shot-1 \"")));
        assertEquals("shot-1", storyboard.shots().get(0).shotKey());
        assertDoesNotThrow(() -> parser.validateShotPrompts(storyboard, prompts));
        assertMessage("textAnchor.x 必须是有限数字", () -> parser.finalStoryboard(wrap(
                "FINAL_STORYBOARD", finalStoryboardJson().replace("\"x\":0.2", "\"x\":1e999"))));
        assertSafeMessage(() -> parser.storyAnalysis(wrap("STORY_ANALYSIS", storyAnalysisJson()
                .replace("\"narration\":", "\"x\\r\\nInjected\":true,\"narration\":"))), "Injected");
    }

    @Test
    void rejectsLowercaseExplicitTextInstructions() {
        assertMessage("图片提示词不得要求模型绘制文字", () -> parser.shotPromptPlan(wrap(
                "SHOT_PROMPT_PLAN", shotPromptPlanJson().replace("Amy walks, no text", "display a placard that says hello"))));
        assertMessage("图片提示词不得要求模型绘制文字", () -> parser.shotPromptPlan(wrap(
                "SHOT_PROMPT_PLAN", shotPromptPlanJson().replace("Amy walks, no text", "put word hello on chalkboard"))));
    }

    @Test
    void allowsNegatedWrittenTextInstruction() {
        assertDoesNotThrow(() -> parser.shotPromptPlan(wrap("SHOT_PROMPT_PLAN", shotPromptPlanJson()
                .replace("Amy walks, no text", "do not render written text"))));
    }

    @Test
    void allowsConflictingActionsWhenTheyBelongToDifferentActors() {
        assertDoesNotThrow(() -> parser.storyboardProposal(wrap("STORYBOARD_PROPOSAL", storyboardProposalJson()
                .replace("Amy walks before lunch", "Amy is asleep and Ben is running"))));
    }

    @Test
    void rejectsExplicitRepeatedSubjectAndPronounActionConflicts() {
        assertConflictingAction("Amy is asleep and Amy is running");
        assertConflictingAction("Amy is asleep while Amy is running");
        assertConflictingAction("Amy is asleep while she is running");
        assertConflictingAction("the door is open and the door is closed");
    }

    private StoryAnalysis storyAnalysis() {
        return parser.storyAnalysis(wrap("STORY_ANALYSIS", storyAnalysisJson()));
    }

    private StoryAnalysis twoSceneAnalysis() {
        String json = storyAnalysisJson()
                .replace(
                        "}],\"beats\":",
                        "},{\"sceneIndex\":2,\"title\":\"Home\",\"sourceExcerpt\":\"Amy rests\",\"summary\":\"Amy rests\"}],\"beats\":")
                .replace(
                        "\"temporalMoment\":\"before lunch\"}],\"characters\":",
                        "\"temporalMoment\":\"before lunch\"},{\"beatKey\":\"beat-2\",\"sceneIndex\":2,\"order\":1,\"action\":\"Amy rests\",\"temporalMoment\":\"after lunch\"}],\"characters\":");
        return parser.storyAnalysis(wrap("STORY_ANALYSIS", json));
    }

    private StoryAnalysis twoBeatAnalysis() {
        return parser.storyAnalysis(wrap("STORY_ANALYSIS", analysisWithBeatCount(2)));
    }

    private StoryboardProposal storyboardProposal() {
        return parser.storyboardProposal(wrap("STORYBOARD_PROPOSAL", storyboardProposalJson()));
    }

    private FinalStoryboard finalStoryboard() {
        return parser.finalStoryboard(wrap("FINAL_STORYBOARD", finalStoryboardJson()));
    }

    private ReferencePlan referencePlan() {
        return parser.referencePlan(wrap("REFERENCE_PLAN", referencePlanJson()));
    }

    private ContinuityBible continuityBible() {
        return parser.continuityBible(wrap("CONTINUITY_BIBLE", continuityBibleJson()));
    }

    private ShotPromptPlan shotPromptPlan() {
        return parser.shotPromptPlan(wrap("SHOT_PROMPT_PLAN", shotPromptPlanJson()));
    }

    private PreflightPlan preflight() {
        return parser.preflight(wrap("PREFLIGHT_PLAN", preflightJson()));
    }

    private static void assertMessage(String expected, ThrowingRunnable action) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals(expected, exception.getMessage());
    }

    private void assertConflictingAction(String action) {
        assertMessage("StoryboardProposal 同一镜头包含互斥时间点", () -> parser.storyboardProposal(wrap(
                "STORYBOARD_PROPOSAL", storyboardProposalJson().replace("Amy walks before lunch", action))));
    }

    private static void assertSafeMessage(ThrowingRunnable action, String forbidden) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, action::run);
        assertFalse(exception.getMessage().contains(forbidden));
        assertTrue(exception.getMessage().length() < 200);
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
        return "{\"shots\":[{\"shotKey\":\"shot-1\",\"sceneIndex\":1,\"beat\":\"beat-1\",\"action\":\"Amy walks before lunch\",\"characters\":[\"amy\"],\"location\":\"park\",\"dialogue\":\"Hello!\",\"narration\":\"A short narration\",\"splitReason\":\"opening\"}]}";
    }

    private static String twoBeatProposalJson() {
        return "{\"shots\":[" + proposalShot("shot-1", "beat-1", 1)
                + "," + proposalShot("shot-2", "beat-2", 1) + "]}";
    }

    private static String proposalShot(String shotKey, String beatKey, int sceneIndex) {
        return "{\"shotKey\":\"" + shotKey + "\",\"sceneIndex\":" + sceneIndex
                + ",\"beat\":\"" + beatKey + "\",\"action\":\"Amy moves\",\"characters\":[\"amy\"],"
                + "\"location\":\"park\",\"dialogue\":\"Hello!\",\"narration\":\"A short narration\",\"splitReason\":\"beat coverage\"}";
    }

    private static String analysisWithBeatCount(int count) {
        StringBuilder beats = new StringBuilder("\"beats\":[");
        for (int index = 1; index <= count; index++) {
            if (index > 1) beats.append(',');
            beats.append("{\"beatKey\":\"beat-").append(index)
                    .append("\",\"sceneIndex\":1,\"order\":").append(index)
                    .append(",\"action\":\"Action ").append(index)
                    .append("\",\"temporalMoment\":\"Moment ").append(index).append("\"}");
        }
        beats.append(']');
        return storyAnalysisJson().replace(
                "\"beats\":[{\"beatKey\":\"beat-1\",\"sceneIndex\":1,\"order\":1,\"action\":\"Amy walks before lunch\",\"temporalMoment\":\"before lunch\"}]",
                beats.toString());
    }

    private static String analysisWithReferenceTargetCount(int characterCount, int locationCount) {
        StringBuilder characters = new StringBuilder("\"characters\":[");
        for (int index = 1; index <= characterCount; index++) {
            if (index > 1) characters.append(',');
            characters.append("{\"characterKey\":\"character-").append(index)
                    .append("\",\"name\":\"Character ").append(index)
                    .append("\",\"description\":\"A child\"}");
        }
        characters.append(']');
        StringBuilder locations = new StringBuilder("\"locations\":[");
        for (int index = 1; index <= locationCount; index++) {
            if (index > 1) locations.append(',');
            locations.append("{\"locationKey\":\"location-").append(index)
                    .append("\",\"name\":\"Location ").append(index)
                    .append("\",\"description\":\"A place\"}");
        }
        locations.append(']');
        return storyAnalysisJson()
                .replace("\"characters\":[{\"characterKey\":\"amy\",\"name\":\"Amy\",\"description\":\"A child\"}]", characters)
                .replace("\"locations\":[{\"locationKey\":\"park\",\"name\":\"Park\",\"description\":\"Green park\"}]", locations)
                .replace("\"dialogues\":[{\"sceneIndex\":1,\"speaker\":\"amy\",\"text\":\"Hello!\"}]", "\"dialogues\":[]");
    }

    private static String analysisWithFiveScenesAndFiveBeatsEach() {
        StringBuilder scenes = new StringBuilder("\"scenes\":[");
        StringBuilder beats = new StringBuilder("\"beats\":[");
        int beatNumber = 1;
        for (int scene = 1; scene <= 5; scene++) {
            if (scene > 1) scenes.append(',');
            scenes.append("{\"sceneIndex\":").append(scene)
                    .append(",\"title\":\"Scene ").append(scene)
                    .append("\",\"sourceExcerpt\":\"Source\",\"summary\":\"Summary\"}");
            for (int order = 1; order <= 5; order++) {
                if (beatNumber > 1) beats.append(',');
                beats.append("{\"beatKey\":\"beat-").append(beatNumber++)
                        .append("\",\"sceneIndex\":").append(scene)
                        .append(",\"order\":").append(order)
                        .append(",\"action\":\"Action\",\"temporalMoment\":\"Moment\"}");
            }
        }
        scenes.append(']');
        beats.append(']');
        return storyAnalysisJson()
                .replace("\"scenes\":[{\"sceneIndex\":1,\"title\":\"Park visit\",\"sourceExcerpt\":\"Amy walks\",\"summary\":\"Amy visits the park\"}]", scenes)
                .replace("\"beats\":[{\"beatKey\":\"beat-1\",\"sceneIndex\":1,\"order\":1,\"action\":\"Amy walks before lunch\",\"temporalMoment\":\"before lunch\"}]", beats)
                .replace("\"dialogues\":[{\"sceneIndex\":1,\"speaker\":\"amy\",\"text\":\"Hello!\"}]", "\"dialogues\":[]")
                .replace("\"narration\":[{\"sceneIndex\":1,\"text\":\"A short narration\"}]", "\"narration\":[]");
    }

    private static String finalStoryboardJson() {
        return "{\"shots\":[" + finalShot("shot-1", 1, 1) + "]}";
    }

    private static String twoBeatFinalStoryboardJson() {
        return "{\"shots\":[" + finalShot("shot-1", 1, 1) + ","
                + finalShot("shot-2", 1, 2)
                        .replace("\"beat\":\"beat-1\"", "\"beat\":\"beat-2\"")
                        .replace("Amy walks before lunch", "Amy finds a ball")
                + "]}";
    }

    private static String finalShot(String shotKey, int sceneIndex, int shotIndex) {
        return "{\"shotKey\":\"" + shotKey + "\",\"sceneIndex\":" + sceneIndex + ",\"shotIndex\":" + shotIndex
                + ",\"beat\":\"beat-1\",\"action\":\"Amy walks before lunch\",\"characters\":[\"amy\"],\"location\":\"park\",\"sourceExcerpt\":\"Amy walks\",\"visualGoal\":\"show Amy\",\"dialogue\":\"Hello!\",\"narration\":\"a short narration\",\"speaker\":\"amy\",\"textAnchor\":{\"x\":0.2,\"y\":0.3}}";
    }

    private static String referencePlanJson() {
        return "{\"referenceAssets\":[{\"assetKey\":\"asset-amy\",\"type\":\"CHARACTER\",\"target\":\"amy\",\"prompt\":\"Amy portrait, no text\",\"negativePrompt\":\"text, watermark\"},{\"assetKey\":\"asset-park\",\"type\":\"LOCATION\",\"target\":\"park\",\"prompt\":\"Green park, no text\",\"negativePrompt\":\"text\"}]}";
    }

    private static String shotPromptPlanJson() {
        return "{\"shots\":[{\"shotKey\":\"shot-1\",\"prompt\":\"Amy walks, no text\",\"negativePrompt\":\"text, words\",\"referenceAssetKeys\":[\"asset-amy\",\"asset-park\"]}]}";
    }

    private static String preflightJson() {
        return "{\"referenceAssets\":[{\"assetKey\":\"asset-amy\",\"type\":\"CHARACTER\",\"target\":\"amy\",\"prompt\":\"Amy portrait, no text\",\"negativePrompt\":\"text\"},{\"assetKey\":\"asset-park\",\"type\":\"LOCATION\",\"target\":\"park\",\"prompt\":\"Green park, no text\",\"negativePrompt\":\"text\"}],\"shots\":["
                + preflightShot("shot-1", 1, 1) + "],\"auditSummary\":\"checked\"}";
    }

    private static String preflightShot(String shotKey, int sceneIndex, int shotIndex) {
        return "{\"shotKey\":\"" + shotKey + "\",\"sceneIndex\":" + sceneIndex + ",\"shotIndex\":" + shotIndex
                + ",\"prompt\":\"Amy walks, no text\",\"negativePrompt\":\"text\",\"referenceAssetKeys\":[\"asset-amy\",\"asset-park\"],\"speaker\":\"amy\",\"dialogue\":\"Hello!\",\"narration\":\"a short narration\",\"textAnchor\":{\"x\":0.2,\"y\":0.3}}";
    }

    private static String referenceAssetsJson(int count) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 1; index <= count; index++) {
            if (index > 1) json.append(',');
            json.append("{\"assetKey\":\"asset-").append(index)
                    .append("\",\"type\":\"CHARACTER\",\"target\":\"character-").append(index)
                    .append("\",\"prompt\":\"Character portrait, no text\",\"negativePrompt\":\"text\"}");
        }
        return json.append(']').toString();
    }

    private static String referenceKeysJson(int count) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 1; index <= count; index++) {
            if (index > 1) json.append(',');
            json.append("\"asset-").append(index).append("\"");
        }
        return json.append(']').toString();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
