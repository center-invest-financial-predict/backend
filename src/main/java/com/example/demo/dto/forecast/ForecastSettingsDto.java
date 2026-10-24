package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code cautionLevel}: conservative | balanced | optimistic | own — selects the active forecast preset.
 * {@code presets}: {@code {"own": {"name": "...", "conditions": [{type: ...}]}}}, conditions as in scenarios.
 */
public record ForecastSettingsDto(
        String companyInn,
        BigDecimal minBalance,
        Integer salaryDay,
        String cautionLevel,
        Object notifyRules,
        Object presets,
        LocalDateTime updatedAt
) {
    public record UpdateRequest(
            BigDecimal minBalance,
            Integer salaryDay,
            String cautionLevel,
            Object notifyRules,
            Object presets
    ) {
    }
}
