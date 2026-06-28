package com.core.agent.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单次 Agent 调用追踪上下文。
 *
 * 通过 {@link TraceContextHolder} 绑定到当前线程，并同步写入 MDC，
 * 使日志统一输出 [traceId] [tenantId] [scene] 等字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceContext {

    /**
     * 全局唯一调用链 ID。
     */
    private String traceId;

    /**
     * 租户 ID。
     */
    private String tenantId;

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * 业务场景，如 rag / ops / analytics。
     */
    private String scene;

    /**
     * 调用开始时间戳（毫秒）。
     */
    private long startTimeMs;
}
