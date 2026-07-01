package com.core.agent.mcp;
import com.core.agent.bootstrap.AgentApp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP Gateway Spring Boot REST 端点测试。
 */
@SpringBootTest(classes = AgentApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpControllerTest {

    private static HttpServer mockBizService;
    private static int mockPort;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void startMockService() throws IOException {
        mockBizService = HttpServer.create(new InetSocketAddress(0), 0);
        mockBizService.createContext("/api/retrieve/dense", exchange -> {
            String body = readBody(exchange);
            sendResponse(exchange, 200, "[doc-1001] retrieved for " + body);
        });
        mockBizService.createContext("/api/rerank", exchange -> {
            sendResponse(exchange, 200, "[doc-1001](0.95); [doc-1002](0.72)");
        });
        mockBizService.setExecutor(null);
        mockBizService.start();
        mockPort = mockBizService.getAddress().getPort();
    }

    @AfterAll
    static void stopMockService() {
        if (mockBizService != null) {
            mockBizService.stop(0);
        }
    }

    @DynamicPropertySource
    static void configureMcpProperties(DynamicPropertyRegistry registry) {
        registry.add("mcp.gateway.services.rag-service", () -> "http://localhost:" + mockPort);
        registry.add("mcp.gateway.tools[0].name", () -> "dense_retrieve");
        registry.add("mcp.gateway.tools[0].description", () -> "向量检索");
        registry.add("mcp.gateway.tools[0].service", () -> "rag-service");
        registry.add("mcp.gateway.tools[0].path", () -> "/api/retrieve/dense");
        registry.add("mcp.gateway.tools[0].method", () -> "POST");
        registry.add("mcp.gateway.tools[0].risk-level", () -> "LOW");
        registry.add("mcp.gateway.tools[0].scene", () -> "rag");
        registry.add("mcp.gateway.tools[1].name", () -> "rerank");
        registry.add("mcp.gateway.tools[1].description", () -> "精排");
        registry.add("mcp.gateway.tools[1].service", () -> "rag-service");
        registry.add("mcp.gateway.tools[1].path", () -> "/api/rerank");
        registry.add("mcp.gateway.tools[1].method", () -> "POST");
        registry.add("mcp.gateway.tools[1].risk-level", () -> "LOW");
        registry.add("mcp.gateway.tools[1].scene", () -> "rag");
    }

    @Test
    void shouldListToolsByScene() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", "tenant-A");
        headers.set("X-Scene", "rag");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/mcp/tools/list", request, JsonNode.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = response.getBody();
        assertNotNull(body);
        assertTrue(body.has("tools"));
        assertEquals(2, body.get("tools").size());
    }

    @Test
    void shouldCallTool() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "name", "dense_retrieve",
                "params", Map.of("query", "chemical spill")
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", "tenant-A");
        headers.set("X-Scene", "rag");
        headers.set("Content-Type", "application/json");
        HttpEntity<String> request = new HttpEntity<>(payload, headers);

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/mcp/tools/call", request, JsonNode.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = response.getBody();
        assertNotNull(body);
        assertTrue(body.get("success").asBoolean(), body.toString());
        assertTrue(body.get("data").asText().contains("[doc-1001]"));
    }

    @Test
    void shouldReturnEmptyForUnknownScene() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", "tenant-A");
        headers.set("X-Scene", "ops");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/mcp/tools/list", request, JsonNode.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().get("tools").size());
    }

    private static String readBody(com.sun.net.httpserver.HttpExchange exchange) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "";
        }
    }

    private static void sendResponse(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
