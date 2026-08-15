package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.ImageRunDtos.RunDetail;
import com.aitaskcenter.dto.ImageRunDtos.RunSummary;
import com.aitaskcenter.dto.ImageRunDtos.SourceStoryView;
import com.aitaskcenter.dto.ImageRunDtos.StartImageRunRequest;
import com.aitaskcenter.service.ImageRunExecutionService;
import com.aitaskcenter.service.ImageRunQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/image-runs")
public class ImageRunController {
    private final ImageRunQueryService queryService;
    private final ImageRunExecutionService executionService;

    public ImageRunController(ImageRunQueryService queryService, ImageRunExecutionService executionService) {
        this.queryService = queryService;
        this.executionService = executionService;
    }

    @GetMapping("/source-stories")
    public ApiResponse<List<SourceStoryView>> listSourceStories() {
        return ApiResponse.ok(queryService.listSourceStories());
    }

    @GetMapping
    public ApiResponse<List<RunSummary>> listRuns() {
        return ApiResponse.ok(queryService.listRuns());
    }

    @GetMapping("/{runId}")
    public ApiResponse<RunDetail> getRun(@PathVariable String runId) {
        return ApiResponse.ok(queryService.getRun(runId));
    }

    @PostMapping
    public ApiResponse<RunSummary> createRun(@RequestBody StartImageRunRequest request) {
        return ApiResponse.ok(executionService.createRun(request), "图片批次已创建");
    }
}
