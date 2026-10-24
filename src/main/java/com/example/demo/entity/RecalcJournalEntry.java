package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "recalc_journal")
@Getter
@Setter
@NoArgsConstructor
public class RecalcJournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_inn", length = 20, nullable = false)
    private String companyInn;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "message", length = 1000, nullable = false)
    private String message;

    public RecalcJournalEntry(String companyInn, LocalDateTime occurredAt, String message) {
        this.companyInn = companyInn;
        this.occurredAt = occurredAt;
        this.message = message;
    }

    @PrePersist
    protected void onCreate() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
    }
}
