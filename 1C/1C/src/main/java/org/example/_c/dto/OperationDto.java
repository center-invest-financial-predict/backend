package org.example._c.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OperationDto(
        @JsonProperty("operationId") String operationId,
        @JsonProperty("operationDate") LocalDate operationDate,
        BigDecimal amount,
        String direction,
        @JsonProperty("counterpartyInn") String counterpartyInn,
        @JsonProperty("counterpartyName") String counterpartyName,
        @JsonProperty("paymentPurpose") String paymentPurpose,
        @JsonProperty("balanceEod") BigDecimal balanceEod
) {
}
