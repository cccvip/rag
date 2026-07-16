package com.core.agent.multiagent;

import com.core.agent.multiagent.application.AgentRegistry;
import com.core.agent.multiagent.domain.AgentCard;
import com.core.agent.multiagent.infrastructure.InMemoryAgentRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAgentRegistryTest {

    private final AgentRegistry registry = new InMemoryAgentRegistry();

    @Test
    void shouldRegisterAndFindAgent() {
        AgentCard card = AgentCard.builder()
                .agentId("weather-agent")
                .name("Weather Agent")
                .capabilities(List.of("weather"))
                .endpoint("http://localhost:8081")
                .build();

        registry.register(card);

        assertThat(registry.find("weather-agent")).isPresent();
        assertThat(registry.list()).hasSize(1);
        assertThat(registry.findByCapability("weather")).hasSize(1);
    }
}
