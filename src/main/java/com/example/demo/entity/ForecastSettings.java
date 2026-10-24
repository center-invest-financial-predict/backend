package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "forecast_settings")
@Getter
@Setter
@NoArgsConstructor
public class ForecastSettings {

    @Id
    @Column(name = "company_inn", length = 20, nullable = false)
    private String companyInn;

    @Column(name = "min_balance", precision = 18, scale = 2)
    private BigDecimal minBalance;

    @Column(name = "salary_day")
    private Integer salaryDay;

    @Column(name = "caution_level", length = 50)
    private String cautionLevel;

    @Column(name = "notify_rules_json", columnDefinition = "TEXT")
    private String notifyRulesJson;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
    }
}
