package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CategoryCashflowDto(
        /** expense | income */
        String direction,
        LocalDate from,
        LocalDate to,
        BigDecimal total,
        List<CategoryBucket> categories
) {
    public record CategoryBucket(String category, BigDecimal amount, BigDecimal sharePct) {
    }
}
