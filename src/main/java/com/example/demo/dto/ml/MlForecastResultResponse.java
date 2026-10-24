package com.example.demo.dto.ml;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Public response of {@code GET /api/ml/companies/{inn}/forecast}.
 */
public record MlForecastResultResponse(
        String companyInn,
        String model,
        String modelVersion,
        int horizonDays,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate lastHistoryDate,
        int historyDays,
        BigDecimal startBalance,
        List<ForecastPointDto> points,
        List<CashGapDto> cashGaps,
        CashGapDto firstGap,
        MinBalanceDto minBalance,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime calculatedAt
) {

    public record ForecastPointDto(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate date,
            BigDecimal predictedNetCashFlow,
            BigDecimal predictedBalance
    ) {
    }

    public record CashGapDto(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate date,
            BigDecimal predictedBalance,
            BigDecimal deficit
    ) {
    }

    public record MinBalanceDto(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate date,
            BigDecimal predictedBalance
    ) {
    }
}
