package com.core.agent.infopricing.interfaces;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 信息定价分析请求。
 */
@Data
public class AnalyzeRequest {

    /** 市场/比赛名称 */
    @NotBlank
    private String marketName;

    /** Polymarket 市场 ID 或 slug */
    @NotBlank
    private String marketId;
}
