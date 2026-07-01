package com.core.agent.shared.exception;

/**
 * MCP Gateway 统一异常。
 */
public class McpException extends RuntimeException {

    private final String code;

    public McpException(String code, String message) {
        super(message);
        this.code = code;
    }

    public McpException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
