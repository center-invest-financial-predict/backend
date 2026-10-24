package com.example.demo.service;

import com.example.demo.client.IntegrationClient;
import com.example.demo.dto.operation.ImportResult;
import com.example.demo.dto.operation.OperationDto;
import com.example.demo.entity.OperationSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class IntegrationSyncService {

    private final IntegrationClient integrationClient;
    private final OperationImportService operationImportService;
    private final RecalcJournalService recalcJournalService;

    @Async
    public void syncInnsAsync(Collection<String> inns) {
        if (inns == null || inns.isEmpty()) {
            return;
        }
        for (String inn : inns) {
            syncInn(inn);
        }
    }

    public ImportResult syncInn(String inn) {
        ImportResult total = ImportResult.empty();
        for (OperationSource source : List.of(OperationSource.ONE_C, OperationSource.GOS_SERVICE)) {
            try {
                List<OperationDto> operations = integrationClient.fetchOperations(source, inn);
                ImportResult result = operationImportService.upsertBatch(inn, source, operations);
                total = total.merge(result);
                if (result.getInserted() > 0 || result.getDuplicates() > 0) {
                    recalcJournalService.record(
                            inn,
                            "Синхронизация " + source.getCode() + ": +" + result.getInserted()
                                    + ", дублей " + result.getDuplicates()
                    );
                }
                log.info("Synced {} for INN {}: inserted={}, duplicates={}, invalid={}",
                        source, inn, result.getInserted(), result.getDuplicates(), result.getInvalid());
            } catch (Exception ex) {
                log.warn("Sync from {} for INN {} failed: {}", source, inn, ex.getMessage());
            }
        }
        return total;
    }
}
