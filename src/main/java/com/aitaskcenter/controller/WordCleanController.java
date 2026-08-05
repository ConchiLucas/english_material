package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.PageResult;
import com.aitaskcenter.dto.WordCleanFacets;
import com.aitaskcenter.dto.WordCleanItem;
import com.aitaskcenter.dto.WordCleanSentenceItem;
import com.aitaskcenter.service.WordCleanService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/word-clean")
public class WordCleanController {
    private final WordCleanService service;

    public WordCleanController(WordCleanService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<WordCleanItem>> list(
            @RequestParam Long connectionId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer pepDifficulty,
            @RequestParam(required = false) Integer sourceDifficulty,
            @RequestParam(required = false) Integer difficultyMin,
            @RequestParam(required = false) Integer difficultyMax,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.list(
                connectionId,
                keyword,
                pepDifficulty,
                sourceDifficulty,
                difficultyMin,
                difficultyMax,
                sortBy,
                sortOrder,
                page,
                pageSize));
    }

    @GetMapping("/facets")
    public ApiResponse<WordCleanFacets> facets(@RequestParam Long connectionId) {
        return ApiResponse.ok(service.facets(connectionId));
    }

    @GetMapping("/{id}/sentences")
    public ApiResponse<List<WordCleanSentenceItem>> sentences(
            @RequestParam Long connectionId,
            @PathVariable long id) {
        return ApiResponse.ok(service.sentences(connectionId, id));
    }
}
