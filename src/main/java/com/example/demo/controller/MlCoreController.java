package com.example.demo.controller;

import com.example.demo.dto.mlcore.ImportConfirmRequest;
import com.example.demo.dto.mlcore.ImportPreviewResponse;
import com.example.demo.dto.mlcore.MlCoreForecastResponse;
import com.example.demo.dto.mlcore.MlCoreHealthResponse;
import com.example.demo.dto.operation.ImportResult;
import com.example.demo.service.CompanyAccessService;
import com.example.demo.service.MlCoreService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Endpoints backed by the "Cashflow ML Core" server (ml.core.url). */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MlCoreController {

    private final MlCoreService mlCoreService;
    private final CompanyAccessService companyAccessService;

    @GetMapping("/ml/core/health")
    @Operation(summary = "ML Core availability (UP / DEGRADED / DOWN / DISABLED), never fails")
    public ResponseEntity<MlCoreHealthResponse> health() {
        return ResponseEntity.ok(mlCoreService.health());
    }

    @GetMapping("/ml/core/companies/{inn}/forecast")
    @Operation(summary = "Raw ML Core forecast for a company (separate from the built-in forecast used on all screens)",
            description = "source=ml-core. While the server marks its forecast as stub (stub=true) these numbers are a "
                    + "demo replay, carry no probability and are NOT used by dashboard/chart/alerts/assistant.")
    public ResponseEntity<MlCoreForecastResponse> forecast(@PathVariable String inn, Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(mlCoreService.forecast(inn));
    }

    @PostMapping(value = "/companies/{inn}/operations/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Normalize an arbitrary statement table (csv/xlsx) with ML Core; nothing is saved")
    public ResponseEntity<ImportPreviewResponse> previewTable(
            @PathVariable String inn,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sheetName", required = false) String sheetName,
            Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(mlCoreService.previewTable(inn, file, sheetName));
    }

    @PostMapping(value = "/companies/{inn}/operations/import/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Recognize a receipt/document with ML Core into one proposed operation; nothing is saved")
    public ResponseEntity<ImportPreviewResponse> previewReceipt(
            @PathVariable String inn,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(mlCoreService.previewReceipt(inn, file));
    }

    @PostMapping("/companies/{inn}/operations/import/confirm")
    @Operation(summary = "Save rows reviewed by the user (from preview/receipt) and recalculate the forecast")
    public ResponseEntity<ImportResult> confirm(
            @PathVariable String inn,
            @Valid @RequestBody ImportConfirmRequest request,
            Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(mlCoreService.confirm(inn, request.operations()));
    }
}
