package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Fact is real. The plan side (forecast made at the start of the period) is not stored yet,
 * so {@code planAvailable = false} and plan fields are null; no numbers are invented.
 */
public record PlanFactResponse(
        LocalDate from,
        LocalDate to,
        BigDecimal factBalance,
        BigDecimal planBalance,
        BigDecimal deviation,
        List<DayPoint> points,
        List<MissedItem> missedOnTime,
        boolean planAvailable
) {
    public record DayPoint(LocalDate date, BigDecimal fact, BigDecimal plan) {
    }

    public record MissedItem(String title, LocalDate expectedDate, int overdueDays, BigDecimal amount,
                             String direction) {
    }
}
