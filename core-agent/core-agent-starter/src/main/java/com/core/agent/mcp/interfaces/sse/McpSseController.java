package com.core.agent.mcp.interfaces.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 标准 MCP SSE 服务端点。
 *
 * <p>符合 Anthropic MCP 协议规范：</p>
 * <ol>
 *     <li>客户端 GET /mcp/sse 建立 SSE 连接</li>
 *     <li>服务端发送 endpoint 事件，告知消息提交地址</li>
 *     <li>客户端 POST /mcp/message?sessionId=xxx 提交 JSON-RPC 请求</li>
 *     <li>服务端通过 SSE 返回 JSON-RPC 响应</li>
 * </ol>
 */
@RestController
@RequestMapping("/mcp")
public class McpSseController {

    private static final Logger log = LoggerFactory.getLogger(McpSseController.class);

    private final McpSseSessionManager sessionManager;
    private final McpMessageHandler messageHandler;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(1);

    public McpSseController(McpSseSessionManager sessionManager, McpMessageHandler messageHandler) {
        this.sessionManager = sessionManager;
        this.messageHandler = messageHandler;
    }

    /**
     * 建立 SSE 连接。
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sse() {
        McpSseSession session = sessionManager.createSession();
        SseEmitter emitter = new SseEmitter(0L);

        // 发送 endpoint 事件，告诉客户端消息提交地址
        try {
            emitter.send(SseEmitter.event()
                    .name("endpoint")
                    .data("/mcp/message?sessionId=" + session.getSessionId()));
        } catch (IOException e) {
            log.error("Failed to send endpoint event", e);
            emitter.completeWithError(e);
            return emitter;
        }

        // 订阅 reactor sink，把后端消息转发到 SSE
        session.getSink().asFlux()
                .doOnNext(message -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("message")
                                .data(message));
                    } catch (IOException e) {
                        log.error("Failed to send SSE message", e);
                        emitter.completeWithError(e);
                    }
                })
                .doOnError(error -> {
                    log.error("SSE stream error", error);
                    emitter.completeWithError(error);
                })
                .doOnComplete(emitter::complete)
                .subscribe();

        // 心跳保活
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                sessionManager.removeSession(session.getSessionId());
            }
        }, 30, 30, TimeUnit.SECONDS);

        emitter.onCompletion(() -> sessionManager.removeSession(session.getSessionId()));
        emitter.onTimeout(() -> sessionManager.removeSession(session.getSessionId()));
        emitter.onError((e) -> sessionManager.removeSession(session.getSessionId()));

        return emitter;
    }

    /**
     * 接收客户端 JSON-RPC 消息。
     */
    @PostMapping("/message")
    public void message(@RequestParam("sessionId") String sessionId,
                        @RequestBody String body) {
        McpSseSession session = sessionManager.getSession(sessionId);
        if (session == null) {
            log.warn("MCP session not found: {}", sessionId);
            return;
        }

        String response = messageHandler.handle(sessionId, body);
        Sinks.EmitResult result = session.getSink().tryEmitNext(response);
        if (result.isFailure()) {
            log.error("Failed to emit MCP response to session {}", sessionId);
        }
    }
}
