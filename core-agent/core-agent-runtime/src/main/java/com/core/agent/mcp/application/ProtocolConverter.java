package com.core.agent.mcp.application;
import com.core.agent.tool.domain.ToolCallRequest;
import com.core.agent.tool.domain.ToolDefinition;


import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 协议转换器。
 *
 * <p>负责 MCP 工具调用协议与后端业务服务 REST 协议之间的转换。
 * 例如把 {@link ToolCallRequest#params} 映射为 HTTP 请求体或查询参数。</p>
 */
public interface ProtocolConverter {

    /**
     * 构造调用后端服务的 HTTP URL。
     *
     * @param baseUrl    服务基础地址
     * @param tool       工具定义
     * @param request    MCP 工具调用请求
     * @return 完整请求 URL
     */
    URL buildRequestUrl(URL baseUrl, ToolDefinition tool, ToolCallRequest request);

    /**
     * 准备 HTTP 连接：设置方法、请求头、请求体等。
     *
     * @param connection JDK HttpURLConnection
     * @param tool       工具定义
     * @param request    MCP 工具调用请求
     */
    void prepareConnection(HttpURLConnection connection, ToolDefinition tool, ToolCallRequest request);

    /**
     * 把后端服务响应解析为字符串。
     *
     * @param connection 已执行完毕的连接
     * @return 响应体字符串
     * @throws Exception 读取失败时抛出
     */
    String parseResponse(HttpURLConnection connection) throws Exception;
}
