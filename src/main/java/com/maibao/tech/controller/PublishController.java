package com.maibao.tech.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import com.maibao.tech.common.BizException;

import com.maibao.tech.common.domain.ApiResponse;
import com.maibao.tech.publish.PublishService;
import com.maibao.tech.publish.domain.PublishRecord;

@RestController
@RequestMapping("/api/publish")
public class PublishController {

    private final PublishService publishService;

    public PublishController(PublishService publishService) {
        this.publishService = publishService;
    }

    /** delaySeconds 为空 = 立即发布；否则建定时发布记录 */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestParam String nodeId,
                                                  @RequestParam(required = false) Long delaySeconds) {
        Instant scheduledAt = delaySeconds == null ? null : Instant.now().plusSeconds(delaySeconds);
        PublishRecord r = publishService.create(nodeId, scheduledAt);
        return ApiResponse.ok(Map.of("id", r.getId(), "status", r.getStatus().name()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        PublishRecord r = publishService.get(id);
        return ApiResponse.ok(Map.of(
                "id", r.getId(),
                "status", r.getStatus().name(),
                "grayPercent", r.getGrayPercent(),
                "scheduled", r.getIsScheduled() == 1));
    }

    @PostMapping("/{id}/claim")
    public ApiResponse<Boolean> claim(@PathVariable Long id) {
        return ApiResponse.ok(publishService.claim(id));
    }

    /** 收尾；返回 false 表示记录已不在 PUBLISHING（重复收尾或已被超时回收） */
    @PostMapping("/{id}/finish")
    public ApiResponse<Boolean> finish(@PathVariable Long id, @RequestParam boolean success,
                                       @RequestParam(required = false) String reason) {
        return ApiResponse.ok(publishService.finish(id, success, reason));
    }

    /** 并发验证：返回成功抢占的线程数，正确值是 1 */
    @PostMapping("/{id}/race")
    public ApiResponse<Integer> race(@PathVariable Long id,
                                     @RequestParam(defaultValue = "20") int threads) {
        if (threads > 200) {
            throw new BizException(4003, "threads too large");
        }
        return ApiResponse.ok(publishService.race(id, threads));
    }
}
