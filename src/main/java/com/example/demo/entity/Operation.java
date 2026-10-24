package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "operations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_operations_company_operation",
                columnNames = {"company_inn", "operation_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class Operation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_inn", length = 20, nullable = false)
    private String companyInn;

    @Column(name = "operation_id", length = 50, nullable = false)
    private String operationId;

    @Column(name = "operation_date", nullable = false)
    private LocalDate operationDate;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "direction", nullable = false, length = 20)
    private Direction direction;

    @Column(name = "counterparty_inn", length = 20)
    private String counterpartyInn;

    @Column(name = "counterparty_name")
    private String counterpartyName;

    @Column(name = "payment_purpose", length = 4000)
    private String paymentPurpose;

    @Column(name = "balance_eod", precision = 18, scale = 2)
    private BigDecimal balanceEod;

    @Column(name = "source", nullable = false, length = 20)
    private OperationSource source;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
