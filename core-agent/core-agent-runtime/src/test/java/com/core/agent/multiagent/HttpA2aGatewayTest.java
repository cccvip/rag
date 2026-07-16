package com.core.agent.multiagent;

import com.core.agent.multiagent.domain.A2aTask;
import com.core.agent.multiagent.infrastructure.HttpA2aGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HttpA2aGatewayTest {

    private com.sun.net.httpserver.HttpServer server;
    private String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpA2aGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        gateway = new HttpA2aGateway(objectMapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldSendTask() throws Exception {
        server.createContext("/tasks", exchange -> {
            String response = objectMapper.writeValueAsString(java.util.Map.of(
                    "taskId", "task-1",
                    "query", "weather",
                    "status", "completed",
                    "answer", "sunny"
            ));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        A2aTask task = gateway.sendTask(baseUrl, "weather");

        assertThat(task.getTaskId()).isEqualTo("task-1");
        assertThat(task.getStatus()).isEqualTo("completed");
        assertThat(task.getAnswer()).isEqualTo("sunny");
    }
}
