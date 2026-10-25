package com.maibao.tech.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.maibao.tech.common.domain.ApiResponse;
import com.maibao.tech.usage.CostQuotaService;
import com.maibao.tech.usage.domain.QuotaView;

/** 配额查询：前端展示"今日还剩多少额度"。 */
@RestController
@RequestMapping("/api/quota")
public class QuotaController {

    private final CostQuotaService quotaService;

    public QuotaController(CostQuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @GetMapping
    public ApiResponse<QuotaView> quota(@RequestParam(defaultValue = "anonymous") String userId) {
        return ApiResponse.ok(quotaService.current(userId));
    }
}
