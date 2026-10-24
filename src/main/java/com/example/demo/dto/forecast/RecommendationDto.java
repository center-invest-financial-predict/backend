package com.example.demo.dto.forecast;

import java.math.BigDecimal;

public record RecommendationDto(
        String id,
        String title,
        String description,
        BigDecimal impactAmount,
        BigDecimal beforeGap,
        BigDecimal afterGap,
        String actionLabel
) {
}
