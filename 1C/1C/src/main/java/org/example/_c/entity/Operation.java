package org.example._c.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(
        name = "operations",
        uniqueConstraints = @UniqueConstraint(name = "uk_onec_ops", columnNames = {"company_inn", "operation_id"})
)
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
    private String direction;

    @Column(name = "counterparty_inn", length = 20)
    private String counterpartyInn;

    @Column(name = "counterparty_name")
    private String counterpartyName;

    @Column(name = "payment_purpose", length = 4000)
    private String paymentPurpose;

    @Column(name = "balance_eod", precision = 18, scale = 2)
    private BigDecimal balanceEod;

    public Long getId() {
        return id;
    }

    public String getCompanyInn() {
        return companyInn;
    }

    public void setCompanyInn(String companyInn) {
        this.companyInn = companyInn;
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public LocalDate getOperationDate() {
        return operationDate;
    }

    public void setOperationDate(LocalDate operationDate) {
        this.operationDate = operationDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getCounterpartyInn() {
        return counterpartyInn;
    }

    public void setCounterpartyInn(String counterpartyInn) {
        this.counterpartyInn = counterpartyInn;
    }

    public String getCounterpartyName() {
        return counterpartyName;
    }

    public void setCounterpartyName(String counterpartyName) {
        this.counterpartyName = counterpartyName;
    }

    public String getPaymentPurpose() {
        return paymentPurpose;
    }

    public void setPaymentPurpose(String paymentPurpose) {
        this.paymentPurpose = paymentPurpose;
    }

    public BigDecimal getBalanceEod() {
        return balanceEod;
    }

    public void setBalanceEod(BigDecimal balanceEod) {
        this.balanceEod = balanceEod;
    }
}
