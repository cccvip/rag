package com.core.agent;

/**
 * 单次工具调用记录。
 */
public class ToolCallRecord {

    private final String toolName;
    private final String input;
    private final String output;
    private final boolean success;
    private final long latencyMs;
    private final boolean blocked;

    public ToolCallRecord(String toolName, String input, String output,
                          boolean success, long latencyMs, boolean blocked) {
        this.toolName = toolName;
        this.input = input;
        this.output = output;
        this.success = success;
        this.latencyMs = latencyMs;
        this.blocked = blocked;
    }

    public String getToolName() {
        return toolName;
    }

    public String getInput() {
        return input;
    }

    public String getOutput() {
        return output;
    }

    public boolean isSuccess() {
        return success;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public boolean isBlocked() {
        return blocked;
    }
}
