package com.example.demo.dto.operation;

import com.example.demo.entity.Direction;
import com.example.demo.forecast.CategoryClassifier;
import com.example.demo.entity.Operation;
import com.example.demo.entity.OperationSource;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class OperationResponse {

    private Long id;
    private String companyInn;
    private String operationId;
    private LocalDate operationDate;
    private BigDecimal amount;
    private Direction direction;
    private String counterpartyInn;
    private String counterpartyName;
    private String paymentPurpose;
    private BigDecimal balanceEod;
    /** Derived by rules (see CategoryClassifier); not stored. */
    private String category;
    private OperationSource source;
    private LocalDateTime createdAt;

    public static OperationResponse from(Operation operation) {
        return OperationResponse.builder()
                .id(operation.getId())
                .companyInn(operation.getCompanyInn())
                .operationId(operation.getOperationId())
                .operationDate(operation.getOperationDate())
                .amount(operation.getAmount())
                .direction(operation.getDirection())
                .counterpartyInn(operation.getCounterpartyInn())
                .counterpartyName(operation.getCounterpartyName())
                .paymentPurpose(operation.getPaymentPurpose())
                .balanceEod(operation.getBalanceEod())
                .category(CategoryClassifier.DEFAULT.classify(operation))
                .source(operation.getSource())
                .createdAt(operation.getCreatedAt())
                .build();
    }
}
