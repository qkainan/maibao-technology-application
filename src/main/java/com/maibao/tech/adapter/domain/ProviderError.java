package com.maibao.tech.adapter.domain;

/**
 * 归一后的厂商错误类型。
 *
 * <p>上层不需要知道是 OpenAI 的 429 还是别家的 "rate limit exceeded"，
 * 只按这几类决定重试、降级还是直接提示用户。</p>
 */
public enum ProviderError {

    /** 鉴权失败：不重试，提示换 key */
    AUTH(4010),
    /** 限流：退避重试或切备用厂商 */
    RATE_LIMIT(4029),
    /** 超时：有限重试 */
    TIMEOUT(4080),
    /** 内容被过滤：不重试，提示用户 */
    FILTERED(4090),
    /** 上游服务错误：可重试 */
    SERVER(5001),
    /** 其他 */
    UNKNOWN(5000);

    private final int code;

    ProviderError(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
