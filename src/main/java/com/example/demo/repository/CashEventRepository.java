package com.example.demo.repository;

import com.example.demo.entity.CashEvent;
import com.example.demo.entity.CashEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface CashEventRepository extends JpaRepository<CashEvent, Long> {

    @Query("""
            SELECT e FROM CashEvent e
            WHERE e.companyInn = :inn
              AND (:status IS NULL OR e.status = :status)
              AND (:fromDate IS NULL OR e.eventDate >= :fromDate)
              AND (:toDate IS NULL OR e.eventDate <= :toDate)
            ORDER BY e.eventDate ASC, e.id ASC
            """)
    List<CashEvent> findFiltered(
            @Param("inn") String inn,
            @Param("status") CashEventStatus status,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate
    );

    List<CashEvent> findByCompanyInnAndEventDateBetweenOrderByEventDateAscIdAsc(
            String companyInn, LocalDate from, LocalDate to
    );

    List<CashEvent> findByCompanyInnAndEventDateGreaterThanEqualAndStatusInOrderByEventDateAscIdAsc(
            String companyInn, LocalDate from, List<CashEventStatus> statuses
    );

    @Query("""
            SELECT e FROM CashEvent e
            WHERE e.companyInn = :inn
              AND (LOWER(e.title) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(e.category, '')) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY e.eventDate DESC
            """)
    List<CashEvent> search(@Param("inn") String inn, @Param("q") String q);
}
