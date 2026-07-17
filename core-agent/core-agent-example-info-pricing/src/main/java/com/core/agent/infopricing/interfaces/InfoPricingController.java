package com.core.agent.infopricing.interfaces;

import com.core.agent.infopricing.application.InfoPricingService;
import com.core.agent.infopricing.domain.PricingTimeline;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 信息定价分析 REST 入口。
 */
@RestController
@RequestMapping("/api/info-pricing")
public class InfoPricingController {

    private final InfoPricingService infoPricingService;

    public InfoPricingController(InfoPricingService infoPricingService) {
        this.infoPricingService = infoPricingService;
    }

    @PostMapping("/analyze")
    public PricingTimeline analyze(@Valid @RequestBody AnalyzeRequest request) {
        return infoPricingService.analyze(request);
    }
}
