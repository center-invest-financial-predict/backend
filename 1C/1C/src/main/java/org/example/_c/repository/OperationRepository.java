package org.example._c.repository;

import org.example._c.entity.Operation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface OperationRepository extends JpaRepository<Operation, Long> {

    List<Operation> findByCompanyInnOrderByOperationDateAscOperationIdAsc(String companyInn);

    List<Operation> findByCompanyInnAndOperationDateGreaterThanEqualOrderByOperationDateAscOperationIdAsc(
            String companyInn,
            LocalDate fromDate
    );

    boolean existsByCompanyInnAndOperationId(String companyInn, String operationId);
}
