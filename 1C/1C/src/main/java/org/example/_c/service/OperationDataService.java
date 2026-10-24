package org.example._c.service;

import org.example._c.dto.OperationDto;
import org.example._c.entity.Company;
import org.example._c.entity.Operation;
import org.example._c.repository.CompanyRepository;
import org.example._c.repository.OperationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@Service
public class OperationDataService {

    private final CompanyRepository companyRepository;
    private final OperationRepository operationRepository;

    public OperationDataService(CompanyRepository companyRepository, OperationRepository operationRepository) {
        this.companyRepository = companyRepository;
        this.operationRepository = operationRepository;
    }

    @Transactional(readOnly = true)
    public List<OperationDto> findByInn(String inn) {
        Company company = companyRepository.findByInn(inn)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown INN: " + inn));
        return operationRepository
                .findByCompanyInnOrderByOperationDateAscOperationIdAsc(company.getInn())
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OperationDto> findByInnAfter(String inn, LocalDate fromDate) {
        Company company = companyRepository.findByInn(inn)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown INN: " + inn));
        List<Operation> operations = fromDate == null
                ? operationRepository.findByCompanyInnOrderByOperationDateAscOperationIdAsc(company.getInn())
                : operationRepository.findByCompanyInnAndOperationDateGreaterThanEqualOrderByOperationDateAscOperationIdAsc(
                company.getInn(), fromDate);
        return operations.stream().map(this::toDto).toList();
    }

    private OperationDto toDto(Operation operation) {
        return new OperationDto(
                operation.getOperationId(),
                operation.getOperationDate(),
                operation.getAmount(),
                operation.getDirection(),
                operation.getCounterpartyInn(),
                operation.getCounterpartyName(),
                operation.getPaymentPurpose(),
                operation.getBalanceEod()
        );
    }
}
