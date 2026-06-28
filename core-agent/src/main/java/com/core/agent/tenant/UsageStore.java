package com.core.agent.tenant;

/**
 * 租户月度用量存储 — 可替换为数据库或 Redis 实现。
 */
public interface UsageStore {

    /**
     * 获取租户某月用量；不存在时返回新的空用量对象。
     */
    TenantUsage getUsage(String tenantId, String yearMonth);

    /**
     * 累加一次调用记录到对应月份的用量。
     */
    void recordUsage(String tenantId, String yearMonth, AgentCallRecord record);
}
