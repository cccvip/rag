package com.core.agent.infopricing.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Polymarket 价格数据点。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketDataPoint {

    /** 时间点 */
    private Instant timestamp;

    /** 市场价格（概率 0~1） */
    private BigDecimal price;

    /** 交易量 */
    private BigDecimal volume;
}
