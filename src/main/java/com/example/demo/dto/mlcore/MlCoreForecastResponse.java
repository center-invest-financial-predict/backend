package com.example.demo.dto.mlcore;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Raw answer of the ML Core forecast for one company. It is shown only by
 * {@code GET /api/ml/core/companies/{inn}/forecast} and is never mixed into the dashboard,
 * chart, alerts or assistant (those use the built-in engine, see {@code usedOnScreens}).
 *
 * @param source         always {@code ml-core}
 * @param stub           the server marks its forecast as a stub (demo replay of the last days); such numbers
 *                       are not a real prediction and carry no probability
 * @param usedOnScreens  always false: numbers of the other screens come from the built-in engine
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
