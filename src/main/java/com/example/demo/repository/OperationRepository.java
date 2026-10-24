package com.example.demo.repository;

import com.example.demo.entity.Operation;
import com.example.demo.entity.OperationSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    Optional<Operation> findFirstByCompanyInnAndBalanceEodIsNotNullOrderByOperationDateDescIdDesc(String companyInn);

    @Query("""
            SELECT COALESCE(SUM(o.amount), 0) FROM Operation o
            WHERE o.companyInn = :inn
              AND o.direction = com.example.demo.entity.Direction.INCOME
              AND o.operationDate >= :fromDate
              AND o.operationDate <= :toDate
            """)
    BigDecimal sumIncome(@Param("inn") String inn, @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

    @Query("""
            SELECT COALESCE(SUM(o.amount), 0) FROM Operation o
            WHERE o.companyInn = :inn
              AND o.direction = com.example.demo.entity.Direction.EXPENSE
              AND o.operationDate >= :fromDate
              AND o.operationDate <= :toDate
            """)
    BigDecimal sumExpense(@Param("inn") String inn, @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

    @Query("""
            SELECT o FROM Operation o
            WHERE o.companyInn = :inn
              AND o.operationDate >= :fromDate
              AND o.operationDate <= :toDate
            ORDER BY o.operationDate ASC, o.id ASC
            """)
    List<Operation> findByCompanyInnAndDateRange(
            @Param("inn") String inn,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate
    );

    @Query("""
            SELECT o FROM Operation o
            WHERE o.companyInn = :inn
              AND (LOWER(COALESCE(o.counterpartyName, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(o.paymentPurpose, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(o.counterpartyInn, '')) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY o.operationDate DESC
            """)
    List<Operation> search(@Param("inn") String inn, @Param("q") String q, Pageable pageable);

    @Query("""
            SELECT MAX(o.operationDate) FROM Operation o
            WHERE o.companyInn = :inn AND o.source = :source
            """)
    Optional<LocalDate> findMaxDateBySource(@Param("inn") String inn, @Param("source") OperationSource source);

    @Query("SELECT MAX(o.operationDate) FROM Operation o WHERE o.companyInn = :inn")
    Optional<LocalDate> findLastOperationDate(@Param("inn") String inn);

    @Query("SELECT MIN(o.operationDate) FROM Operation o WHERE o.companyInn = :inn")
    Optional<LocalDate> findFirstOperationDate(@Param("inn") String inn);

    long countByCompanyInn(String companyInn);

    long countByCompanyInnAndSource(String companyInn, OperationSource source);
}
