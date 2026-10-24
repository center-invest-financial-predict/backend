package com.example.demo.controller;

import com.example.demo.dto.operation.ImportResult;
import com.example.demo.dto.operation.OperationResponse;
import com.example.demo.dto.operation.SyncResponse;
import com.example.demo.entity.Operation;
import com.example.demo.entity.OperationSource;
import com.example.demo.repository.CompanySourceRepository;
import com.example.demo.repository.OperationRepository;
import com.example.demo.service.SourceFilter;
import com.example.demo.service.CompanyAccessService;
import com.example.demo.service.CsvImportService;
import com.example.demo.service.IntegrationSyncService;
import com.example.demo.util.PageableSanitizer;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Set;

@RestController
@RequestMapping("/api/companies/{inn}")
@RequiredArgsConstructor
public class CompanyOperationsController {

    private final CompanyAccessService companyAccessService;
    private final CsvImportService csvImportService;
    private final IntegrationSyncService integrationSyncService;
    private final OperationRepository operationRepository;
    private final CompanySourceRepository companySourceRepository;

    @PostMapping(value = "/operations/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResult> uploadCsv(
            @PathVariable String inn,
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) throws IOException {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(csvImportService.importForCompany(authentication.getName(), inn, file));
    }

    @PostMapping("/sync")
    @io.swagger.v3.oas.annotations.Operation(summary = "Pull operations from the switched-on external sources "
            + "(1C, Gos_service); source=... restricts it to one. The result tells what happened with each source.")
    public ResponseEntity<SyncResponse> sync(
            @PathVariable String inn,
            @RequestParam(required = false) String source,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(integrationSyncService.syncInn(inn, source));
    }

    @GetMapping("/operations")
    public ResponseEntity<Page<OperationResponse>> listOperations(
            @PathVariable String inn,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @ParameterObject
            @PageableDefault(size = 50, sort = "operationDate", direction = Sort.Direction.DESC)
            Pageable pageable,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        OperationSource sourceFilter = source == null || source.isBlank() ? null : OperationSource.from(source);
        Pageable safePageable = PageableSanitizer.sanitize(pageable);
        // Without an explicit source the list shows what the forecast is built on: switched-off sources are hidden.
        Set<OperationSource> disabled = sourceFilter == null
                ? SourceFilter.disabled(companySourceRepository, inn) : Set.of();
        Page<Operation> found = disabled.isEmpty()
                ? operationRepository.findFiltered(inn, sourceFilter, from, to, safePageable)
                : operationRepository.findFilteredExcluding(inn, disabled, from, to, safePageable);
        return ResponseEntity.ok(found.map(OperationResponse::from));
    }
}
