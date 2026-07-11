package com.core.agent.mcp.infrastructure;
import com.core.agent.mcp.application.ProtocolConverter;
import com.core.agent.tool.domain.ToolCallRequest;
import com.core.agent.tool.domain.ToolDefinition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * REST 协议转换器。
 *
 * <p>把 MCP 工具调用转换为对后端业务服务的 HTTP REST 调用。
 * 默认将请求参数作为 JSON body 发送，支持 GET 查询参数。</p>
 */
public class RestProtocolConverter implements ProtocolConverter {

    private final ObjectMapper objectMapper;

    public RestProtocolConverter() {
        this(new ObjectMapper());
    }

    public RestProtocolConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public URL buildRequestUrl(URL baseUrl, ToolDefinition tool, ToolCallRequest request) {
        try {
            String path = tool.getPath();
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            String query = "";
            if ("GET".equals(tool.getMethod()) && request.getParams() != null) {
                query = request.getParams().fields().hasNext()
                        ? "?" + buildQueryString(request.getParams())
                        : "";
            }
            return new URL(baseUrl.getProtocol(), baseUrl.getHost(), baseUrl.getPort(), path + query);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build request URL for tool " + tool.getName(), e);
        }
    }

    @Override
    public void prepareConnection(HttpURLConnection connection, ToolDefinition tool, ToolCallRequest request) {
        try {
            connection.setRequestMethod(tool.getMethod());
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-Tenant-Id", request.getTenantId());
            if (request.getUserId() != null) {
                connection.setRequestProperty("X-User-Id", request.getUserId());
            }
            connection.setDoInput(true);

            if ("POST".equals(tool.getMethod()) || "PUT".equals(tool.getMethod()) || "PATCH".equals(tool.getMethod())) {
                connection.setDoOutput(true);
                String body = request.getParams() == null ? "{}" : request.getParams().toString();
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to prepare connection for tool " + tool.getName(), e);
        }
    }

    @Override
    public String parseResponse(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        InputStreamReader reader;
        if (status >= 200 && status < 300) {
            reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8);
        } else {
            reader = new InputStreamReader(connection.getErrorStream() != null
                    ? connection.getErrorStream() : connection.getInputStream(), StandardCharsets.UTF_8);
        }
        try (BufferedReader br = new BufferedReader(reader)) {
            String body = br.lines().collect(Collectors.joining("\n"));
            if (status >= 200 && status < 300) {
                return body;
            }
            throw new RuntimeException("Backend returned HTTP " + status + ": " + body);
        }
    }

    private String buildQueryString(JsonNode params) {
        StringBuilder sb = new StringBuilder();
        params.fields().forEachRemaining(entry -> {
            if (!sb.isEmpty()) {
                sb.append("&");
            }
            sb.append(urlEncode(entry.getKey()))
              .append("=")
              .append(urlEncode(entry.getValue().asText()));
        });
        return sb.toString();
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
