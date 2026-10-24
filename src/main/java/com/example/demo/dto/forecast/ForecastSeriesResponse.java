package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ForecastSeriesResponse(
        String companyInn,
        int horizonDays,
        Long scenarioId,
        LocalDateTime calculatedAt,
        String runId,
        LocalDate dataAsOf,
        BigDecimal startBalance,
        List<DayPoint> points,
        /** Last actual end-of-day balances up to dataAsOf, for chart continuity. */
        List<ActualPoint> history,
        Summary summary,
        /** Applied scenario conditions (machine form, same as in saved scenarios). */
        List<Map<String, Object>> conditions,
        List<String> conditionsText,
        List<String> warnings,
        /** Who calculated the forecast: {@code builtin} or {@code ml-core}. */
        String source,
        String sourceNote
) {
    public record DayPoint(
            LocalDate date,
            BigDecimal baseBalance,
            /** Balance when negative, otherwise null (red zone of the chart). */
            BigDecimal negativeBalance,
            BigDecimal income,
            BigDecimal expense,
            BigDecimal confidenceLow,
            BigDecimal confidenceHigh
    ) {
    }

    public record ActualPoint(LocalDate date, BigDecimal balance) {
    }

    public record Summary(
            BigDecimal endBalance,
            BigDecimal minBalance,
            LocalDate minBalanceDate,
            LocalDate gapDate,
            BigDecimal gapAmount,
            LocalDate riskGapDate,
            BigDecimal riskGapAmount,
            BigDecimal gapProbability,
            BigDecimal totalIncome,
            BigDecimal totalExpense
    ) {
    }
}
