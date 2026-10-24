package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record WeeklyCashflowDto(
        /** past | forecast */
        String period,
        LocalDate dataAsOf,
        List<WeekBucket> weeks
) {
    public record WeekBucket(
            LocalDate weekStart,
            LocalDate weekEnd,
            BigDecimal income,
            BigDecimal expense
    ) {
    }
}
