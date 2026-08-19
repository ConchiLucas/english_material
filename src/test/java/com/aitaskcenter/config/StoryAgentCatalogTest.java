package com.aitaskcenter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StoryAgentCatalogTest {
    @Test
    void exposesTwelveEditableAgentsAndFiveReadOnlyNodesWithUniqueKeys() {
        List<StoryAgentCatalog.NodeDefinition> nodes = StoryAgentCatalog.nodes();

        assertEquals(17, nodes.size());
        assertEquals(12, nodes.stream().filter(StoryAgentCatalog.NodeDefinition::editable).count());
        assertEquals(5, nodes.stream().filter(node -> !node.editable()).count());
        assertEquals(17, nodes.stream().map(StoryAgentCatalog.NodeDefinition::key).distinct().count());
        assertEquals(
                Set.of("AGENT", "PROGRAM", "HUMAN"),
                nodes.stream().map(StoryAgentCatalog.NodeDefinition::nodeKind).collect(java.util.stream.Collectors.toSet()));
        assertEquals("PROGRAM", StoryAgentCatalog.require("word-pack").nodeKind());
        assertEquals(
                List.of(
                        "vocabulary-planner",
                        "pitch-humor",
                        "pitch-adventure",
                        "pitch-wonder",
                        "story-director",
                        "story-writer",
                        "review-fun",
                        "review-language",
                        "review-continuity",
                        "story-scorer",
                        "quality-decider",
                        "targeted-reviser"),
                nodes.stream()
                        .filter(StoryAgentCatalog.NodeDefinition::editable)
                        .map(StoryAgentCatalog.NodeDefinition::key)
                        .toList());
    }

    @Test
    void exposesExpectedStagesAndQualityDecisionRoutes() {
        assertEquals(
                List.of("planning", "writing", "quality", "delivery"),
                StoryAgentCatalog.stages().stream()
                        .map(StoryAgentCatalog.StageDefinition::key)
                        .toList());
        assertEquals(
                List.of(
                        "目标词到三个匿名提案",
                        "同一主角、同一主线、逐场升级",
                        "审核、评分与决策完全分离",
                        "通过后进入人工审核"),
                StoryAgentCatalog.stages().stream()
                        .map(StoryAgentCatalog.StageDefinition::note)
                        .toList());
        assertEquals(
                List.of(1, 2, 3, 4),
                StoryAgentCatalog.stages().stream()
                        .map(StoryAgentCatalog.StageDefinition::order)
                        .toList());
        assertEquals(
                List.of("targeted-reviser", "story-writer"),
                StoryAgentCatalog.require("quality-decider").downstream());
        assertEquals(
                List.of("candidateStory", "targetGrade", "qualityRound"),
                StoryAgentCatalog.require("review-fun").variables());
        assertEquals(
                List.of("candidateStory", "targetWords", "wordUsage", "targetGrade", "qualityRound"),
                StoryAgentCatalog.require("review-language").variables());
        assertEquals(
                List.of("candidateStory", "storyBlueprint", "qualityRound"),
                StoryAgentCatalog.require("review-continuity").variables());
    }

    @Test
    void storyProducingDefaultsRequestOnlyTheEnglishStory() {
        String writerPrompt = StoryAgentCatalog.require("story-writer").defaultPrompt();
        String reviserPrompt = StoryAgentCatalog.require("targeted-reviser").defaultPrompt();

        assertTrue(writerPrompt.contains("只输出完整英文故事正文"));
        assertTrue(writerPrompt.contains("可见冲突"));
        assertTrue(writerPrompt.contains("The cat"));
        assertTrue(writerPrompt.contains("高中"));
        assertTrue(writerPrompt.contains("未指定学段"));
        assertTrue(writerPrompt.contains("8 到 16"));
        assertTrue(StoryAgentCatalog.require("story-scorer").defaultPrompt().contains("未指定学段"));
        assertTrue(StoryAgentCatalog.require("story-scorer").defaultPrompt().contains("初中及以后"));
        assertFalse(writerPrompt.contains("位置清单"));
        assertTrue(StoryAgentCatalog.require("story-director").defaultPrompt().contains("BEAT_COUNTS"));
        assertTrue(StoryAgentCatalog.require("vocabulary-planner").defaultPrompt().contains("8 到 16 个画面"));
        assertTrue(StoryAgentCatalog.require("pitch-humor").defaultPrompt().contains("8 到 16 个可入画动作"));
        assertTrue(StoryAgentCatalog.require("pitch-adventure").defaultPrompt().contains("8 和 9"));
        assertTrue(reviserPrompt.contains("只输出修订后的完整英文故事正文"));
        assertTrue(reviserPrompt.contains("不够 8 个可画动作"));
        assertFalse(reviserPrompt.contains("变更记录"));
    }
}
