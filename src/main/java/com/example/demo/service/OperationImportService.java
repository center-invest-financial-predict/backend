package com.example.demo.service;

import com.example.demo.dto.operation.ImportResult;
import com.example.demo.dto.operation.OperationDto;
import com.example.demo.entity.OperationSource;
import com.example.demo.exception.UnknownInnException;
import com.example.demo.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OperationImportService {

    private static final int CHUNK_SIZE = 1000;

    /**
     * Portable insert-if-absent (works on PostgreSQL and H2).
     */
    private static final String INSERT_SQL = """
            INSERT INTO operations (
                company_inn, operation_id, operation_date, amount, direction,
                counterparty_inn, counterparty_name, payment_purpose, balance_eod,
                source, created_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            WHERE NOT EXISTS (
                SELECT 1 FROM operations o
                WHERE o.company_inn = ? AND o.operation_id = ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final CompanyRepository companyRepository;

    @Transactional
    public ImportResult upsertBatch(String inn, OperationSource source, List<OperationDto> operations) {
        if (!companyRepository.existsById(inn)) {
            throw new UnknownInnException(List.of(inn));
        }
        if (operations == null || operations.isEmpty()) {
            return ImportResult.empty();
        }

        Map<String, OperationDto> unique = new LinkedHashMap<>();
        int invalid = 0;
        for (OperationDto dto : operations) {
            if (dto == null || dto.getOperationId() == null || dto.getOperationId().isBlank()
                    || dto.getOperationDate() == null || dto.getAmount() == null || dto.getDirection() == null) {
                invalid++;
                continue;
            }
            unique.putIfAbsent(dto.getOperationId().trim(), dto);
        }

        int duplicatesInBatch = operations.size() - unique.size() - invalid;
        int inserted = 0;
        List<OperationDto> batch = new ArrayList<>(unique.values());

        for (int i = 0; i < batch.size(); i += CHUNK_SIZE) {
            List<OperationDto> chunk = batch.subList(i, Math.min(i + CHUNK_SIZE, batch.size()));
            inserted += insertChunk(inn, source, chunk);
        }

        int duplicates = duplicatesInBatch + (batch.size() - inserted);
        return new ImportResult(inserted, Math.max(duplicates, 0), invalid, 0);
    }

    private int insertChunk(String inn, OperationSource source, List<OperationDto> chunk) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int[][] results = jdbcTemplate.batchUpdate(INSERT_SQL, chunk, chunk.size(), (ps, dto) -> {
            String operationId = dto.getOperationId().trim();
            ps.setString(1, inn);
            ps.setString(2, operationId);
            ps.setDate(3, Date.valueOf(dto.getOperationDate()));
            ps.setBigDecimal(4, dto.getAmount());
            ps.setString(5, dto.getDirection().name());
            ps.setString(6, blankToNull(dto.getCounterpartyInn()));
            ps.setString(7, blankToNull(dto.getCounterpartyName()));
            ps.setString(8, blankToNull(dto.getPaymentPurpose()));
            if (dto.getBalanceEod() != null) {
                ps.setBigDecimal(9, dto.getBalanceEod());
            } else {
                ps.setObject(9, null);
            }
            ps.setString(10, source.getCode());
            ps.setTimestamp(11, now);
            ps.setString(12, inn);
            ps.setString(13, operationId);
        });

        int inserted = 0;
        for (int[] counts : results) {
            for (int count : counts) {
                if (count > 0) {
                    inserted++;
                }
            }
        }
        return inserted;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
