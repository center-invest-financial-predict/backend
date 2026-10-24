package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.util.List;

public record CategoryCashflowDto(
        List<CategoryBucket> categories,
        boolean stubCategories
) {
    public record CategoryBucket(String category, BigDecimal amount, BigDecimal sharePct) {
    }
}
