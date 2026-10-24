package com.example.demo.dto.forecast;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * Each recommendation is verified by recalculating the forecast with the proposed action:
 * {@code beforeGap} / {@code afterGap} are the exposure (shortfall) before and after.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecommendationDto(
        String id,
        String title,
        String description,
        BigDecimal impactAmount,
        BigDecimal beforeGap,
        BigDecimal afterGap,
        String actionLabel,
        /** Scenario conditions that can be sent to /scenarios/preview or saved as a scenario. */
        List<java.util.Map<String, Object>> conditions
) {
}
