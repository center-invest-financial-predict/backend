package com.example.demo.controller;

import com.example.demo.dto.forecast.ForecastSeriesResponse;
import com.example.demo.dto.forecast.ScenarioDto;
import com.example.demo.service.CompanyAccessService;
import com.example.demo.service.ForecastScenarioService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/companies/{inn}/scenarios")
@RequiredArgsConstructor
public class ForecastScenarioController {

    private final CompanyAccessService companyAccessService;
    private final ForecastScenarioService forecastScenarioService;

    @GetMapping
    @Operation(summary = "Saved scenarios for current user")
    public ResponseEntity<List<ScenarioDto>> list(@PathVariable String inn, Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(forecastScenarioService.list(authentication.getName(), inn));
    }

    @PostMapping
    @Operation(summary = "Save scenario conditions")
    public ResponseEntity<ScenarioDto> create(
            @PathVariable String inn,
            @Valid @RequestBody ScenarioDto.CreateRequest request,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(forecastScenarioService.create(authentication.getName(), inn, request));
    }

    @GetMapping("/examples")
    @Operation(summary = "Ready-made scenario examples (stub)")
    public ResponseEntity<List<ScenarioDto.ExampleDto>> examples(
            @PathVariable String inn,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(forecastScenarioService.examples(inn));
    }

    @PostMapping("/preview")
    @Operation(summary = "Preview forecast for conditions without saving (stub ML)")
    public ResponseEntity<ForecastSeriesResponse> preview(
            @PathVariable String inn,
            @RequestBody ScenarioDto.PreviewRequest request,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(forecastScenarioService.preview(inn, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScenarioDto> get(
            @PathVariable String inn,
            @PathVariable Long id,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(forecastScenarioService.get(authentication.getName(), inn, id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ScenarioDto> update(
            @PathVariable String inn,
            @PathVariable Long id,
            @RequestBody ScenarioDto.UpdateRequest request,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(forecastScenarioService.update(authentication.getName(), inn, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String inn,
            @PathVariable Long id,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        forecastScenarioService.delete(authentication.getName(), inn, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/copy")
    public ResponseEntity<ScenarioDto> copy(
            @PathVariable String inn,
            @PathVariable Long id,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(forecastScenarioService.copy(authentication.getName(), inn, id));
    }
}
