package com.example.demo.service;

import com.example.demo.dto.forecast.*;
import com.example.demo.dto.operation.OperationResponse;
import com.example.demo.entity.CashEventStatus;
import com.example.demo.entity.Direction;
import com.example.demo.entity.Operation;
import com.example.demo.entity.OperationSource;
import com.example.demo.repository.CashEventRepository;
import com.example.demo.repository.OperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CashForecastService {

    private final OperationRepository operationRepository;
    private final CashEventRepository cashEventRepository;
    private final MlForecastStubService stubService;
    private final RecalcJournalService recalcJournalService;

    @Transactional(readOnly = true)
    public DashboardResponse dashboard(String inn) {
        BigDecimal currentBalance = operationRepository
                .findFirstByCompanyInnAndBalanceEodIsNotNullOrderByOperationDateDescIdDesc(inn)
                .map(Operation::getBalanceEod)
                .orElse(BigDecimal.valueOf(400_000));

        LocalDate today = LocalDate.now();
        LocalDate weekAgo = today.minusDays(6);
        LocalDate prevWeekStart = today.minusDays(13);
        LocalDate prevWeekEnd = today.minusDays(7);

        BigDecimal weeklyIncome = nz(operationRepository.sumIncome(inn, weekAgo, today));
        BigDecimal weeklyExpense = nz(operationRepository.sumExpense(inn, weekAgo, today));
        BigDecimal prevIncome = nz(operationRepository.sumIncome(inn, prevWeekStart, prevWeekEnd));
        BigDecimal prevExpense = nz(operationRepository.sumExpense(inn, prevWeekStart, prevWeekEnd));

        BigDecimal balance7dAgo = currentBalance.subtract(weeklyIncome).add(weeklyExpense);
        BigDecimal change7d = currentBalance.subtract(balance7dAgo);

        MlForecastStubService.GapInfo gap = stubService.gapInfo(inn, currentBalance);

        return new DashboardResponse(
                inn,
                currentBalance,
                change7d,
                gap.minBalance30d(),
                gap.gapDate(),
                gap.gapProbability(),
                gap.gapDate(),
                gap.gapAmount(),
                weeklyIncome,
                pctChange(weeklyIncome, prevIncome),
                weeklyExpense,
                pctChange(weeklyExpense, prevExpense),
                stubService.sparkline(inn, currentBalance),
                stubService.calculatedAt(),
                true
        );
    }

    @Transactional(readOnly = true)
    public List<AlertDto> alerts(String inn) {
        BigDecimal bal = currentBalance(inn);
        return stubService.alerts(inn, stubService.gapInfo(inn, bal));
    }

    @Transactional(readOnly = true)
    public List<RecommendationDto> recommendations(String inn) {
        BigDecimal bal = currentBalance(inn);
        return stubService.recommendations(inn, stubService.gapInfo(inn, bal));
    }

    @Transactional(readOnly = true)
    public WeeklyCashflowDto weekly(String inn) {
        LocalDate start = LocalDate.now().minusWeeks(3).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = start.plusWeeks(4).minusDays(1);
        List<Operation> ops = operationRepository.findByCompanyInnAndDateRange(inn, start, end);
        if (ops.isEmpty()) {
            return stubService.stubWeekly(inn);
        }
        Map<LocalDate, WeeklyCashflowDto.WeekBucket> buckets = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) {
            LocalDate ws = start.plusWeeks(i);
            buckets.put(ws, new WeeklyCashflowDto.WeekBucket(ws, ws.plusDays(6), BigDecimal.ZERO, BigDecimal.ZERO));
        }
        for (Operation op : ops) {
            LocalDate ws = op.getOperationDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            WeeklyCashflowDto.WeekBucket b = buckets.get(ws);
            if (b == null) {
                continue;
            }
            if (op.getDirection() == Direction.INCOME) {
                buckets.put(ws, new WeeklyCashflowDto.WeekBucket(b.weekStart(), b.weekEnd(),
                        b.income().add(op.getAmount()), b.expense()));
            } else {
                buckets.put(ws, new WeeklyCashflowDto.WeekBucket(b.weekStart(), b.weekEnd(),
                        b.income(), b.expense().add(op.getAmount())));
            }
        }
        return new WeeklyCashflowDto(new ArrayList<>(buckets.values()), true);
    }

    @Transactional(readOnly = true)
    public CategoryCashflowDto byCategory(String inn) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(29);
        BigDecimal totalExpense = nz(operationRepository.sumExpense(inn, from, to));
        List<Operation> ops = operationRepository.findByCompanyInnAndDateRange(inn, from, to).stream()
                .filter(o -> o.getDirection() == Direction.EXPENSE)
                .toList();
        if (ops.isEmpty()) {
            return new CategoryCashflowDto(stubService.expenseCategories(inn, totalExpense), true);
        }
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (Operation op : ops) {
            String cat = stubService.categorize(op.getPaymentPurpose(), op.getCounterpartyName());
            map.merge(cat, op.getAmount(), BigDecimal::add);
        }
        BigDecimal sum = map.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<CategoryCashflowDto.CategoryBucket> buckets = map.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> new CategoryCashflowDto.CategoryBucket(
                        e.getKey(),
                        e.getValue(),
                        sum.signum() == 0 ? BigDecimal.ZERO
                                : e.getValue().multiply(BigDecimal.valueOf(100)).divide(sum, 1, RoundingMode.HALF_UP)
                ))
                .toList();
        return new CategoryCashflowDto(buckets, true);
    }

    @Transactional(readOnly = true)
    public List<CashEventDto> upcomingPayments(String inn) {
        List<CashEventDto> fromDb = cashEventRepository
                .findByCompanyInnAndEventDateGreaterThanEqualAndStatusInOrderByEventDateAscIdAsc(
                        inn,
                        LocalDate.now(),
                        List.of(CashEventStatus.EXPECTED, CashEventStatus.OVERDUE)
                )
                .stream()
                .map(CashEventDto::from)
                .toList();
        if (fromDb.isEmpty()) {
            return stubService.stubUpcomingPayments(inn);
        }
        return fromDb;
    }

    @Transactional(readOnly = true)
    public ForecastSeriesResponse forecast(String inn, int horizonDays, Long scenarioId) {
        return stubService.forecastSeries(inn, horizonDays, scenarioId, currentBalance(inn));
    }

    @Transactional(readOnly = true)
    public ForecastDayResponse forecastDay(String inn, LocalDate date) {
        return stubService.forecastDay(inn, date, currentBalance(inn));
    }

    @Transactional(readOnly = true)
    public PlanFactResponse planFact(String inn, LocalDate from, LocalDate to) {
        LocalDate f = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate t = to == null ? LocalDate.now() : to;
        List<Operation> ops = operationRepository.findByCompanyInnAndDateRange(inn, f, t);
        Map<LocalDate, BigDecimal> eod = new TreeMap<>();
        BigDecimal running = currentBalance(inn);
        // walk backwards-ish: prefer balance_eod when present
        for (Operation op : ops) {
            if (op.getBalanceEod() != null) {
                eod.put(op.getOperationDate(), op.getBalanceEod());
            }
        }
        List<PlanFactResponse.DayPoint> factPoints = new ArrayList<>();
        for (LocalDate d = f; !d.isAfter(t); d = d.plusDays(1)) {
            BigDecimal fact = eod.getOrDefault(d, running);
            factPoints.add(new PlanFactResponse.DayPoint(d, fact, null));
            running = fact;
        }
        return stubService.planFact(inn, f, t, factPoints);
    }

    @Transactional(readOnly = true)
    public List<RecalcJournalDto> journal(String inn, int limit) {
        return recalcJournalService.list(inn, limit);
    }

    @Transactional(readOnly = true)
    public DataSourcesResponse sources(String inn) {
        long total = operationRepository.countByCompanyInn(inn);
        List<DataSourcesResponse.SourceStatus> list = new ArrayList<>();
        for (OperationSource src : OperationSource.values()) {
            OptionalLocalDate last = OptionalLocalDate.of(operationRepository.findMaxDateBySource(inn, src));
            list.add(new DataSourcesResponse.SourceStatus(
                    src.getCode(),
                    src.name(),
                    last.present(),
                    last.date(),
                    total
            ));
        }
        return new DataSourcesResponse(inn, list);
    }

    @Transactional(readOnly = true)
    public SearchResponse search(String inn, String q) {
        String query = q == null ? "" : q.trim();
        if (query.isBlank()) {
            return new SearchResponse(query, List.of(), List.of());
        }
        List<OperationResponse> ops = operationRepository.search(inn, query, PageRequest.of(0, 50))
                .stream().map(OperationResponse::from).toList();
        List<CashEventDto> events = cashEventRepository.search(inn, query).stream()
                .limit(50)
                .map(CashEventDto::from)
                .toList();
        return new SearchResponse(query, ops, events);
    }

    private BigDecimal currentBalance(String inn) {
        return operationRepository
                .findFirstByCompanyInnAndBalanceEodIsNotNullOrderByOperationDateDescIdDesc(inn)
                .map(Operation::getBalanceEod)
                .orElse(BigDecimal.valueOf(400_000));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal pctChange(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.signum() == 0) {
            return current == null || current.signum() == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(100);
        }
        return current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous.abs(), 1, RoundingMode.HALF_UP);
    }

    private record OptionalLocalDate(boolean present, LocalDate date) {
        static OptionalLocalDate of(java.util.Optional<LocalDate> opt) {
            return opt.map(d -> new OptionalLocalDate(true, d)).orElse(new OptionalLocalDate(false, null));
        }
    }
}
