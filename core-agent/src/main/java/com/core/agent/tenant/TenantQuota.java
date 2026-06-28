package com.core.agent.tenant;

import java.time.Instant;

/**
 * 租户配额 — 按套餐定义月度 Token 上限、QPS 上限等。
 *
 * 生产环境通常从数据库或配置中心加载；本工程先用内存版存储演示。
 */
public class TenantQuota {

    private final String tenantId;
    private final String planType;
    private final long maxTokensPerMonth;
    private final int maxQps;
    private final long maxTokensPerRequest;
    private final Instant createdAt;
    private final Instant updatedAt;

    public TenantQuota(String tenantId, String planType,
                       long maxTokensPerMonth, int maxQps,
                       long maxTokensPerRequest) {
        this(tenantId, planType, maxTokensPerMonth, maxQps, maxTokensPerRequest,
                Instant.now(), Instant.now());
    }

    public TenantQuota(String tenantId, String planType,
                       long maxTokensPerMonth, int maxQps,
                       long maxTokensPerRequest,
                       Instant createdAt, Instant updatedAt) {
        this.tenantId = tenantId;
        this.planType = planType;
        this.maxTokensPerMonth = maxTokensPerMonth;
        this.maxQps = maxQps;
        this.maxTokensPerRequest = maxTokensPerRequest;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getPlanType() {
        return planType;
    }

    public long getMaxTokensPerMonth() {
        return maxTokensPerMonth;
    }

    public int getMaxQps() {
        return maxQps;
    }

    public long getMaxTokensPerRequest() {
        return maxTokensPerRequest;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "TenantQuota{" +
                "tenantId='" + tenantId + '\'' +
                ", planType='" + planType + '\'' +
                ", maxTokensPerMonth=" + maxTokensPerMonth +
                ", maxQps=" + maxQps +
                ", maxTokensPerRequest=" + maxTokensPerRequest +
                '}';
    }
}
