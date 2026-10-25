package com.example.demo.dto.mlcore;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Raw answer of the ML Core forecast for one company, returned separately by
 * {@code GET /api/ml/core/companies/{inn}/forecast} for diagnostics and comparison.
 *
 * @param source         always {@code ml-core}
 * @param stub           true when the ML Core explicitly marks the response as a stub
 * @param usedOnScreens  false because this raw response is separate; dashboard requests independently select a source
 */
public record MlCoreForecastResponse(
        String source,
        String companyInn,
        boolean stub,
        boolean usedOnScreens,
        Double cashGapProbability,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate asOfDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate startDate,
        List<Day> days,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate firstCashGapDate,
        BigDecimal maximumCashGap,
        BigDecimal baselineBalance,
        String snapshotHash,
        List<String> warnings,
        int operationsSent,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime calculatedAt
) {

    public record Day(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate date,
            BigDecimal income,
            BigDecimal expenses,
            BigDecimal balance
    ) {
    }
}
