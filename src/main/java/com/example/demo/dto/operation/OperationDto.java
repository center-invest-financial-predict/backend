package com.example.demo.dto.operation;

import com.example.demo.entity.Direction;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OperationDto {

    /** Optional: company code from CSV (C001). Used to resolve INN on multi-company uploads. */
    @JsonAlias("company_id")
    private String companyId;

    /** Optional: company INN from CSV when present. */
    @JsonAlias({"company_inn", "inn"})
    private String companyInn;

    @JsonAlias("operation_id")
    @NotBlank
    private String operationId;

    @JsonAlias("operation_date")
    @NotNull
    private LocalDate operationDate;

    @NotNull
    private BigDecimal amount;

    @NotNull
    private Direction direction;

    @JsonAlias("counterparty_inn")
    private String counterpartyInn;

    @JsonAlias("counterparty_name")
    private String counterpartyName;

    @JsonAlias("payment_purpose")
    private String paymentPurpose;

    @JsonAlias("balance_eod")
    private BigDecimal balanceEod;
}
