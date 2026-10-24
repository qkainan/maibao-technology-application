package com.maibao.tech.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import com.maibao.tech.common.domain.ApiResponse;
import com.maibao.tech.security.KeyVaultService;

@RestController
@RequestMapping("/api/keys")
public class ApiKeyController {

    private final KeyVaultService keyVaultService;

    public ApiKeyController(KeyVaultService keyVaultService) {
        this.keyVaultService = keyVaultService;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestParam String ownerId,
                                                   @RequestParam String projectId,
                                                   @RequestParam String provider,
                                                   @RequestParam String apiKey) {
        return ApiResponse.ok(keyVaultService.create(ownerId, projectId, provider, apiKey));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam String ownerId) {
        return ApiResponse.ok(keyVaultService.list(ownerId));
    }

    /** 越权校验：ownerId 不匹配时返回 4030 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id, @RequestParam String ownerId) {
        return ApiResponse.ok(keyVaultService.detail(ownerId, id));
    }
}
