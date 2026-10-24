package com.example.demo.dto.forecast;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record ScenarioDto(
        Long id,
        String companyInn,
        String name,
        Object conditions,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public record CreateRequest(
            @NotBlank String name,
            @NotNull Object conditions
    ) {
    }

    public record UpdateRequest(
            String name,
            Object conditions
    ) {
    }

    public record PreviewRequest(
            Object conditions
    ) {
    }

    public record ExampleDto(
            String id,
            String name,
            String description,
            Object conditions,
            java.math.BigDecimal minBalance,
            java.time.LocalDate gapDate,
            java.math.BigDecimal gapAmount
    ) {
    }
}
