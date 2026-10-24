package com.example.demo.service;

import com.example.demo.client.IntegrationClient;
import com.example.demo.dto.operation.ImportResult;
import com.example.demo.dto.operation.OperationDto;
import com.example.demo.dto.operation.SyncResponse;
import com.example.demo.dto.operation.SyncResponse.SourceResult;
import com.example.demo.entity.OperationSource;
import com.example.demo.forecast.ForecastService;
import com.example.demo.repository.CompanySourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Pulls operations from the external services (1С, Госуслуги). Only the sources the user has switched on are asked;
 * CSV is never pulled (it is uploaded by the user).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntegrationSyncService {

    private static final List<OperationSource> PULL_SOURCES = List.of(OperationSource.ONE_C, OperationSource.GOS_SERVICE);

    private final IntegrationClient integrationClient;
    private final OperationImportService operationImportService;
    private final ForecastService forecastService;
    private final CompanySourceRepository companySourceRepository;

    @Async
    public void syncInnsAsync(Collection<String> inns) {
        if (inns == null || inns.isEmpty()) {
            return;
        }
        for (String inn : inns) {
            syncInn(inn, null);
        }
    }

    public SyncResponse syncInn(String inn) {
        return syncInn(inn, null);
    }

    /**
     * @param onlySource when set, only this pull source is asked (it still has to be switched on)
     * @throws IllegalArgumentException the code is unknown or is not a pull source (csv)
     */
    public SyncResponse syncInn(String inn, String onlySource) {
        List<OperationSource> targets = PULL_SOURCES;
        if (onlySource != null && !onlySource.isBlank()) {
            OperationSource one = OperationSource.from(onlySource);
            if (!PULL_SOURCES.contains(one)) {
                throw new IllegalArgumentException("Источник " + one.getCode() + " загружается файлом, а не синхронизацией");
            }
            targets = List.of(one);
        }

        Set<OperationSource> disabled = SourceFilter.disabled(companySourceRepository, inn);
        ImportResult total = ImportResult.empty();
        List<SourceResult> results = new ArrayList<>();
        for (OperationSource source : targets) {
            if (disabled.contains(source)) {
                results.add(new SourceResult(source.getCode(), SyncResponse.DISABLED, 0, 0, 0,
                        "Источник отключён: данные не запрашивались"));
                continue;
            }
            try {
                List<OperationDto> operations = integrationClient.fetchOperations(source, inn);
                ImportResult result = operationImportService.upsertBatch(inn, source, operations);
                total = total.merge(result);
                if (result.getInserted() > 0) {
                    forecastService.onDataChanged(
                            inn,
                            "Синхронизация " + source.getCode() + ": +" + result.getInserted()
                                    + ", дублей " + result.getDuplicates()
                    );
                }
                log.info("Synced {} for INN {}: inserted={}, duplicates={}, invalid={}",
                        source, inn, result.getInserted(), result.getDuplicates(), result.getInvalid());
                results.add(new SourceResult(source.getCode(), SyncResponse.SYNCED, result.getInserted(),
                        result.getDuplicates(), result.getInvalid(), null));
            } catch (IntegrationClient.SourceUnavailableException ex) {
                results.add(new SourceResult(source.getCode(),
                        ex.isNotConfigured() ? SyncResponse.NOT_CONFIGURED : SyncResponse.UNAVAILABLE,
                        0, 0, 0, ex.getMessage()));
            } catch (Exception ex) {
                log.warn("Sync from {} for INN {} failed: {}", source, inn, ex.getMessage());
                results.add(new SourceResult(source.getCode(), SyncResponse.UNAVAILABLE, 0, 0, 0,
                        "Не удалось загрузить данные источника " + source.getCode()));
            }
        }
        return new SyncResponse(total.getInserted(), total.getDuplicates(), total.getInvalid(), total.getSkipped(),
                results);
    }
}
