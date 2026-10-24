package com.example.demo.controller;

import com.example.demo.dto.forecast.*;
import com.example.demo.service.CashForecastService;
import com.example.demo.service.CompanyAccessService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/companies/{inn}")
@RequiredArgsConstructor
public class CashForecastController {

    private final CompanyAccessService companyAccessService;
    private final CashForecastService cashForecastService;

    @GetMapping("/dashboard")
    @Operation(summary = "Today overview KPIs (ML fields are stubbed)")
    public ResponseEntity<DashboardResponse> dashboard(@PathVariable String inn, Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.dashboard(inn));
    }

    @GetMapping("/alerts")
    @Operation(summary = "Attention alerts (stub ML)")
    public ResponseEntity<List<AlertDto>> alerts(@PathVariable String inn, Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.alerts(inn));
    }

    @GetMapping("/recommendations")
    @Operation(summary = "Action recommendations (stub ML)")
    public ResponseEntity<List<RecommendationDto>> recommendations(@PathVariable String inn, Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.recommendations(inn));
    }

    @GetMapping("/cashflow/weekly")
    @Operation(summary = "Money by weeks")
    public ResponseEntity<WeeklyCashflowDto> weekly(@PathVariable String inn, Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.weekly(inn));
    }

    @GetMapping("/cashflow/by-category")
    @Operation(summary = "Expense breakdown by category (categories stubbed)")
    public ResponseEntity<CategoryCashflowDto> byCategory(@PathVariable String inn, Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.byCategory(inn));
    }

    @GetMapping("/payments/upcoming")
    @Operation(summary = "Upcoming payments from events (stub if empty)")
    public ResponseEntity<List<CashEventDto>> upcoming(@PathVariable String inn, Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.upcomingPayments(inn));
    }

    @GetMapping("/forecast")
    @Operation(summary = "30-day balance forecast series (stub ML)")
    public ResponseEntity<ForecastSeriesResponse> forecast(
            @PathVariable String inn,
            @RequestParam(defaultValue = "30") int horizonDays,
            @RequestParam(required = false) Long scenarioId,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.forecast(inn, horizonDays, scenarioId));
    }

    @GetMapping("/forecast/day")
    @Operation(summary = "Forecast day drill-down (stub ML)")
    public ResponseEntity<ForecastDayResponse> forecastDay(
            @PathVariable String inn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.forecastDay(inn, date));
    }

    @GetMapping("/plan-fact")
    @Operation(summary = "Plan vs fact (plan side stubbed)")
    public ResponseEntity<PlanFactResponse> planFact(
            @PathVariable String inn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.planFact(inn, from, to));
    }

    @GetMapping("/recalc-journal")
    @Operation(summary = "Recalculation journal")
    public ResponseEntity<List<RecalcJournalDto>> journal(
            @PathVariable String inn,
            @RequestParam(defaultValue = "20") int limit,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.journal(inn, limit));
    }

    @GetMapping("/sources")
    @Operation(summary = "Data sources status")
    public ResponseEntity<DataSourcesResponse> sources(@PathVariable String inn, Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.sources(inn));
    }

    @GetMapping("/search")
    @Operation(summary = "Search operations and events")
    public ResponseEntity<SearchResponse> search(
            @PathVariable String inn,
            @RequestParam String q,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.search(inn, q));
    }
}
