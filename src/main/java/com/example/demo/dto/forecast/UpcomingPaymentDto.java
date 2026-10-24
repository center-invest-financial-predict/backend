package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

/** An expected payment/receipt of the base forecast: either a user/imported event or a detected regular payment. */
public record UpcomingPaymentDto(
        LocalDate date,
        String title,
        String direction,
        BigDecimal amount,
        String category,
        /** event | regular */
        String origin,
        /** Taxes, salary, credit, rent: cannot simply be skipped. */
        boolean mandatory,
        Long eventId
) {
}
