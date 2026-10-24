package com.example.demo.repository;

import com.example.demo.entity.RecalcJournalEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecalcJournalRepository extends JpaRepository<RecalcJournalEntry, Long> {

    List<RecalcJournalEntry> findByCompanyInnOrderByOccurredAtDesc(String companyInn, Pageable pageable);
}
