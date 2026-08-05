package com.aitaskcenter.dto;

import java.util.List;

public record WordCleanFacets(
        List<WordCleanFacetItem> pepDifficulties,
        List<WordCleanFacetItem> sourceDifficulties,
        List<WordCleanFacetItem> difficultyRanges) {
}
