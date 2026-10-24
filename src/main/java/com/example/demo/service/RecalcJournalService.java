package com.example.demo.service;

import com.example.demo.dto.forecast.RecalcJournalDto;
import com.example.demo.entity.RecalcJournalEntry;
import com.example.demo.repository.RecalcJournalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RecalcJournalService {

    private final RecalcJournalRepository recalcJournalRepository;

    @Transactional
    public void record(String inn, String message) {
        recalcJournalRepository.save(new RecalcJournalEntry(inn, LocalDateTime.now(), message));
    }

    @Transactional(readOnly = true)
    public List<RecalcJournalDto> list(String inn, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        return recalcJournalRepository.findByCompanyInnOrderByOccurredAtDesc(inn, PageRequest.of(0, size))
                .stream()
                .map(RecalcJournalDto::from)
                .toList();
    }
}
