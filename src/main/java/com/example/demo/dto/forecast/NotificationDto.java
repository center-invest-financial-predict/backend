package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Header bell. Items are computed from the current alerts and the recalculation journal; only the "read" mark is
 * stored (per user and company). The id is stable while the underlying situation is the same.
 */
public record NotificationDto(
        String id,
        /** ALERT | JOURNAL */
        String kind,
        /** alert type (GAP, RISK, ...) or JOURNAL */
        String type,
        /** critical | warning | info */
        String severity,
        String title,
        String description,
        LocalDate date,
        LocalDateTime occurredAt,
        BigDecimal amount,
        String actionLabel,
        String actionTarget,
        boolean read
) {
    public record Response(String companyInn, long unread, List<NotificationDto> items) {
    }
}
