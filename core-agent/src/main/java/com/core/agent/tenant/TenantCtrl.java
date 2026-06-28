package com.core.agent.tenant;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 租户管控 — 平台通用组件。
 *
 * 职责：
 * - 调用前：检查 Token 月度配额、QPS 限流
 * - 调用后：记录 Token、延迟、工具调用次数
 *
 * 存储与限流均通过接口抽象，内存版用于原型，生产可替换为 Redis/数据库实现。
 */
public class TenantCtrl {

    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final QuotaStore quotaStore;
    private final UsageStore usageStore;
    private final RateLimiter rateLimiter;

    public TenantCtrl(QuotaStore quotaStore, UsageStore usageStore, RateLimiter rateLimiter) {
        this.quotaStore = quotaStore;
        this.usageStore = usageStore;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 调用前检查 — Token 月度配额 + QPS 限流。
     */
    public TenantCheckResult check(String tenantId) {
        TenantQuota quota = quotaStore.getQuota(tenantId);
        if (quota == null) {
            // 未配置配额时默认放行；生产可改为拒绝
            return TenantCheckResult.allowed();
        }

        // 1. 月度 Token 配额
        String yearMonth = currentYearMonth();
        TenantUsage usage = usageStore.getUsage(tenantId, yearMonth);
        if (usage.getUsedTokens() >= quota.getMaxTokensPerMonth()) {
            return TenantCheckResult.denied("Token monthly quota exceeded: " +
                    usage.getUsedTokens() + "/" + quota.getMaxTokensPerMonth());
        }

        // 2. QPS 限流
        if (!rateLimiter.tryAcquire(tenantId)) {
            return TenantCheckResult.denied("QPS rate limit exceeded");
        }

        return TenantCheckResult.allowed();
    }

    /**
     * 调用后记录用量。
     */
    public void recordUsage(String tenantId, AgentCallRecord record) {
        usageStore.recordUsage(tenantId, currentYearMonth(), record);
    }

    /**
     * 查询租户当月用量（便于监控与告警）。
     */
    public TenantUsage getCurrentMonthUsage(String tenantId) {
        return usageStore.getUsage(tenantId, currentYearMonth());
    }

    /**
     * 查询租户配额。
     */
    public TenantQuota getQuota(String tenantId) {
        return quotaStore.getQuota(tenantId);
    }

    private String currentYearMonth() {
        return LocalDate.now().format(YEAR_MONTH);
    }
}
