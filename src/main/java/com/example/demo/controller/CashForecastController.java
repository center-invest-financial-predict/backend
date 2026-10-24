package com.example.demo.controller;

import com.example.demo.dto.forecast.*;
import com.example.demo.forecast.Condition;
import com.example.demo.service.CashForecastService;
import com.example.demo.service.CompanyAccessService;
import com.example.demo.service.ForecastScenarioService;
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
    private final ForecastScenarioService scenarioService;

    @GetMapping("/dashboard")
    @Operation(summary = "Overview KPIs from the current forecast (balance, minimum, gap date/amount/probability)")
    public ResponseEntity<DashboardResponse> dashboard(@PathVariable String inn, Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.dashboard(inn));
    }

    @GetMapping("/alerts")
    @Operation(summary = "Attention alerts with date and amount of the shortfall")
    public ResponseEntity<List<AlertDto>> alerts(@PathVariable String inn, Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.alerts(inn));
    }

    @GetMapping("/recommendations")
    @Operation(summary = "Recommendations verified by recalculating the forecast")
    public ResponseEntity<List<RecommendationDto>> recommendations(@PathVariable String inn, Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.recommendations(inn));
    }

    @GetMapping("/cashflow/weekly")
    @Operation(summary = "Money by weeks: period=past (default, 4 weeks of data) or forecast (next 4 weeks)")
    public ResponseEntity<WeeklyCashflowDto> weekly(
            @PathVariable String inn,
            @RequestParam(defaultValue = "past") String period,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.weekly(inn, period));
    }

    @GetMapping("/cashflow/by-category")
    @Operation(summary = "Breakdown by rule-based categories (default: expenses, last 30 days of data)")
    public ResponseEntity<CategoryCashflowDto> byCategory(
            @PathVariable String inn,
            @RequestParam(defaultValue = "expense") String direction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.byCategory(inn, direction, from, to));
    }

    @GetMapping("/payments/upcoming")
    @Operation(summary = "Upcoming payments and receipts of the forecast: events and detected regular payments")
    public ResponseEntity<List<UpcomingPaymentDto>> upcoming(
            @PathVariable String inn,
            @RequestParam(defaultValue = "100") int limit,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(cashForecastService.upcomingPayments(inn, limit));
    }

    @GetMapping("/forecast")
    @Operation(summary = "Balance forecast; with scenarioId the saved scenario conditions are applied")
    public ResponseEntity<ForecastSeriesResponse> forecast(
            @PathVariable String inn,
            @RequestParam(defaultValue = "30") int horizonDays,
            @RequestParam(required = false) Long scenarioId,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        List<Condition> conditions = scenarioId == null
                ? List.of() : scenarioService.conditions(authentication.getName(), inn, scenarioId);
        return ResponseEntity.ok(cashForecastService.forecast(inn, horizonDays, conditions, scenarioId));
    }

    @GetMapping("/forecast/day")
    @Operation(summary = "Day drill-down: what the day consists of and why")
    public ResponseEntity<ForecastDayResponse> forecastDay(
            @PathVariable String inn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long scenarioId,
            Authentication authentication
    ) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        List<Condition> conditions = scenarioId == null
                ? List.of() : scenarioService.conditions(authentication.getName(), inn, scenarioId);
        return ResponseEntity.ok(cashForecastService.forecastDay(inn, date, conditions, scenarioId));
    }

    @GetMapping("/plan-fact")
    @Operation(summary = "Fact balances by day; plan side is not stored yet (planAvailable=false)")
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
    @Operation(summary = "Recalculation journal: what changed and how the forecast moved")
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
