package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The same forecast under several ready-made sets of conditions. Every preset is computed by the one forecast
 * engine; {@code impact} is the difference against the {@code base} preset. {@code gapProbability} is a fraction 0..1.
 */
public record ForecastPresetsResponse(
        String companyInn,
        int horizonDays,
        BigDecimal minBalanceThreshold,
        String cautionLevel,
        String activePreset,
        List<Preset> presets
) {
    public record Preset(
            String code,
            String name,
            String description,
            boolean active,
            boolean configured,
            List<Map<String, Object>> conditions,
            BigDecimal minBalance,
            LocalDate minBalanceDate,
            boolean belowThreshold,
            LocalDate gapDate,
            BigDecimal gapAmount,
            BigDecimal gapProbability,
            Impact impact
    ) {
    }

    /** Preset minus base: negative balance/amount deltas are worse; a positive probability delta is worse. */
    public record Impact(
            BigDecimal minBalanceDelta,
            BigDecimal gapAmountDelta,
            BigDecimal gapProbabilityDelta
    ) {
    }
}
