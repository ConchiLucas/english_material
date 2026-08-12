package com.aitaskcenter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
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
                List.of(
                        "targeted-reviser",
                        "story-writer",
                        "story-director",
                        "pitch-humor",
                        "pitch-adventure",
                        "pitch-wonder",
                        "vocabulary-planner"),
                StoryAgentCatalog.require("quality-decider").downstream());
    }
}
