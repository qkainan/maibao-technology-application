package com.maibao.tech.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * favicon.ico 专用处理。
 *
 * <p>这个项目没有提供图标文件，但浏览器每次打开页面都会请求它。显式返回 404，
 * 避免请求落到静态资源处理器再被全局异常兜底改写成 HTTP 200 + 业务错误码。</p>
 */
@RestController
public class FaviconController {

    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.notFound().build();
    }
}
