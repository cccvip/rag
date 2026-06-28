package com.core.agent.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantCtrlTest {

    private InMemoryQuotaStore quotaStore;
    private InMemoryUsageStore usageStore;
    private TenantCtrl tenantCtrl;

    @BeforeEach
    void setUp() {
        quotaStore = new InMemoryQuotaStore();
        usageStore = new InMemoryUsageStore();
        RateLimiter rateLimiter = new InMemoryTokenBucketRateLimiter(quotaStore);
        tenantCtrl = new TenantCtrl(quotaStore, usageStore, rateLimiter);
    }

    @Test
    void shouldAllowWhenQuotaAndQpsAreAvailable() {
        quotaStore.saveQuota(new TenantQuota("tenant-A", "PRO", 1000, 10, 500));

        TenantCheckResult result = tenantCtrl.check("tenant-A");

        assertTrue(result.isAllowed(), result.getReason());
    }

    @Test
    void shouldDenyWhenTokenQuotaExceeded() {
        quotaStore.saveQuota(new TenantQuota("tenant-B", "FREE", 100, 10, 500));
        tenantCtrl.recordUsage("tenant-B", new AgentCallRecord("s-1", 100, 200, 1));

        TenantCheckResult result = tenantCtrl.check("tenant-B");

        assertFalse(result.isAllowed());
        assertTrue(result.getReason().contains("Token monthly quota exceeded"));
    }

    @Test
    void shouldDenyWhenQpsExceeded() {
        // 容量为 1 的桶，连续请求第二次应被限流（不考虑 refill）
        quotaStore.saveQuota(new TenantQuota("tenant-C", "PRO", 10000, 1, 500));

        assertTrue(tenantCtrl.check("tenant-C").isAllowed());
        TenantCheckResult result = tenantCtrl.check("tenant-C");

        assertFalse(result.isAllowed());
        assertTrue(result.getReason().contains("QPS rate limit exceeded"));
    }

    @Test
    void shouldRecordUsage() {
        quotaStore.saveQuota(new TenantQuota("tenant-D", "PRO", 1000, 10, 500));

        tenantCtrl.recordUsage("tenant-D", new AgentCallRecord("s-1", 150, 300, 2));
        tenantCtrl.recordUsage("tenant-D", new AgentCallRecord("s-2", 50, 100, 1));

        TenantUsage usage = tenantCtrl.getCurrentMonthUsage("tenant-D");
        assertEquals(200, usage.getUsedTokens());
        assertEquals(2, usage.getCallCount());
        assertEquals(3, usage.getToolCallCount());
        assertEquals(400, usage.getTotalLatencyMs());
    }

    @Test
    void shouldAllowUnconfiguredTenantByDefault() {
        TenantCheckResult result = tenantCtrl.check("tenant-unknown");

        assertTrue(result.isAllowed());
    }

    @Test
    void shouldIsolateUsageByMonth() {
        // 当前月份配额已满，但其他月份不影响当前检查
        quotaStore.saveQuota(new TenantQuota("tenant-E", "FREE", 100, 10, 500));
        usageStore.recordUsage("tenant-E", "2026-01",
                new AgentCallRecord("s-1", 99999, 0, 0));

        TenantCheckResult result = tenantCtrl.check("tenant-E");

        assertTrue(result.isAllowed(), "其他月份用量不应影响当月配额");
    }
}
