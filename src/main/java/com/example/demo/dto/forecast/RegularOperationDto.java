package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RegularOperationDto(
        String id,
        String title,
        String category,
        BigDecimal amount,
        String direction,
        LocalDate nextDate,
        /** weekly | biweekly | monthly */
        String cadence,
        String cadenceLabel,
        LocalDate lastDate,
        int occurrences,
        double confidence,
        boolean amountUnstable
) {
}
