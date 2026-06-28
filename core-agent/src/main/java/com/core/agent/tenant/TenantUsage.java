package com.core.agent.tenant;

import java.time.Instant;

/**
 * 租户月度用量 — 用于月度 Token 配额校验和成本追踪。
 */
public class TenantUsage {

    private final String tenantId;
    private final String yearMonth;
    private long usedTokens;
    private long callCount;
    private long toolCallCount;
    private long totalLatencyMs;
    private Instant lastUpdatedAt;

    public TenantUsage(String tenantId, String yearMonth) {
        this(tenantId, yearMonth, 0L, 0L, 0L, 0L, Instant.now());
    }

    public TenantUsage(String tenantId, String yearMonth,
                       long usedTokens, long callCount,
                       long toolCallCount, long totalLatencyMs,
                       Instant lastUpdatedAt) {
        this.tenantId = tenantId;
        this.yearMonth = yearMonth;
        this.usedTokens = usedTokens;
        this.callCount = callCount;
        this.toolCallCount = toolCallCount;
        this.totalLatencyMs = totalLatencyMs;
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getYearMonth() {
        return yearMonth;
    }

    public long getUsedTokens() {
        return usedTokens;
    }

    public long getCallCount() {
        return callCount;
    }

    public long getToolCallCount() {
        return toolCallCount;
    }

    public long getTotalLatencyMs() {
        return totalLatencyMs;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    /**
     * 累加一次调用产生的用量。
     */
    public synchronized void add(AgentCallRecord record) {
        this.usedTokens += record.getTokens();
        this.callCount += 1;
        this.toolCallCount += record.getToolCallCount();
        this.totalLatencyMs += record.getLatencyMs();
        this.lastUpdatedAt = Instant.now();
    }

    @Override
    public String toString() {
        return "TenantUsage{" +
                "tenantId='" + tenantId + '\'' +
                ", yearMonth='" + yearMonth + '\'' +
                ", usedTokens=" + usedTokens +
                ", callCount=" + callCount +
                ", toolCallCount=" + toolCallCount +
                ", totalLatencyMs=" + totalLatencyMs +
                '}';
    }
}
