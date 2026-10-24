package com.example.demo.dto.forecast;

import com.example.demo.entity.CashEvent;
import com.example.demo.entity.CashEventStatus;
import com.example.demo.entity.Direction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record CashEventDto(
        Long id,
        String companyInn,
        LocalDate eventDate,
        String title,
        Direction direction,
        BigDecimal amount,
        String category,
        CashEventStatus status,
        String source,
        Long operationId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CashEventDto from(CashEvent e) {
        return new CashEventDto(
                e.getId(),
                e.getCompanyInn(),
                e.getEventDate(),
                e.getTitle(),
                e.getDirection(),
                e.getAmount(),
                e.getCategory(),
                e.getStatus(),
                e.getSource(),
                e.getOperationId(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    public record CreateRequest(
            @NotNull LocalDate eventDate,
            @NotBlank String title,
            @NotNull Direction direction,
            @NotNull BigDecimal amount,
            String category,
            CashEventStatus status,
            String source
    ) {
    }

    public record UpdateRequest(
            LocalDate eventDate,
            String title,
            Direction direction,
            BigDecimal amount,
            String category,
            CashEventStatus status,
            String source
    ) {
    }
}
