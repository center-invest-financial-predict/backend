package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AlertDto(
        String id,
        /** GAP | RISK | BELOW_MIN | STRESS | OVERDUE | DATA */
        String type,
        /** critical | warning | info */
        String severity,
        String title,
        String description,
        LocalDate date,
        BigDecimal amount,
        String actionLabel,
        String actionTarget
) {
}
