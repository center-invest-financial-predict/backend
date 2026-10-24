package com.example.demo.controller;

import com.example.demo.dto.forecast.ForecastSettingsDto;
import com.example.demo.service.CompanyAccessService;
import com.example.demo.service.ForecastSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/{inn}/forecast-settings")
@RequiredArgsConstructor
public class ForecastSettingsController {

    private final CompanyAccessService companyAccessService;
    private final ForecastSettingsService forecastSettingsService;

    @GetMapping
    @Operation(summary = "Forecast strategy settings")
    public ResponseEntity<ForecastSettingsDto> get(@PathVariable String inn, Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(forecastSettingsService.get(inn));
    }

    @PutMapping
    @Operation(summary = "Update forecast strategy settings")
    public ResponseEntity<ForecastSettingsDto> put(
            @PathVariable String inn,
            @RequestBody ForecastSettingsDto.UpdateRequest request,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(forecastSettingsService.upsert(inn, request));
    }
}
