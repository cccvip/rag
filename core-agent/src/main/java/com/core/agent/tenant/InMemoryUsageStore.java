package com.core.agent.tenant;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版租户用量存储 — 原型使用，生产可替换为 Redis/数据库实现。
 */
public class InMemoryUsageStore implements UsageStore {

    private final Map<String, TenantUsage> usages = new ConcurrentHashMap<>();

    @Override
    public TenantUsage getUsage(String tenantId, String yearMonth) {
        String key = keyOf(tenantId, yearMonth);
        return usages.computeIfAbsent(key, k -> new TenantUsage(tenantId, yearMonth));
    }

    @Override
    public void recordUsage(String tenantId, String yearMonth, AgentCallRecord record) {
        TenantUsage usage = getUsage(tenantId, yearMonth);
        usage.add(record);
    }

    private String keyOf(String tenantId, String yearMonth) {
        return tenantId + ":" + yearMonth;
    }
}
