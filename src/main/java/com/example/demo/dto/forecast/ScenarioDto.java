package com.example.demo.dto.forecast;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record ScenarioDto(
        Long id,
        String companyInn,
        String name,
        Object conditions,
        /** Human readable form of the conditions (Russian). */
        java.util.List<String> conditionsText,
        /** Result of the scenario recalculated on current data; null while history is insufficient. */
        ForecastSeriesResponse.Summary summary,
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

    /** @param horizonDays 1..90, default 30 */
    public record PreviewRequest(
            Object conditions,
            Integer horizonDays
    ) {
    }

    public record ExampleDto(
            String id,
            String name,
            String description,
            Object conditions,
            java.math.BigDecimal minBalance,
            java.time.LocalDate gapDate,
            java.math.BigDecimal gapAmount,
            java.math.BigDecimal gapProbability
    ) {
    }
}
