package com.aicabinet.trade.support;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 进程启动时刻。用于客户端判断「服务已重启、旧 token 应失效」。
 */
@Component
public class ServerBootMarker {

    private final long epochMillis = Instant.now().toEpochMilli();

    public long epochMillis() {
        return epochMillis;
    }
}
