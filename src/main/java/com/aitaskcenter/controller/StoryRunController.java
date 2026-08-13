package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.StoryRunDtos.RunDetail;
import com.aitaskcenter.dto.StoryRunDtos.RunSummary;
import com.aitaskcenter.service.StoryRunQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/story-runs")
public class StoryRunController {
    private final StoryRunQueryService queryService;

    public StoryRunController(StoryRunQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ApiResponse<List<RunSummary>> listRuns() {
        return ApiResponse.ok(queryService.listRuns());
    }

    @GetMapping("/{runId}")
    public ApiResponse<RunDetail> getRun(@PathVariable String runId) {
        return ApiResponse.ok(queryService.getRun(runId));
    }
}
