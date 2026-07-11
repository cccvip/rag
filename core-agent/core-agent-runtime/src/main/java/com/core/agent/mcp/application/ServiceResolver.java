package com.core.agent.mcp.application;
import com.core.agent.shared.exception.McpException;


import java.net.URI;
import java.net.URL;

/**
 * 服务地址解析器。
 *
 * <p>负责把工具定义中的 {@code service} 名称解析为可调用的实际 URL。
 * 默认提供静态配置实现，生产环境可替换为 Nacos / Consul / Eureka 实现。</p>
 */
public interface ServiceResolver {

    /**
     * 解析服务地址。
     *
     * @param serviceName 服务名，如 ops-service
     * @return 服务基础 URL，如 http://ops-service:8080
     * @throws McpException 服务未找到或无法解析时抛出
     */
    URL resolve(String serviceName);

    /**
     * 判断指定服务是否已注册。
     */
    default boolean isAvailable(String serviceName) {
        try {
            resolve(serviceName);
            return true;
        } catch (McpException e) {
            return false;
        }
    }
}
