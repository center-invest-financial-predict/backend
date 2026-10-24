package com.example.demo.dto.ml;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

/**
 * Internal contract: ml-service {@code POST /forecast} response.
 * See docs/ml-forecast-openapi.yaml.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlForecastResponse(
        @JsonProperty("model") String model,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("company_inn") String companyInn,
        @JsonProperty("last_history_date")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate lastHistoryDate,
        @JsonProperty("start_balance") Double startBalance,
        @JsonProperty("horizon_days") Integer horizonDays,
        @JsonProperty("points") List<Point> points,
        @JsonProperty("cash_gaps") List<Gap> cashGaps,
        @JsonProperty("first_gap") Gap firstGap,
        @JsonProperty("min_balance") MinBalance minBalance,
        @JsonProperty("inference_time_ms") Double inferenceTimeMs
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Point(
            @JsonProperty("date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate date,
            @JsonProperty("predicted_net_cash_flow") Double predictedNetCashFlow,
            @JsonProperty("predicted_balance") Double predictedBalance,
            @JsonProperty("control_input_u") Double controlInputU
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Gap(
            @JsonProperty("date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate date,
            @JsonProperty("predicted_balance") Double predictedBalance,
            @JsonProperty("deficit") Double deficit
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MinBalance(
            @JsonProperty("date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate date,
            @JsonProperty("predicted_balance") Double predictedBalance
    ) {
    }
}
