package com.core.agent.multiagent.interfaces;

import com.core.agent.multiagent.application.AgentRegistry;
import com.core.agent.multiagent.domain.AgentCard;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/agents")
public class AgentRegistryController {

    private final AgentRegistry agentRegistry;

    public AgentRegistryController(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    @PostMapping
    public ResponseEntity<Void> register(@RequestBody AgentCard card) {
        agentRegistry.register(card);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<List<AgentCard>> list(@RequestParam(required = false) String capability) {
        if (capability != null && !capability.isBlank()) {
            return ResponseEntity.ok(agentRegistry.findByCapability(capability));
        }
        return ResponseEntity.ok(agentRegistry.list());
    }

    @GetMapping("/{agentId}")
    public ResponseEntity<AgentCard> get(@PathVariable String agentId) {
        return agentRegistry.find(agentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
