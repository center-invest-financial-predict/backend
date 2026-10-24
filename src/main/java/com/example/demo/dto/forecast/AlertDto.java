package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AlertDto(
        String id,
        String severity,
        String title,
        String description,
        LocalDate date,
        BigDecimal amount,
        String actionLabel,
        String actionTarget
) {
}
