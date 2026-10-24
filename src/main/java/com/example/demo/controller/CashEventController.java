package com.example.demo.controller;

import com.example.demo.dto.forecast.CalendarResponse;
import com.example.demo.dto.forecast.CashEventDto;
import com.example.demo.dto.forecast.CashEventImportDto;
import com.example.demo.dto.forecast.RegularOperationDto;
import com.example.demo.entity.CashEventStatus;
import com.example.demo.service.CashEventService;
import com.example.demo.service.CompanyAccessService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/companies/{inn}/events")
@RequiredArgsConstructor
public class CashEventController {

    private final CompanyAccessService companyAccessService;
    private final CashEventService cashEventService;

    @GetMapping
    @Operation(summary = "List cash events (expected/overdue/done)")
    public ResponseEntity<List<CashEventDto>> list(
            @PathVariable String inn,
            @RequestParam(required = false) CashEventStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashEventService.list(inn, status, from, to));
    }

    @PostMapping
    @Operation(summary = "Add expected payment/receipt to forecast")
    public ResponseEntity<CashEventDto> create(
            @PathVariable String inn,
            @Valid @RequestBody CashEventDto.CreateRequest request,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashEventService.create(inn, request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<CashEventDto> update(
            @PathVariable String inn,
            @PathVariable Long id,
            @RequestBody CashEventDto.UpdateRequest request,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashEventService.update(inn, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String inn,
            @PathVariable Long id,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        cashEventService.delete(inn, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/calendar")
    @Operation(summary = "Events calendar for a month (yyyy-MM)")
    public ResponseEntity<CalendarResponse> calendar(
            @PathVariable String inn,
            @RequestParam(required = false) String month,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        YearMonth ym = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
        return ResponseEntity.ok(cashEventService.calendar(inn, ym));
    }

    @GetMapping("/regular")
    @Operation(summary = "Regular operations detected from history (stub ML)")
    public ResponseEntity<List<RegularOperationDto>> regular(
            @PathVariable String inn,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashEventService.regular(inn));
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Parse a future payments CSV and show every line (with errors); nothing is saved")
    public ResponseEntity<CashEventImportDto.PreviewResponse> importPreview(
            @PathVariable String inn,
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) throws IOException {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashEventService.previewCsv(file));
    }

    @PostMapping("/import/confirm")
    @Operation(summary = "Save the (possibly edited) rows of the preview; invalid rows are reported, valid ones saved")
    public ResponseEntity<CashEventImportDto.ConfirmResponse> importConfirm(
            @PathVariable String inn,
            @Valid @RequestBody CashEventImportDto.ConfirmRequest request,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashEventService.confirmImport(inn, request.rows()));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import future payments CSV")
    public ResponseEntity<List<CashEventDto>> importCsv(
            @PathVariable String inn,
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) throws IOException {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashEventService.importCsv(inn, file));
    }
}
