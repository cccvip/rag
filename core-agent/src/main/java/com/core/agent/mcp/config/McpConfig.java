package com.core.agent.mcp.config;
import com.core.agent.mcp.application.ProtocolConverter;
import com.core.agent.mcp.application.ServiceResolver;
import com.core.agent.mcp.application.TenantIsolation;
import com.core.agent.mcp.infrastructure.DefaultTenantIsolation;
import com.core.agent.mcp.infrastructure.McpGateway;
import com.core.agent.mcp.infrastructure.RestProtocolConverter;
import com.core.agent.mcp.infrastructure.StaticServiceResolver;
import com.core.agent.mcp.interfaces.McpProperties;
import com.core.agent.mcp.registry.McpToolRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Gateway 自动配置类。
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfig {

    @Bean
    public McpToolRegistry mcpToolRegistry(McpProperties properties, TenantIsolation tenantIsolation) {
        return new McpToolRegistry(properties, tenantIsolation);
    }

    @Bean
    public ServiceResolver serviceResolver(McpProperties properties) {
        return new StaticServiceResolver(properties);
    }

    @Bean
    public ProtocolConverter protocolConverter() {
        return new RestProtocolConverter();
    }

    @Bean
    public TenantIsolation tenantIsolation() {
        return new DefaultTenantIsolation();
    }

    @Bean
    public McpGateway mcpGateway(McpToolRegistry toolRegistry,
                                 ServiceResolver serviceResolver,
                                 ProtocolConverter protocolConverter,
                                 TenantIsolation tenantIsolation,
                                 McpProperties properties,
                                 ObjectMapper objectMapper) {
        return new McpGateway(toolRegistry, serviceResolver, protocolConverter,
                tenantIsolation, properties, objectMapper);
    }
}
