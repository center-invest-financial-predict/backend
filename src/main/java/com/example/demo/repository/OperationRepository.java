package com.example.demo.repository;

import com.example.demo.entity.Operation;
import com.example.demo.entity.OperationSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface OperationRepository extends JpaRepository<Operation, Long> {

    @Query("""
            SELECT o FROM Operation o
            WHERE (o.companyInn = :inn OR o.counterpartyInn = :inn)
              AND (:source IS NULL OR o.source = :source)
              AND (:fromDate IS NULL OR o.operationDate >= :fromDate)
              AND (:toDate IS NULL OR o.operationDate <= :toDate)
            """)
    Page<Operation> findFiltered(
            @Param("inn") String inn,
            @Param("source") OperationSource source,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            Pageable pageable
    );

    @Query("""
            SELECT CASE WHEN COUNT(o) > 0 THEN true ELSE false END
            FROM Operation o
            WHERE o.companyInn = :inn OR o.counterpartyInn = :inn
            """)
    boolean existsMentioningInn(@Param("inn") String inn);

    Optional<Operation> findFirstByCounterpartyInnAndCounterpartyNameNotNullOrderByIdAsc(String counterpartyInn);
}
