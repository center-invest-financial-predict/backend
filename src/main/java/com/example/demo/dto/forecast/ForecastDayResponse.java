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
        BigDecimal otherIncomeForecast,
        BigDecimal negativeEndBalance,
        List<DayEvent> events,
        boolean stub
) {
    public record DayEvent(String title, String source, BigDecimal amount, String direction) {
    }
}
