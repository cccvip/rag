package com.core.agent.infopricing;

import com.core.agent.infopricing.application.InfoPricingService;
import com.core.agent.infopricing.domain.PricingTimeline;
import com.core.agent.infopricing.interfaces.AnalyzeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.ai.openai.api-key=test-key",
        "spring.ai.openai.base-url=https://api.deepseek.com",
        "spring.ai.openai.chat.options.model=deepseek-chat",
        "info-pricing.attribution-mock-enabled=true"
})
class InfoPricingServiceTest {

    @Autowired
    private InfoPricingService infoPricingService;

    @Test
    void shouldAnalyzeMockMarketData() {
        AnalyzeRequest request = new AnalyzeRequest();
        request.setMarketName("England vs France - Third Place Match");
        request.setMarketId("england-france-third-place");

        PricingTimeline timeline = infoPricingService.analyze(request);

        assertThat(timeline).isNotNull();
        assertThat(timeline.getMarketName()).isEqualTo("England vs France - Third Place Match");
        assertThat(timeline.getMarketData()).isNotEmpty();
        assertThat(timeline.getAnomalies()).isNotEmpty();
        assertThat(timeline.getAttributions()).isNotEmpty();
        assertThat(timeline.getReport()).isNotBlank();

        System.out.println("=== Report ===");
        System.out.println(timeline.getReport());
    }
}
