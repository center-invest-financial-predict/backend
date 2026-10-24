package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PlanFactResponse(
        LocalDate from,
        LocalDate to,
        BigDecimal factBalance,
        BigDecimal planBalance,
        BigDecimal deviation,
        List<DayPoint> points,
        List<MissedItem> missedOnTime,
        boolean stubPlan
) {
    public record DayPoint(LocalDate date, BigDecimal fact, BigDecimal plan) {
    }

    public record MissedItem(String title, LocalDate expectedDate, int overdueDays, BigDecimal amount) {
    }
}
