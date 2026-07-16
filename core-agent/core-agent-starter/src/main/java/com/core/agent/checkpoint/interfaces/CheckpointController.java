package com.core.agent.checkpoint.interfaces;

import com.core.agent.agent.graph.domain.Checkpoint;
import com.core.agent.checkpoint.application.CheckpointService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Checkpoint 人工审批接口。
 */
@RestController
@RequestMapping("/checkpoints")
public class CheckpointController {

    private final CheckpointService checkpointService;

    public CheckpointController(CheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<Checkpoint> getCheckpoint(@PathVariable String token) {
        Optional<Checkpoint> checkpoint = checkpointService.find(token);
        return checkpoint.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{token}/approve")
    public ResponseEntity<Checkpoint> approve(@PathVariable String token,
                                               @RequestBody(required = false) Map<String, String> body) {
        String comment = body != null ? body.getOrDefault("comment", "") : "";
        return ResponseEntity.ok(checkpointService.approve(token, comment));
    }

    @PostMapping("/{token}/reject")
    public ResponseEntity<Checkpoint> reject(@PathVariable String token,
                                              @RequestBody(required = false) Map<String, String> body) {
        String comment = body != null ? body.getOrDefault("comment", "") : "";
        return ResponseEntity.ok(checkpointService.reject(token, comment));
    }
}
