package com.example.demo.service;

import com.example.demo.client.MlCoreClient;
import com.example.demo.dto.mlcore.ImportPreviewResponse;
import com.example.demo.dto.mlcore.MlCoreApi;
import com.example.demo.dto.mlcore.MlCoreForecastResponse;
import com.example.demo.dto.mlcore.MlCoreHealthResponse;
import com.example.demo.dto.operation.ImportResult;
import com.example.demo.dto.operation.OperationDto;
import com.example.demo.entity.Company;
import com.example.demo.entity.Direction;
import com.example.demo.entity.Operation;
import com.example.demo.entity.OperationSource;
import com.example.demo.exception.MlCoreException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.forecast.ForecastService;
import com.example.demo.forecast.ForecastTypes.Inputs;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.OperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the backend does with the "Cashflow ML Core" server:
 * health, a labelled (separate) forecast, and LLM-assisted import preview of tables and receipts.
 * <p>
 * This service exposes the raw ML Core answer for diagnostics and comparison. The dashboard forecast is assembled by
 * {@link com.example.demo.forecast.ServerForecastSource}, which adopts supported ML Core forecasts and otherwise
 * falls back to the built-in engine.
 */
@Service
@RequiredArgsConstructor
public class MlCoreService {

    public static final String SOURCE = "ml-core";
    static final int MAX_FILE_BYTES = 20 * 1024 * 1024;

    private final MlCoreClient client;
    private final ForecastService forecastService;
    private final OperationRepository operationRepository;
    private final CompanyRepository companyRepository;
    private final OperationImportService operationImportService;

    @Value("${ml.core.history-days:365}")
    private int historyDays = 365;

    // ------------------------------------------------------------------ health

    /** Never throws: reports DISABLED / DOWN instead. */
    public MlCoreHealthResponse health() {
        if (!client.isEnabled()) {
            return new MlCoreHealthResponse(false, client.baseUrl(), "DISABLED", false, false, null,
                    "ml.core.enabled=false");
        }
        boolean live = false;
        boolean ready = false;
        String llmMode = null;
        String message = null;
        try {
            live = "ok".equalsIgnoreCase(client.live().getOrDefault("status", "ok"));
            Map<String, String> r = client.ready();
            ready = "ready".equalsIgnoreCase(r.get("status")) || "ok".equalsIgnoreCase(r.get("status"));
            llmMode = r.get("llm_mode");
            if (!ready) {
                message = "status=" + r.get("status");
            }
        } catch (MlCoreException e) {
            message = e.getMessage();
        }
        String status = ready ? "UP" : (live ? "DEGRADED" : "DOWN");
        return new MlCoreHealthResponse(true, client.baseUrl(), status, live, ready, llmMode, message);
    }

    // ------------------------------------------------------------------ forecast (separate, labelled)

    public MlCoreForecastResponse forecast(String inn) {
        // Same opening balance and as-of date as the built-in forecast; throws InsufficientHistoryException (422).
        Inputs inputs = forecastService.inputs(inn);
        Company company = company(inn);

        LocalDate asOf = inputs.asOf();
        // Only the sources the user has switched on go to the model.
        List<Operation> ops = SourceFilter.without(operationRepository.findByCompanyInnAndDateRange(
                inn, asOf.minusDays(Math.max(historyDays, 1)), asOf), inputs.excludedSources());
        List<MlCoreApi.Operation> payload = new ArrayList<>(ops.size());
        for (Operation op : ops) {
            if (op.getAmount() == null || op.getAmount().signum() <= 0 || op.getDirection() == null) {
                continue; // ML Core requires amount > 0
            }
            payload.add(new MlCoreApi.Operation(company.getCompanyCode(), op.getOperationId(),
                    op.getOperationDate(), op.getAmount(), op.getDirection().getLabel(),
                    op.getCounterpartyInn(), op.getCounterpartyName(), op.getPaymentPurpose(), op.getBalanceEod()));
        }
        if (payload.isEmpty()) {
            throw new com.example.demo.exception.InsufficientHistoryException("ml-core", 1, 0);
        }

        MlCoreApi.ForecastResult r = client.forecast(new MlCoreApi.ForecastRequest(payload, asOf,
                inputs.startBalance(), company.getIndustry(), company.getLegalForm()));
        return toResponse(inn, r, payload.size());
    }

    static MlCoreForecastResponse toResponse(String inn, MlCoreApi.ForecastResult r, int operationsSent) {
        List<MlCoreForecastResponse.Day> days = r.days().stream()
                .map(d -> new MlCoreForecastResponse.Day(d.date(), d.income(), d.expenses(), d.balance()))
                .toList();
        boolean stub = r.isStub() == null || r.isStub(); // the schema default is true
        return new MlCoreForecastResponse(SOURCE, inn, stub, false, r.cashGapProbability(), r.asOfDate(),
                r.startDate(), days, r.firstCashGapDate(), r.maximumCashGap(), r.baselineBalance(),
                r.snapshotHash(), r.warnings() == null ? List.of() : r.warnings(), operationsSent,
                LocalDateTime.now().withNano(0));
    }

    // ------------------------------------------------------------------ import preview

    public ImportPreviewResponse previewTable(String inn, MultipartFile file, String sheetName) {
        Company company = company(inn);
        byte[] content = read(file);
        MlCoreApi.TableImportResponse r = client.importTable(file.getOriginalFilename(), content,
                file.getContentType(), company.getCompanyCode(), sheetName);
        List<ImportPreviewResponse.Row> rows = r.rows().stream().map(MlCoreService::toRow).toList();
        return new ImportPreviewResponse(SOURCE, inn, rows,
                r.columnMapping() == null ? Map.of() : r.columnMapping(),
                r.warnings() == null ? List.of() : r.warnings());
    }

    public ImportPreviewResponse previewReceipt(String inn, MultipartFile file) {
        Company company = company(inn);
        byte[] content = read(file);
        MlCoreApi.ReceiptResponse r = client.importReceipt(file.getOriginalFilename(), content,
                file.getContentType(), company.getCompanyCode());
        return new ImportPreviewResponse(SOURCE, inn, List.of(toRow(r.row())), Map.of(),
                r.warnings() == null ? List.of() : r.warnings());
    }

    private static ImportPreviewResponse.Row toRow(MlCoreApi.ImportRow row) {
        return new ImportPreviewResponse.Row(row.rowNumber(),
                row.operation() == null ? Map.of() : row.operation(),
                row.missingFields() == null ? List.of() : row.missingFields(),
                row.warnings() == null ? List.of() : row.warnings(),
                row.readyForReview() == null || row.readyForReview());
    }

    // ------------------------------------------------------------------ confirm

    /**
     * Saves rows the user reviewed. The company is always the one from the path (any company id/INN inside a row is
     * ignored), rows without {@code operation_id} get a deterministic one so a repeated confirm is idempotent.
     */
    public ImportResult confirm(String inn, List<OperationDto> rows) {
        List<OperationDto> valid = new ArrayList<>();
        int invalid = 0;
        for (OperationDto dto : rows == null ? List.<OperationDto>of() : rows) {
            if (dto == null || dto.getOperationDate() == null || dto.getDirection() == null
                    || dto.getAmount() == null || dto.getAmount().signum() <= 0) {
                invalid++;
                continue;
            }
            OperationDto copy = new OperationDto(null, inn, operationId(inn, dto), dto.getOperationDate(),
                    dto.getAmount(), dto.getDirection(), dto.getCounterpartyInn(), dto.getCounterpartyName(),
                    dto.getPaymentPurpose(), dto.getBalanceEod());
            valid.add(copy);
        }
        ImportResult part = operationImportService.upsertBatch(inn, OperationSource.CSV, valid);
        ImportResult result = new ImportResult(part.getInserted(), part.getDuplicates(),
                part.getInvalid() + invalid, part.getSkipped());
        if (result.getInserted() > 0) {
            forecastService.onDataChanged(inn, "Импорт через ML Core (проверено пользователем): +"
                    + result.getInserted() + ", дублей " + result.getDuplicates());
        }
        return result;
    }

    static String operationId(String inn, OperationDto dto) {
        if (dto.getOperationId() != null && !dto.getOperationId().isBlank()) {
            return dto.getOperationId().trim();
        }
        String key = String.join("|", inn, String.valueOf(dto.getOperationDate()),
                (dto.getDirection() == Direction.INCOME ? "I" : "E"),
                dto.getAmount().stripTrailingZeros().toPlainString(),
                nz(dto.getCounterpartyInn()), nz(dto.getCounterpartyName()), nz(dto.getPaymentPurpose()));
        return "mlc-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ helpers

    private Company company(String inn) {
        return companyRepository.findById(inn)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found: " + inn));
    }

    private static byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File is too large (max " + MAX_FILE_BYTES / (1024 * 1024) + " MB)");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read the uploaded file");
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
