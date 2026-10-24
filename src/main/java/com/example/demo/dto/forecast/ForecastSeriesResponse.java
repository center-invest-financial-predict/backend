package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ForecastSeriesResponse(
        String companyInn,
        int horizonDays,
        Long scenarioId,
        LocalDateTime calculatedAt,
        List<DayPoint> points,
        boolean stub
) {
    public record DayPoint(
            LocalDate date,
            BigDecimal baseBalance,
            BigDecimal negativeBalance,
            BigDecimal income,
            BigDecimal expense,
            BigDecimal confidenceLow,
            BigDecimal confidenceHigh
    ) {
    }
}
