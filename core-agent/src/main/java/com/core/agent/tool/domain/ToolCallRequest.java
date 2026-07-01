package com.core.agent.tool.domain;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 工具调用请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallRequest {

    /** 要调用的工具名称。 */
    private String name;

    /** 工具输入参数。 */
    private JsonNode params;

    /** 租户 ID，用于租户隔离与权限校验。 */
    private String tenantId;

    /** 业务场景，用于按场景过滤工具。 */
    private String scene;

    /** 用户 ID，用于审计。 */
    private String userId;

    public String getScene() {
        return scene;
    }

    public String getTenantId() {
        return tenantId;
    }
}
