package com.example.demo.dto.forecast;

import com.example.demo.entity.RecalcJournalEntry;

import java.time.LocalDateTime;

public record RecalcJournalDto(
        Long id,
        String companyInn,
        LocalDateTime occurredAt,
        String message
) {
    public static RecalcJournalDto from(RecalcJournalEntry e) {
        return new RecalcJournalDto(e.getId(), e.getCompanyInn(), e.getOccurredAt(), e.getMessage());
    }
}
