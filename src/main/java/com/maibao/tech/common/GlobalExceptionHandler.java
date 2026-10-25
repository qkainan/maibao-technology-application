package com.maibao.tech.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.maibao.tech.adapter.StreamCancelledException;
import com.maibao.tech.adapter.domain.ProviderException;
import com.maibao.tech.common.domain.ApiResponse;

/**
 * 统一响应：业务错误走业务码，调用方错误走 4xx。
 *
 * <p>区分这两类的意义：业务错误（如归属校验失败）是"请求本身合法，但状态不允许"，
 * 用 HTTP 200 + 业务码便于前端统一处理；参数/方法/路径错误是"调用方式就不对"，
 * 必须让调用方在协议层看到 4xx，否则日志里只剩一条无法定位的堆栈。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException e) {
        log.warn("biz error: code={} msg={}", e.getCode(), e.getMessage());
        return ApiResponse.fail(e.getCode(), e.getMessage());
    }

    /** 厂商错误已归一到 ProviderError，这里映射成业务码，前端按类型决定重试还是换 key */
    @ExceptionHandler(ProviderException.class)
    public ApiResponse<Void> handleProvider(ProviderException e) {
        log.warn("provider error: type={} msg={}", e.error(), e.getMessage());
        return ApiResponse.fail(e.error().code(), e.getMessage());
    }

    /** 客户端主动断开不是故障，别落成 500 */
    @ExceptionHandler(StreamCancelledException.class)
    public ApiResponse<Void> handleCancelled(StreamCancelledException e) {
        log.debug("stream cancelled by client");
        return ApiResponse.fail(4990, "client disconnected");
    }

    /**
     * 路径变量或查询参数类型不匹配。
     *
     * <p>典型场景：把 API Key 之类的字符串填进了要求 Long 的 id 位置。</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String required = e.getRequiredType() == null ? "?" : e.getRequiredType().getSimpleName();
        String message = "参数 " + e.getName() + " 需要 " + required + " 类型，实际收到: " + e.getValue();
        log.warn("bad request: {}", message);
        return ResponseEntity.badRequest().body(ApiResponse.fail(4000, message));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        String message = "缺少必填参数: " + e.getParameterName();
        log.warn("bad request: {}", message);
        return ResponseEntity.badRequest().body(ApiResponse.fail(4001, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadBody(HttpMessageNotReadableException e) {
        log.warn("bad request: unreadable body");
        return ResponseEntity.badRequest().body(ApiResponse.fail(4002, "请求体不是合法 JSON"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethod(HttpRequestMethodNotSupportedException e) {
        String message = "不支持的请求方法: " + e.getMethod();
        log.warn("bad request: {}", message);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiResponse.fail(4005, message));
    }

    /**
     * 静态资源或未知路径不存在：直接 404，不套业务响应体。
     *
     * <p>浏览器每次打开页面都会请求 favicon.ico。如果走兜底，会变成 HTTP 200 + 业务错误码，
     * 既误导调用方，也在日志里留下与业务无关的堆栈。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResource(NoResourceFoundException e) {
        log.debug("resource not found: {} {}", e.getHttpMethod(), e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleOther(Exception e) {
        log.error("unexpected error", e);
        return ApiResponse.fail(500, "internal error");
    }
}
