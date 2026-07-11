package com.core.agent.tool.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 工具调用结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallResult {

    /** 是否调用成功。 */
    private boolean success;

    /** 工具返回的原始数据（JSON 字符串或纯文本）。 */
    private String data;

    /** 错误信息，success=false 时返回。 */
    private String error;

    /** 调用耗时（毫秒）。 */
    private long durationMs;

    public static ToolCallResult ok(String data, long durationMs) {
        return ToolCallResult.builder()
                .success(true)
                .data(data)
                .durationMs(durationMs)
                .build();
    }

    public static ToolCallResult fail(String error) {
        return ToolCallResult.builder()
                .success(false)
                .error(error)
                .build();
    }

    public static ToolCallResult fail(String error, long durationMs) {
        return ToolCallResult.builder()
                .success(false)
                .error(error)
                .durationMs(durationMs)
                .build();
    }
}
