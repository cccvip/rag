package com.core.agent.mcp;
import com.core.agent.mcp.infrastructure.DefaultTenantIsolation;
import com.core.agent.mcp.infrastructure.McpGateway;
import com.core.agent.mcp.infrastructure.RestProtocolConverter;
import com.core.agent.mcp.infrastructure.StaticServiceResolver;
import com.core.agent.mcp.interfaces.McpProperties;
import com.core.agent.mcp.registry.McpToolRegistry;
import com.core.agent.shared.model.RiskLevel;
import com.core.agent.tool.domain.Tool;
import com.core.agent.tool.domain.ToolDefinition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP Gateway 核心功能测试。
 */
class McpGatewayTest {

    private HttpServer mockServer;
    private int mockPort;
    private McpGateway gateway;
    private McpToolRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        // 启动模拟业务服务
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockServer.createContext("/api/retrieve", exchange -> {
            String body = readBody(exchange);
            String response = "[doc-1001] result for " + body;
            sendResponse(exchange, 200, response);
        });
        mockServer.createContext("/api/restart", exchange -> {
            sendResponse(exchange, 200, "restarted");
        });
        mockServer.setExecutor(null);
        mockServer.start();
        mockPort = mockServer.getAddress().getPort();

        // 构建 MCP Gateway
        ToolDefinition retrieve = ToolDefinition.builder()
                .name("dense_retrieve")
                .description("向量检索")
                .service("rag-service")
                .path("/api/retrieve")
                .method("POST")
                .riskLevel(RiskLevel.LOW)
                .scene("rag")
                .build();

        ToolDefinition restart = ToolDefinition.builder()
                .name("service_restart")
                .description("重启服务")
                .service("ops-service")
                .path("/api/restart")
                .method("POST")
                .riskLevel(RiskLevel.HIGH)
                .scene("ops")
                .build();

        McpProperties properties = McpProperties.builder()
                .tools(List.of(retrieve, restart))
                .services(Map.of(
                        "rag-service", "http://localhost:" + mockPort,
                        "ops-service", "http://localhost:" + mockPort
                ))
                .timeoutMs(5000)
                .build();

        registry = new McpToolRegistry(properties, new DefaultTenantIsolation());
        StaticServiceResolver resolver = new StaticServiceResolver(properties);
        gateway = new McpGateway(registry, resolver, new RestProtocolConverter(),
                new DefaultTenantIsolation(), properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    void shouldListToolsByScene() {
        List<ToolDefinition> ragTools = gateway.listTools("tenant-A", "rag");
        assertEquals(1, ragTools.size());
        assertEquals("dense_retrieve", ragTools.get(0).getName());

        List<ToolDefinition> opsTools = gateway.listTools("tenant-A", "ops");
        assertEquals(1, opsTools.size());
        assertEquals("service_restart", opsTools.get(0).getName());
    }

    @Test
    void shouldCallRemoteTool() {
        var result = gateway.call("dense_retrieve", "{\"query\": \"chemical spill\"}", "tenant-A", "rag");
        assertTrue(result.isSuccess(), result.getError());
        assertTrue(result.getData().contains("[doc-1001]"));
        assertTrue(result.getDurationMs() >= 0);
    }

    @Test
    void shouldReturnErrorWhenToolNotFound() {
        var result = gateway.call("unknown_tool", "{}", "tenant-A", "rag");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Tool not found"));
    }

    @Test
    void shouldFilterToolsByTenantBlacklist() {
        DefaultTenantIsolation isolation = new DefaultTenantIsolation();
        isolation.denyTool("tenant-B", "dense_retrieve");

        McpToolRegistry registryWithIsolation = new McpToolRegistry();
        registryWithIsolation.register(ToolDefinition.builder()
                .name("dense_retrieve")
                .description("向量检索")
                .service("rag-service")
                .path("/api/retrieve")
                .riskLevel(RiskLevel.LOW)
                .scene("rag")
                .build());

        McpGateway gatewayWithIsolation = new McpGateway(
                registryWithIsolation,
                new StaticServiceResolver(Map.of("rag-service", "http://localhost:" + mockPort)),
                new RestProtocolConverter(),
                isolation,
                McpProperties.builder().timeoutMs(5000).build(),
                new ObjectMapper()
        );

        var tenantATools = gatewayWithIsolation.listTools("tenant-A", "rag");
        assertEquals(1, tenantATools.size());
        assertEquals("dense_retrieve", tenantATools.get(0).getName());
        assertTrue(gatewayWithIsolation.listTools("tenant-B", "rag").isEmpty());
    }

    private String readBody(com.sun.net.httpserver.HttpExchange exchange) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "";
        }
    }

    private void sendResponse(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
