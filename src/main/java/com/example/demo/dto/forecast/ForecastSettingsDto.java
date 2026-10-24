package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ForecastSettingsDto(
        String companyInn,
        BigDecimal minBalance,
        Integer salaryDay,
        String cautionLevel,
        Object notifyRules,
        LocalDateTime updatedAt
) {
    public record UpdateRequest(
            BigDecimal minBalance,
            Integer salaryDay,
            String cautionLevel,
            Object notifyRules
    ) {
    }
}
