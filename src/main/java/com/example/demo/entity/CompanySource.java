package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Per-company switch of a data source. No row means "enabled": only the user's explicit choice is stored.
 * A disabled source is left out of the forecast calculation.
 */
@Entity
@Table(name = "company_sources")
@Getter
@Setter
@NoArgsConstructor
public class CompanySource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_inn", length = 20, nullable = false)
    private String companyInn;

    /** {@link OperationSource#getCode()}. */
    @Column(name = "source_code", length = 50, nullable = false)
    private String sourceCode;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
    }
}
