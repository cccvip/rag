package com.core.agent.tenant;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版令牌桶限流器 — 按租户隔离，支持突发流量。
 *
 * 每个租户独立一个桶，容量为 maxQps，每秒 refill maxQps 个令牌。
 * 生产环境可替换为基于 Redis 的分布式限流器。
 */
public class InMemoryTokenBucketRateLimiter implements RateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final QuotaStore quotaStore;

    public InMemoryTokenBucketRateLimiter(QuotaStore quotaStore) {
        this.quotaStore = quotaStore;
    }

    @Override
    public boolean tryAcquire(String tenantId) {
        TenantQuota quota = quotaStore.getQuota(tenantId);
        if (quota == null) {
            // 未配置配额时默认放行，便于原型演示；生产可改为拒绝
            return true;
        }
        int maxQps = quota.getMaxQps();
        if (maxQps <= 0) {
            return true;
        }

        Bucket bucket = buckets.computeIfAbsent(tenantId, k -> new Bucket(maxQps));
        return bucket.tryAcquire(maxQps);
    }

    /**
     * 令牌桶状态 — 非线程安全，外部通过 synchronized 保护。
     */
    private static class Bucket {
        private final double capacity;
        private double tokens;
        private long lastRefillMs;

        Bucket(int capacity) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillMs = System.currentTimeMillis();
        }

        synchronized boolean tryAcquire(int maxQps) {
            refill(maxQps);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill(int maxQps) {
            long now = System.currentTimeMillis();
            double elapsedSeconds = (now - lastRefillMs) / 1000.0;
            if (elapsedSeconds > 0) {
                tokens = Math.min(capacity, tokens + elapsedSeconds * maxQps);
                lastRefillMs = now;
            }
        }
    }
}
