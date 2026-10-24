package com.example.demo.dto.forecast;

import com.example.demo.entity.Direction;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Two-step import of expected payments: parse and show ({@code preview}), then save what the user confirmed. */
public final class CashEventImportDto {

    private CashEventImportDto() {
    }

    /**
     * One parsed CSV line. {@code eventDate/title/direction/amount/category} have the same names as
     * {@link CashEventDto.CreateRequest}, so the user can edit rows and send them back to {@code confirm} as they are.
     *
     * @param valid false when the line cannot be imported, then {@code error} says why
     */
    public record Row(
            int rowNumber,
            LocalDate eventDate,
            String title,
            Direction direction,
            BigDecimal amount,
            String category,
            boolean valid,
            String error
    ) {
    }

    public record PreviewResponse(List<Row> rows, int validCount, int invalidCount) {
    }

    public record ConfirmRequest(@NotNull List<CashEventDto.CreateRequest> rows) {
    }

    /** @param rejected rows (by index in the request) that failed validation; the others are saved. */
    public record ConfirmResponse(List<CashEventDto> created, List<Rejected> rejected) {
    }

    public record Rejected(int index, String error) {
    }
}
