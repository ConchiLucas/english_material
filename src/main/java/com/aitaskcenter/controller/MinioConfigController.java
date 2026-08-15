package com.aitaskcenter.controller;

import com.aitaskcenter.dto.ApiResponse;
import com.aitaskcenter.dto.MinioConfigRequest;
import com.aitaskcenter.dto.MinioConfigView;
import com.aitaskcenter.service.MinioConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/minio-config")
public class MinioConfigController {
    private final MinioConfigService service;

    public MinioConfigController(MinioConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<MinioConfigView> get() {
        return ApiResponse.ok(service.get());
    }

    @PutMapping
    public ApiResponse<MinioConfigView> save(@RequestBody MinioConfigRequest request) {
        return ApiResponse.ok(service.save(request), "MinIO 配置已保存");
    }

    @PostMapping("/test")
    public ApiResponse<Void> test(@RequestBody MinioConfigRequest request) {
        service.test(request);
        return ApiResponse.ok(null, "MinIO 连接验证成功");
    }
}
