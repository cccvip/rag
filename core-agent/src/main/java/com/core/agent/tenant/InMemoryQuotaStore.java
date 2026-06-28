package com.core.agent.tenant;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版租户配额存储 — 原型使用，生产可替换为数据库/配置中心实现。
 */
public class InMemoryQuotaStore implements QuotaStore {

    private final Map<String, TenantQuota> quotas = new ConcurrentHashMap<>();

    @Override
    public TenantQuota getQuota(String tenantId) {
        return quotas.get(tenantId);
    }

    @Override
    public void saveQuota(TenantQuota quota) {
        quotas.put(quota.getTenantId(), quota);
    }
}
