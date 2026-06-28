package com.core.agent.tenant;

/**
 * 租户调用前检查结果。
 */
public class TenantCheckResult {

    private final boolean allowed;
    private final String reason;

    private TenantCheckResult(boolean allowed, String reason) {
        this.allowed = allowed;
        this.reason = reason;
    }

    public static TenantCheckResult allowed() {
        return new TenantCheckResult(true, null);
    }

    public static TenantCheckResult denied(String reason) {
        return new TenantCheckResult(false, reason);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "TenantCheckResult{" +
                "allowed=" + allowed +
                ", reason='" + reason + '\'' +
                '}';
    }
}
