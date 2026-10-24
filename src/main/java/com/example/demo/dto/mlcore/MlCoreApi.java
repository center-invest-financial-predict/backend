package com.example.demo.dto.mlcore;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Wire contract of the "Cashflow ML Core" server (snake_case, see docs/ml-core-openapi.json).
 * Only the fields the backend needs are mapped; unknown fields are ignored.
 */
public final class MlCoreApi {

    private MlCoreApi() {
    }

    /** {@code POST /v1/forecasts}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ForecastRequest(
            @JsonProperty("operations") List<Operation> operations,
            @JsonProperty("as_of_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate asOfDate,
            @JsonProperty("opening_balance") BigDecimal openingBalance
    ) {
    }

    /** Statement row. {@code direction} is the Russian label ("Поступление" / "Списание"), amount is positive. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Operation(
            @JsonProperty("company_id") String companyId,
            @JsonProperty("operation_id") String operationId,
            @JsonProperty("operation_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate operationDate,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("direction") String direction,
            @JsonProperty("counterparty_inn") String counterpartyInn,
            @JsonProperty("counterparty_name") String counterpartyName,
            @JsonProperty("payment_purpose") String paymentPurpose,
            @JsonProperty("balance_eod") BigDecimal balanceEod
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForecastResult(
            @JsonProperty("is_stub") Boolean isStub,
            @JsonProperty("cash_gap_probability") Double cashGapProbability,
            @JsonProperty("as_of_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate asOfDate,
            @JsonProperty("start_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate startDate,
            @JsonProperty("days") List<DailyBalance> days,
            @JsonProperty("first_cash_gap_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate firstCashGapDate,
            @JsonProperty("maximum_cash_gap") BigDecimal maximumCashGap,
            @JsonProperty("baseline_balance") BigDecimal baselineBalance,
            @JsonProperty("snapshot_hash") String snapshotHash,
            @JsonProperty("warnings") List<String> warnings
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DailyBalance(
            @JsonProperty("date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate date,
            @JsonProperty("income") BigDecimal income,
            @JsonProperty("expenses") BigDecimal expenses,
            @JsonProperty("balance") BigDecimal balance
    ) {
    }

    // ------------------------------------------------------------------ /v1/chat

    /** {@code POST /v1/chat}: {@code message} (1..12000 chars), {@code history} (at most 40) and the forecast snapshot. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatRequest(
            @JsonProperty("message") String message,
            @JsonProperty("history") List<ChatMessage> history,
            @JsonProperty("snapshot") Snapshot snapshot
    ) {
    }

    public record ChatMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content
    ) {
    }

    /** The forecast the LLM talks about (server schema "ForecastResult-Input"). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Snapshot(
            @JsonProperty("is_stub") boolean isStub,
            @JsonProperty("cash_gap_probability") Double cashGapProbability,
            @JsonProperty("as_of_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate asOfDate,
            @JsonProperty("start_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate startDate,
            @JsonProperty("days") List<DailyBalance> days,
            @JsonProperty("first_cash_gap_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate firstCashGapDate,
            @JsonProperty("maximum_cash_gap") BigDecimal maximumCashGap,
            @JsonProperty("events") List<SnapshotEvent> events,
            @JsonProperty("base_events") List<SnapshotEvent> baseEvents,
            @JsonProperty("active_scenarios") List<Scenario> activeScenarios,
            @JsonProperty("baseline_balance") BigDecimal baselineBalance,
            @JsonProperty("snapshot_hash") String snapshotHash,
            @JsonProperty("warnings") List<String> warnings
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SnapshotEvent(
            @JsonProperty("event_id") String eventId,
            @JsonProperty("operation_date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate operationDate,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("direction") String direction,
            @JsonProperty("counterparty_inn") String counterpartyInn,
            @JsonProperty("counterparty_name") String counterpartyName,
            @JsonProperty("payment_purpose") String paymentPurpose,
            @JsonProperty("source_operation_id") String sourceOperationId
    ) {
    }

    /** Scenario in the server vocabulary; {@code kind} is a free string (only {@code delay} has been seen so far). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scenario(
            @JsonProperty("id") String id,
            @JsonProperty("kind") String kind,
            @JsonProperty("target_event_ids") List<String> targetEventIds,
            @JsonProperty("days") Integer days,
            @JsonProperty("percent") String percent,
            @JsonProperty("amount") String amount,
            @JsonProperty("date")
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate date,
            @JsonProperty("direction") String direction,
            @JsonProperty("description") String description
    ) {
    }

    /** Only the text and the structured scenarios are used; the server's own numbers are ignored on purpose. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatResponse(
            @JsonProperty("answer") String answer,
            @JsonProperty("clarification_needed") Boolean clarificationNeeded,
            @JsonProperty("applied_scenarios") List<Scenario> appliedScenarios,
            @JsonProperty("removed_scenario_ids") List<String> removedScenarioIds
    ) {
    }

    /** {@code POST /v1/imports/table}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableImportResponse(
            @JsonProperty("rows") List<ImportRow> rows,
            @JsonProperty("column_mapping") Map<String, String> columnMapping,
            @JsonProperty("warnings") List<String> warnings
    ) {
    }

    /** {@code POST /v1/imports/receipt}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReceiptResponse(
            @JsonProperty("row") ImportRow row,
            @JsonProperty("warnings") List<String> warnings
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImportRow(
            @JsonProperty("row_number") Integer rowNumber,
            @JsonProperty("operation") Map<String, Object> operation,
            @JsonProperty("missing_fields") List<String> missingFields,
            @JsonProperty("warnings") List<String> warnings,
            @JsonProperty("ready_for_review") Boolean readyForReview
    ) {
    }
}
