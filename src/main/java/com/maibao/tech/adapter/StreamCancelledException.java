package com.maibao.tech.adapter;

/**
 * 客户端已断开（或上游被主动取消）时抛出，用于把取消信号从写端传回读端。
 *
 * <p>它不是故障：控制器收到后静默结束，不记 ERROR 日志。</p>
 */
public class StreamCancelledException extends RuntimeException {

    public StreamCancelledException() {
        super("stream cancelled by client");
    }
}
