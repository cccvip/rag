package com.core.agent.infopricing;

import com.core.agent.infopricing.application.InfoPricingService;
import com.core.agent.infopricing.domain.PricingTimeline;
import com.core.agent.infopricing.interfaces.AnalyzeRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.ai.openai.api-key=test-key",
        "spring.ai.openai.base-url=https://api.deepseek.com",
        "spring.ai.openai.chat.options.model=deepseek-chat",
        "info-pricing.mock-enabled=true",
        "info-pricing.attribution-mock-enabled=true",
        "info-pricing.news-search-mock-enabled=true"
})
class InfoPricingServiceTest {

    @Autowired
    private InfoPricingService infoPricingService;

    @Test
    void shouldAnalyzeWithMockData() {
        AnalyzeRequest request = new AnalyzeRequest();
        request.setMarketName("Mock Match");
        request.setMarketId("mock-slug");

        PricingTimeline timeline = infoPricingService.analyze(request);

        assertThat(timeline).isNotNull();
        assertThat(timeline.getMarketName()).isEqualTo("Mock Match");
        assertThat(timeline.getMarketData()).isNotEmpty();
        assertThat(timeline.getAnomalies()).isNotEmpty();
        assertThat(timeline.getAttributions()).isNotEmpty();
        assertThat(timeline.getReport()).isNotBlank();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "POLYMARKET_REAL_API", matches = "true")
    void shouldAnalyzeRealPolymarketData() {
        // 使用真实 Polymarket 市场：西班牙是否会赢得 2026 世界杯
        // 需要网络连接；设置环境变量 POLYMARKET_REAL_API=true 启用
        AnalyzeRequest request = new AnalyzeRequest();
        request.setMarketName("Will Spain win the 2026 FIFA World Cup?");
        request.setMarketId("will-spain-win-the-2026-fifa-world-cup-963");

        PricingTimeline timeline = infoPricingService.analyze(request);

        assertThat(timeline).isNotNull();
        assertThat(timeline.getMarketName()).isEqualTo("Will Spain win the 2026 FIFA World Cup?");
        assertThat(timeline.getMarketData()).isNotEmpty();
        assertThat(timeline.getReport()).isNotBlank();

        System.out.println("=== Real Polymarket Report ===");
        System.out.println("Data points: " + timeline.getMarketData().size());
        System.out.println("Anomalies: " + timeline.getAnomalies().size());
        System.out.println(timeline.getReport());
    }
}
