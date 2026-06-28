package com.core.agent.tenant;

/**
 * 租户配额存储 — 可替换为数据库或 Redis 实现。
 */
public interface QuotaStore {

    /**
     * 获取租户配额；不存在时返回 null。
     */
    TenantQuota getQuota(String tenantId);

    /**
     * 保存或更新租户配额。
     */
    void saveQuota(TenantQuota quota);
}
