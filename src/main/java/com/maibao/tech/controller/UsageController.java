package com.maibao.tech.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.maibao.tech.common.domain.ApiResponse;
import com.maibao.tech.usage.UsageService;
import com.maibao.tech.usage.domain.vo.CostView;
import com.maibao.tech.usage.domain.vo.QueryLogView;
import com.maibao.tech.usage.domain.vo.UsageSummaryView;

/** 对话流水与成本报表（模拟业务）。 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService usageService;

    public UsageController(UsageService usageService) {
        this.usageService = usageService;
    }

    /** 最近对话流水；userId 留空表示查全部 */
    @GetMapping("/queries")
    public ApiResponse<List<QueryLogView>> queries(@RequestParam(defaultValue = "") String userId,
                                                   @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(usageService.recentQueries(userId, limit));
    }

    /** 按模型 × 天的成本聚合 */
    @GetMapping("/cost")
    public ApiResponse<List<CostView>> cost(@RequestParam(defaultValue = "") String userId) {
        return ApiResponse.ok(usageService.costByModel(userId));
    }

    /** 某个用户的累计用量 */
    @GetMapping("/summary")
    public ApiResponse<UsageSummaryView> summary(@RequestParam(defaultValue = "") String userId) {
        return ApiResponse.ok(usageService.summary(userId));
    }
}
