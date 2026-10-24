package com.example.demo.dto.ml;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

/**
 * Internal contract: backend -> ml-service {@code POST /forecast}.
 * See docs/ml-forecast-openapi.yaml.
 */
public record MlForecastRequest(
        @JsonProperty("company_inn") String companyInn,
        @JsonProperty("company_code") String companyCode,
        @JsonProperty("industry") String industry,
        @JsonProperty("legal_form") String legalForm,
        @JsonProperty("model") String model,
        @JsonProperty("horizon_days") int horizonDays,
        @JsonProperty("history") List<HistoryDay> history
) {

    public record HistoryDay(
            @JsonProperty("date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate date,
            @JsonProperty("inflow") double inflow,
            @JsonProperty("outflow") double outflow,
            @JsonProperty("balance_eod") double balanceEod
    ) {
    }
}
