package com.core.agent.mcp.infrastructure;
import com.core.agent.mcp.application.ServiceResolver;
import com.core.agent.mcp.interfaces.McpProperties;
import com.core.agent.shared.exception.McpException;


import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 静态服务地址解析器。
 *
 * <p>从 {@link McpProperties#getServices()} 中读取服务名到基础 URL 的映射。
 * 适用于开发测试或小型部署；生产环境可替换为 Nacos 实现。</p>
 */
public class StaticServiceResolver implements ServiceResolver {

    private final Map<String, String> serviceUrlMap;

    public StaticServiceResolver(Map<String, String> serviceUrlMap) {
        this.serviceUrlMap = serviceUrlMap == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(serviceUrlMap);
    }

    public StaticServiceResolver(McpProperties properties) {
        this(properties == null ? null : properties.getServices());
    }

    public void register(String serviceName, String baseUrl) {
        serviceUrlMap.put(serviceName, baseUrl);
    }

    @Override
    public URL resolve(String serviceName) {
        String baseUrl = serviceUrlMap.get(serviceName);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new McpException("SERVICE_NOT_FOUND",
                    "Service not found: " + serviceName + ", registered services: " + serviceUrlMap.keySet());
        }
        try {
            return new URL(baseUrl);
        } catch (MalformedURLException e) {
            throw new McpException("INVALID_SERVICE_URL",
                    "Invalid service URL for " + serviceName + ": " + baseUrl, e);
        }
    }
}
