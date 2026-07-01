package com.core.agent.guardrail.domain;
import com.core.agent.agent.domain.Agent;
import com.core.agent.shared.model.RiskLevel;
import com.core.agent.tool.domain.Tool;
import com.core.agent.tool.domain.ToolDefinition;


import java.util.ArrayList;
import java.util.List;

/**
 * 安全护栏：根据工具风险等级决定是否允许执行。
 *
 * 风险等级定义：
 * - LOW：只读、无副作用，可直接执行
 * - MEDIUM：有输出但影响可控，记录审计日志后执行
 * - HIGH：有实际副作用，需要人工确认
 * - CRITICAL：高危、不可逆，不开放给 Agent 自动调用
 */
public class GuardRail {

    private final List<String> auditLogs = new ArrayList<>();

    /**
     * 判断本地工具是否允许执行。
     *
     * @param tool 待执行的工具
     * @return true 表示允许执行，false 表示拦截
     */
    public boolean allow(Tool tool) {
        return allow(tool, "default-tenant", "default-user");
    }

    /**
     * 判断本地工具是否允许执行（带租户/用户上下文）。
     */
    public boolean allow(Tool tool, String tenantId, String userId) {
        RiskLevel riskLevel = tool.riskLevel();
        recordAudit(tool.name(), riskLevel, tenantId, userId);

        return switch (riskLevel) {
            case LOW, MEDIUM -> true;
            case HIGH -> requestHumanConfirmation(tool.name(), tenantId, userId);
            case CRITICAL -> {
                System.out.println("[GuardRail] Blocked CRITICAL tool '" + tool.name()
                        + "' for tenant=" + tenantId + ", user=" + userId);
                yield false;
            }
        };
    }

    /**
     * 判断 MCP Gateway 工具是否允许执行（带租户/用户上下文）。
     */
    public boolean allow(ToolDefinition tool, String tenantId, String userId) {
        RiskLevel riskLevel = tool.getRiskLevel();
        recordAudit(tool.getName(), riskLevel, tenantId, userId);

        return switch (riskLevel) {
            case LOW, MEDIUM -> true;
            case HIGH -> requestHumanConfirmation(tool.getName(), tenantId, userId);
            case CRITICAL -> {
                System.out.println("[GuardRail] Blocked CRITICAL tool '" + tool.getName()
                        + "' for tenant=" + tenantId + ", user=" + userId);
                yield false;
            }
        };
    }

    private void recordAudit(String toolName, RiskLevel riskLevel, String tenantId, String userId) {
        String log = String.format("[AUDIT] tenant=%s, user=%s, tool=%s, risk=%s",
                tenantId, userId, toolName, riskLevel);
        auditLogs.add(log);
        System.out.println(log);
    }

    /**
     * 高风险工具请求人工确认。
     * 在生产环境中，这里应该调用工单系统、发送审批消息或弹出确认界面。
     * 本 demo 中模拟为需要确认，默认不通过。
     */
    private boolean requestHumanConfirmation(String toolName, String tenantId, String userId) {
        System.out.println("[GuardRail] HIGH risk tool '" + toolName
                + "' requires human confirmation for tenant=" + tenantId
                + ", user=" + userId + ". Blocking in demo mode.");
        return false;
    }

    public List<String> getAuditLogs() {
        return new ArrayList<>(auditLogs);
    }
}
