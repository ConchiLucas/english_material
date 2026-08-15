package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.ImageAgentDtos.StyleCreateRequest;
import com.aitaskcenter.dto.ImageAgentDtos.StylePresetView;
import com.aitaskcenter.dto.ImageAgentDtos.StyleUpdateRequest;
import com.aitaskcenter.service.ImageAgentService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/image-style-presets")
public class ImageStylePresetController {
    private final ImageAgentService service;

    public ImageStylePresetController(ImageAgentService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<StylePresetView>> styles() { return ApiResponse.ok(service.styles()); }

    @PostMapping
    public ApiResponse<StylePresetView> create(@RequestBody StyleCreateRequest request) {
        return ApiResponse.ok(service.createStyle(request), "画风预设已创建");
    }

    @PutMapping("/{presetId}")
    public ApiResponse<StylePresetView> update(@PathVariable long presetId, @RequestBody StyleUpdateRequest request) {
        return ApiResponse.ok(service.updateStyle(presetId, request), "画风预设已保存");
    }
}
