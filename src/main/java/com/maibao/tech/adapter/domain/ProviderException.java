package com.maibao.tech.adapter.domain;

/** 适配器统一抛出的厂商异常，带归一后的错误类型。 */
public class ProviderException extends RuntimeException {

    private final ProviderError error;

    public ProviderException(ProviderError error, String message) {
        super(message);
        this.error = error;
    }

    public ProviderException(ProviderError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public ProviderError error() {
        return error;
    }
}
