package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ForecastDayResponse(
        LocalDate date,
        BigDecimal startBalance,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal endBalance,
        /** Statistical (non-scheduled) part of the day. */
        BigDecimal otherIncomeForecast,
        BigDecimal otherExpenseForecast,
        BigDecimal negativeEndBalance,
        BigDecimal confidenceLow,
        BigDecimal confidenceHigh,
        List<DayEvent> events,
        Long scenarioId
) {
    public record DayEvent(String title, String source, BigDecimal amount, String direction,
                           String category, String basis) {
    }
}
