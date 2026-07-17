package com.core.agent.infopricing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 信息定价分析示例应用入口。
 *
 * <p>仅扫描本模块包，避免引入 core-agent-starter/core-agent-runtime 中的大量基础设施 bean。</p>
 */
@SpringBootApplication(scanBasePackages = "com.core.agent.infopricing")
public class InfoPricingApp {

    public static void main(String[] args) {
        SpringApplication.run(InfoPricingApp.class, args);
    }
}
