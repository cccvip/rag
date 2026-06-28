package com.core.agent.tenant;

/**
 * 租户级限流器接口。
 */
public interface RateLimiter {

    /**
     * 尝试为指定租户获取一个 QPS 令牌。
     *
     * @return true 表示允许通过，false 表示被限流
     */
    boolean tryAcquire(String tenantId);
}
