package com.core.agent.infopricing.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 价格异常跳变点。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyPoint {

    /** 跳变时间点 */
    private Instant timestamp;

    /** 跳变前价格 */
    private BigDecimal priceBefore;

    /** 跳变后价格 */
    private BigDecimal priceAfter;

    /** 绝对变化 */
    private BigDecimal change;

    /** 百分比变化 */
    private BigDecimal changePercent;
}
