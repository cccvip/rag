package com.core.agent.multiagent.infrastructure;

import com.core.agent.multiagent.domain.A2aGateway;
import com.core.agent.multiagent.domain.A2aTask;
import com.core.agent.multiagent.infrastructure.a2a.A2aSendTaskRequest;
import com.core.agent.multiagent.infrastructure.a2a.A2aTaskResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 基于 JDK HttpClient 的 A2A Gateway 实现。
 */
public class HttpA2aGateway implements A2aGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpA2aGateway.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpA2aGateway(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public HttpA2aGateway(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), objectMapper);
    }

    @Override
    public A2aTask sendTask(String agentEndpoint, String query) {
        try {
            String body = objectMapper.writeValueAsString(new A2aSendTaskRequest(query));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(agentEndpoint + "/tasks"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            A2aTaskResponse taskResponse = objectMapper.readValue(response.body(), A2aTaskResponse.class);
            return toDomain(taskResponse);
        } catch (Exception e) {
            log.error("Failed to send A2A task to {}", agentEndpoint, e);
            throw new IllegalStateException("A2A send task failed", e);
        }
    }

    @Override
    public A2aTask getTaskStatus(String agentEndpoint, String taskId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(agentEndpoint + "/tasks/" + taskId))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            A2aTaskResponse taskResponse = objectMapper.readValue(response.body(), A2aTaskResponse.class);
            return toDomain(taskResponse);
        } catch (Exception e) {
            log.error("Failed to get A2A task status from {} taskId={}", agentEndpoint, taskId, e);
            throw new IllegalStateException("A2A get task status failed", e);
        }
    }

    private A2aTask toDomain(A2aTaskResponse response) {
        if (response == null) {
            return null;
        }
        return A2aTask.builder()
                .taskId(response.getTaskId())
                .query(response.getQuery())
                .status(response.getStatus())
                .answer(response.getAnswer())
                .artifacts(response.getArtifacts())
                .build();
    }
}
