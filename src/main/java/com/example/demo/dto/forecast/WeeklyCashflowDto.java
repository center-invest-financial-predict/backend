package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record WeeklyCashflowDto(
        List<WeekBucket> weeks,
        boolean fromOperations
) {
    public record WeekBucket(
            LocalDate weekStart,
            LocalDate weekEnd,
            BigDecimal income,
            BigDecimal expense
    ) {
    }
}
