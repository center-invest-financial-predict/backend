package com.example.demo.service;

import com.example.demo.dto.forecast.*;
import com.example.demo.dto.operation.OperationResponse;
import com.example.demo.entity.CashEvent;
import com.example.demo.entity.CashEventStatus;
import com.example.demo.entity.Direction;
import com.example.demo.entity.Operation;
import com.example.demo.entity.OperationSource;
import com.example.demo.exception.InsufficientHistoryException;
import com.example.demo.forecast.CategoryClassifier;
import com.example.demo.forecast.Condition;
import com.example.demo.forecast.ForecastService;
import com.example.demo.forecast.ForecastTypes.DayItem;
import com.example.demo.forecast.ForecastTypes.DayPoint;
import com.example.demo.forecast.ForecastTypes.Inputs;
import com.example.demo.forecast.ForecastTypes.Run;
import com.example.demo.forecast.Money;
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
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Read model for all forecast screens. Nothing here is computed independently: dashboard, chart, day drill-down,
 * alerts, recommendations and upcoming payments are all derived from the cached {@link Run} of {@link ForecastService}.
 */
@Service
@RequiredArgsConstructor
public class CashForecastService {

    private static final int HISTORY_POINTS = 30;
    private static final double RISK_ALERT_PROBABILITY = 0.20;
    private static final Set<String> MANDATORY = Set.of(
            CategoryClassifier.TAXES, CategoryClassifier.SALARY, CategoryClassifier.CREDIT, CategoryClassifier.RENT);
    private static final Set<String> NOT_MOVABLE = MANDATORY;

    private final OperationRepository operationRepository;
    private final CashEventRepository cashEventRepository;
    private final ForecastService forecastService;
    private final ForecastSettingsService forecastSettingsService;
    private final RecalcJournalService recalcJournalService;
    private final CategoryClassifier classifier;

    // ---------------------------------------------------------------- dashboard

    @Transactional(readOnly = true)
    public DashboardResponse dashboard(String inn) {
        Run run = forecastService.baseline(inn);
        LocalDate asOf = run.asOf();

        BigDecimal weeklyIncome = nz(operationRepository.sumIncome(inn, asOf.minusDays(6), asOf));
        BigDecimal weeklyExpense = nz(operationRepository.sumExpense(inn, asOf.minusDays(6), asOf));
        BigDecimal prevIncome = nz(operationRepository.sumIncome(inn, asOf.minusDays(13), asOf.minusDays(7)));
        BigDecimal prevExpense = nz(operationRepository.sumExpense(inn, asOf.minusDays(13), asOf.minusDays(7)));

        return new DashboardResponse(
                inn,
                asOf,
                run.startBalance(),
                weeklyIncome.subtract(weeklyExpense),
                run.minBalance(),
                run.minBalanceDate(),
                BigDecimal.valueOf(run.probability()),
                run.firstGap() == null ? null : run.firstGap().date(),
                run.firstGap() == null ? null : run.firstGap().deficit(),
                run.p10Gap() == null ? null : run.p10Gap().date(),
                run.p10Gap() == null ? null : run.p10Gap().deficit(),
                weeklyIncome,
                pctChange(weeklyIncome, prevIncome),
                weeklyExpense,
                pctChange(weeklyExpense, prevExpense),
                run.totalInflow(),
                run.totalOutflow(),
                run.points().stream().map(DayPoint::balance).toList(),
                run.calculatedAt(),
                run.runId(),
                run.historyDays(),
                run.warnings(),
                run.source(),
                run.note()
        );
    }

    // ---------------------------------------------------------------- alerts

    @Transactional(readOnly = true)
    public List<AlertDto> alerts(String inn) {
        Run base = forecastService.baseline(inn);
        ForecastSettingsDto settings = forecastSettingsService.get(inn);
        BigDecimal threshold = settings.minBalance() == null ? BigDecimal.ZERO : settings.minBalance();
        List<AlertDto> out = new ArrayList<>();

        if (base.firstGap() != null) {
            out.add(new AlertDto("gap", "GAP", "critical",
                    "Кассовый разрыв " + base.firstGap().date(),
                    "По базовому прогнозу " + base.firstGap().date() + " на счёте не хватит "
                            + Money.rub(base.firstGap().deficit()) + ". Минимальный остаток за период — "
                            + Money.rub(base.minBalance()) + " (" + base.minBalanceDate() + "). Вероятность разрыва — "
                            + Money.pct(base.probability()) + ".",
                    base.firstGap().date(), base.firstGap().deficit(), "Открыть прогноз", "forecast"));
        } else if (ruleEnabled(settings, "gapRisk")
                && (base.probability() >= RISK_ALERT_PROBABILITY || base.p10Gap() != null)) {
            Run.class.getName();
            var g = base.p10Gap();
            out.add(new AlertDto("risk", "RISK", "warning",
                    "Риск кассового разрыва: " + Money.pct(base.probability()),
                    "Базовый прогноз без разрыва, но в " + Money.pct(base.probability())
                            + " расчётных сценариев баланс уходит ниже нуля"
                            + (g == null ? "." : "; при неблагоприятном развитии с " + g.date() + " не хватит до "
                            + Money.rub(g.deficit()) + "."),
                    g == null ? base.minBalanceDate() : g.date(), g == null ? null : g.deficit(),
                    "Открыть прогноз", "forecast"));
        }

        if (base.firstGap() == null && ruleEnabled(settings, "belowMinBalance")
                && threshold.signum() > 0 && base.minBalance().compareTo(threshold) < 0) {
            out.add(new AlertDto("below-min", "BELOW_MIN", "warning",
                    "Остаток опустится ниже порога " + Money.rub(threshold),
                    "Минимальный остаток " + Money.rub(base.minBalance()) + " ожидается " + base.minBalanceDate() + ".",
                    base.minBalanceDate(), threshold.subtract(base.minBalance()), "Настроить порог", "settings"));
        }

        if (ruleEnabled(settings, "gapRisk")) {
            Run stress = forecastService.stress(inn, base.horizon());
            if (stress.firstGap() != null && base.firstGap() == null) {
                out.add(new AlertDto("stress", "STRESS", "warning",
                        "При задержке оплат на " + Condition.STRESS_DELAY_DAYS + " дн. будет разрыв",
                        "Если клиенты заплатят на " + Condition.STRESS_DELAY_DAYS + " дней позже, баланс уйдёт ниже нуля "
                                + stress.firstGap().date() + ": не хватит " + Money.rub(stress.firstGap().deficit()) + ".",
                        stress.firstGap().date(), stress.firstGap().deficit(), "Создать сценарий", "scenarios"));
            }
        }

        LocalDate asOf = base.asOf();
        List<CashEvent> overdue = cashEventRepository
                .findByCompanyInnAndEventDateGreaterThanEqualAndStatusInOrderByEventDateAscIdAsc(
                        inn, asOf.minusDays(60), List.of(CashEventStatus.EXPECTED, CashEventStatus.OVERDUE))
                .stream().filter(e -> !e.getEventDate().isAfter(asOf)).limit(5).toList();
        for (CashEvent e : overdue) {
            boolean income = e.getDirection() == Direction.INCOME;
            out.add(new AlertDto("overdue-" + e.getId(), "OVERDUE", income ? "warning" : "info",
                    (income ? "Не пришло ожидаемое поступление: " : "Не оплачен ожидаемый платёж: ") + e.getTitle(),
                    Money.rub(e.getAmount()) + ", ожидалось " + e.getEventDate() + ". "
                            + (income ? "В прогноз эта сумма не включена." : "В прогнозе учтён как оплата в первый день."),
                    e.getEventDate(), e.getAmount(), "Открыть события", "events"));
        }

        long lag = ChronoUnit.DAYS.between(asOf, LocalDate.now());
        if (lag > 3) {
            out.add(new AlertDto("stale-data", "DATA", "info",
                    "Данные не обновлялись " + lag + " дн.",
                    "Последняя операция — " + asOf + ". Прогноз строится от этой даты; загрузите свежую выписку или "
                            + "синхронизируйте источники.",
                    asOf, null, "Источники данных", "sources"));
        }
        return out;
    }

    // ---------------------------------------------------------------- recommendations

    @Transactional(readOnly = true)
    public List<RecommendationDto> recommendations(String inn) {
        Run base = forecastService.baseline(inn);
        BigDecimal threshold = Optional.ofNullable(forecastSettingsService.get(inn).minBalance()).orElse(BigDecimal.ZERO);
        double before = exposure(base, threshold);
        if (before <= 0) {
            return List.of();
        }
        LocalDate focus = base.firstGap() != null ? base.firstGap().date()
                : base.p10Gap() != null ? base.p10Gap().date() : base.minBalanceDate();
        List<RecommendationDto> out = new ArrayList<>();

        // 1) postpone the biggest optional payments up to the problem date
        Map<String, DayItem> candidates = new LinkedHashMap<>();
        for (DayPoint p : base.points()) {
            if (p.date().isAfter(focus)) {
                break;
            }
            for (DayItem it : p.items()) {
                if ("EXPENSE".equals(it.direction()) && !NOT_MOVABLE.contains(it.category())
                        && !"scenario".equals(it.source())) {
                    candidates.merge(it.title(), it, (a, b) -> a.amount().compareTo(b.amount()) >= 0 ? a : b);
                }
            }
        }
        candidates.values().stream()
                .sorted(Comparator.comparing(DayItem::amount).reversed())
                .limit(3)
                .forEach(it -> {
                    Condition c = shift(it.title(), 7);
                    Run after = forecastService.run(inn, base.horizon(), List.of(c));
                    double impact = before - exposure(after, threshold);
                    if (impact > 0) {
                        out.add(new RecommendationDto("postpone-" + Math.abs(it.title().hashCode()),
                                "Перенести платёж «" + it.title() + "» на 7 дней",
                                "Платёж " + Money.rub(it.amount()) + " сдвигается на неделю позже; прогноз пересчитан.",
                                Money.bd(impact), Money.bd(before), Money.bd(before - impact),
                                "Применить сценарий", Condition.toMaps(List.of(c))));
                    }
                });

        // 2) pull forward the biggest expected receipt entered as an event
        base.points().stream().flatMap(p -> p.items().stream())
                .filter(it -> "INCOME".equals(it.direction()) && "event".equals(it.source()))
                .max(Comparator.comparing(DayItem::amount))
                .ifPresent(it -> {
                    Condition c = shift(it.title(), -7);
                    Run after = forecastService.run(inn, base.horizon(), List.of(c));
                    double impact = before - exposure(after, threshold);
                    if (impact > 0) {
                        out.add(new RecommendationDto("accelerate-" + Math.abs(it.title().hashCode()),
                                "Ускорить поступление «" + it.title() + "» на 7 дней",
                                "Если оплата " + Money.rub(it.amount()) + " придёт на неделю раньше, прогноз улучшится.",
                                Money.bd(impact), Money.bd(before), Money.bd(before - impact),
                                "Применить сценарий", Condition.toMaps(List.of(c))));
                    }
                });

        // 3) short-term financing sized to the verified exposure
        double sized = Math.ceil(before * 1.1 / 50_000.0) * 50_000.0;
        int offset = (int) Math.max(1, ChronoUnit.DAYS.between(base.asOf(), focus) - 1);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("type", "extra_income");
        extra.put("amount", BigDecimal.valueOf(sized));
        extra.put("dayOffset", offset);
        Run after = forecastService.run(inn, base.horizon(), Condition.parse(List.of(extra)));
        double impact = before - exposure(after, threshold);
        if (impact > 0) {
            out.add(new RecommendationDto("financing",
                    "Подготовить краткосрочное финансирование на " + Money.rub(sized),
                    "Овердрафт или кредитная линия на эту сумму к " + base.asOf().plusDays(offset)
                            + " закрывает расчётный дефицит.",
                    Money.bd(impact), Money.bd(before), Money.bd(before - impact),
                    "Применить сценарий", List.of(extra)));
        }
        out.sort(Comparator.comparing(RecommendationDto::impactAmount).reversed());
        return out;
    }

    private static Condition shift(String title, int days) {
        return Condition.parse(List.of(Map.of("type", "shift_payment", "match", title, "days", days))).get(0);
    }

    private static double exposure(Run run, BigDecimal threshold) {
        double det = run.deepestGap() == null ? 0 : run.deepestGap().deficit().doubleValue();
        double p10 = run.p10Gap() == null ? 0 : run.p10Gap().deficit().doubleValue();
        double belowMin = threshold.signum() > 0 && run.minBalance().compareTo(threshold) < 0
                ? threshold.subtract(run.minBalance()).doubleValue() : 0;
        return Math.max(det, Math.max(p10, belowMin));
    }

    private static boolean ruleEnabled(ForecastSettingsDto settings, String key) {
        return !(settings.notifyRules() instanceof Map<?, ?> m) || !Boolean.FALSE.equals(m.get(key));
    }

    // ---------------------------------------------------------------- cash flow blocks

    @Transactional(readOnly = true)
    public WeeklyCashflowDto weekly(String inn, String period) {
        boolean forecast = "forecast".equalsIgnoreCase(period);
        if (period != null && !period.isBlank() && !forecast && !"past".equalsIgnoreCase(period)) {
            throw new IllegalArgumentException("period must be 'past' or 'forecast'");
        }
        LocalDate asOf = forecastService.asOf(inn);
        List<WeeklyCashflowDto.WeekBucket> weeks = new ArrayList<>();
        if (forecast) {
            Run run = forecastService.baseline(inn);
            for (int w = 0; w < 4; w++) {
                LocalDate ws = asOf.plusDays(1 + 7L * w);
                LocalDate we = ws.plusDays(6);
                BigDecimal in = BigDecimal.ZERO;
                BigDecimal out = BigDecimal.ZERO;
                for (DayPoint p : run.points()) {
                    if (!p.date().isBefore(ws) && !p.date().isAfter(we)) {
                        in = in.add(p.inflow());
                        out = out.add(p.outflow());
                    }
                }
                weeks.add(new WeeklyCashflowDto.WeekBucket(ws, we, in, out));
            }
            return new WeeklyCashflowDto("forecast", asOf, weeks);
        }
        LocalDate start = asOf.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(3);
        Map<LocalDate, BigDecimal[]> buckets = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) {
            buckets.put(start.plusWeeks(i), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        }
        for (Operation op : operationRepository.findByCompanyInnAndDateRange(inn, start, asOf)) {
            BigDecimal[] b = buckets.get(op.getOperationDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            if (b != null) {
                int idx = op.getDirection() == Direction.INCOME ? 0 : 1;
                b[idx] = b[idx].add(op.getAmount());
            }
        }
        buckets.forEach((ws, v) -> weeks.add(new WeeklyCashflowDto.WeekBucket(ws, ws.plusDays(6), v[0], v[1])));
        return new WeeklyCashflowDto("past", asOf, weeks);
    }

    @Transactional(readOnly = true)
    public CategoryCashflowDto byCategory(String inn, String direction, LocalDate from, LocalDate to) {
        Direction dir = direction == null || direction.isBlank() || "expense".equalsIgnoreCase(direction)
                ? Direction.EXPENSE : Direction.INCOME;
        if (direction != null && !direction.isBlank()
                && !"expense".equalsIgnoreCase(direction) && !"income".equalsIgnoreCase(direction)) {
            throw new IllegalArgumentException("direction must be 'expense' or 'income'");
        }
        LocalDate end = to == null ? forecastService.asOf(inn) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (Operation op : operationRepository.findByCompanyInnAndDateRange(inn, start, end)) {
            if (op.getDirection() == dir) {
                map.merge(classifier.classify(op), op.getAmount(), BigDecimal::add);
            }
        }
        BigDecimal sum = map.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<CategoryCashflowDto.CategoryBucket> buckets = map.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> new CategoryCashflowDto.CategoryBucket(e.getKey(), e.getValue(),
                        sum.signum() == 0 ? BigDecimal.ZERO
                                : e.getValue().multiply(BigDecimal.valueOf(100)).divide(sum, 1, RoundingMode.HALF_UP)))
                .toList();
        return new CategoryCashflowDto(dir == Direction.EXPENSE ? "expense" : "income", start, end, sum, buckets);
    }

    @Transactional(readOnly = true)
    public List<UpcomingPaymentDto> upcomingPayments(String inn, int limit) {
        Run run = forecastService.baseline(inn);
        List<UpcomingPaymentDto> out = new ArrayList<>();
        for (DayPoint p : run.points()) {
            for (DayItem it : p.items()) {
                boolean expense = "EXPENSE".equals(it.direction());
                out.add(new UpcomingPaymentDto(p.date(), it.title(), it.direction(), it.amount(), it.category(),
                        it.source(), expense && MANDATORY.contains(it.category()), it.eventId()));
            }
        }
        return out.stream().limit(Math.max(1, Math.min(limit, 500))).toList();
    }

    // ---------------------------------------------------------------- forecast series

    @Transactional(readOnly = true)
    public ForecastSeriesResponse forecast(String inn, int horizonDays, List<Condition> conditions, Long scenarioId) {
        Run run = forecastService.run(inn, horizonDays, conditions);
        return toSeries(run, scenarioId, history(inn, run));
    }

    @Transactional(readOnly = true)
    public ForecastDayResponse forecastDay(String inn, LocalDate date, List<Condition> conditions, Long scenarioId) {
        Run run = forecastService.run(inn, ForecastService.DEFAULT_HORIZON, conditions);
        int idx = (int) ChronoUnit.DAYS.between(run.asOf(), date) - 1;
        if (idx < 0 || idx >= run.points().size()) {
            throw new IllegalArgumentException("date must be within " + run.asOf().plusDays(1) + ".."
                    + run.asOf().plusDays(run.horizon()));
        }
        DayPoint p = run.points().get(idx);
        BigDecimal start = idx == 0 ? run.startBalance() : run.points().get(idx - 1).balance();
        return new ForecastDayResponse(
                p.date(), start, p.inflow(), p.outflow(), p.balance(), p.otherInflow(), p.otherOutflow(),
                p.balance().signum() < 0 ? p.balance() : null, p.p10(), p.p90(),
                p.items().stream()
                        .map(i -> new ForecastDayResponse.DayEvent(i.title(), i.source(), i.amount(), i.direction(),
                                i.category(), i.basis()))
                        .toList(),
                scenarioId);
    }

    public ForecastSeriesResponse toSeries(Run run, Long scenarioId, List<ForecastSeriesResponse.ActualPoint> history) {
        List<ForecastSeriesResponse.DayPoint> points = run.points().stream()
                .map(p -> new ForecastSeriesResponse.DayPoint(p.date(), p.balance(),
                        p.balance().signum() < 0 ? p.balance() : null, p.inflow(), p.outflow(), p.p10(), p.p90()))
                .toList();
        return new ForecastSeriesResponse(
                run.inn(), run.horizon(), scenarioId, run.calculatedAt(), run.runId(), run.asOf(), run.startBalance(),
                points, history, summary(run), Condition.toMaps(run.conditions()),
                run.conditions().stream().map(Condition::describe).toList(), run.warnings(),
                run.source(), run.note());
    }

    public ForecastSeriesResponse.Summary summary(Run run) {
        return new ForecastSeriesResponse.Summary(
                run.endBalance(), run.minBalance(), run.minBalanceDate(),
                run.firstGap() == null ? null : run.firstGap().date(),
                run.firstGap() == null ? null : run.firstGap().deficit(),
                run.p10Gap() == null ? null : run.p10Gap().date(),
                run.p10Gap() == null ? null : run.p10Gap().deficit(),
                BigDecimal.valueOf(run.probability()), run.totalInflow(), run.totalOutflow());
    }

    /** Daily end balances rolled back from the opening balance, so history joins the forecast without a jump. */
    private List<ForecastSeriesResponse.ActualPoint> history(String inn, Run run) {
        return new ArrayList<>(dailyBalances(inn, run, run.asOf().minusDays(HISTORY_POINTS - 1L)).entrySet().stream()
                .map(e -> new ForecastSeriesResponse.ActualPoint(e.getKey(), e.getValue()))
                .toList());
    }

    private TreeMap<LocalDate, BigDecimal> dailyBalances(String inn, Run run, LocalDate from) {
        Map<LocalDate, BigDecimal> net = new HashMap<>();
        for (Operation op : operationRepository.findByCompanyInnAndDateRange(inn, from, run.asOf())) {
            BigDecimal v = op.getDirection() == Direction.INCOME ? op.getAmount() : op.getAmount().negate();
            net.merge(op.getOperationDate(), v, BigDecimal::add);
        }
        TreeMap<LocalDate, BigDecimal> balances = new TreeMap<>();
        BigDecimal bal = run.startBalance();
        for (LocalDate d = run.asOf(); !d.isBefore(from); d = d.minusDays(1)) {
            balances.put(d, bal);
            bal = bal.subtract(net.getOrDefault(d, BigDecimal.ZERO));
        }
        return balances;
    }

    // ---------------------------------------------------------------- plan / fact

    @Transactional(readOnly = true)
    public PlanFactResponse planFact(String inn, LocalDate from, LocalDate to) {
        Run run = forecastService.baseline(inn);
        LocalDate asOf = run.asOf();
        LocalDate t = to == null || to.isAfter(asOf) ? asOf : to;
        LocalDate f = from == null ? asOf.withDayOfMonth(1) : from;
        if (f.isAfter(t)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        TreeMap<LocalDate, BigDecimal> balances = dailyBalances(inn, run, f);
        List<PlanFactResponse.DayPoint> points = new ArrayList<>();
        balances.subMap(f, true, t, true).forEach((d, b) -> points.add(new PlanFactResponse.DayPoint(d, b, null)));

        List<PlanFactResponse.MissedItem> missed = cashEventRepository
                .findByCompanyInnAndEventDateBetweenOrderByEventDateAscIdAsc(inn, f, t).stream()
                .filter(e -> e.getStatus() != CashEventStatus.DONE && !e.getEventDate().isAfter(asOf))
                .map(e -> new PlanFactResponse.MissedItem(e.getTitle(), e.getEventDate(),
                        (int) ChronoUnit.DAYS.between(e.getEventDate(), asOf), e.getAmount(), e.getDirection().name()))
                .toList();
        return new PlanFactResponse(f, t, balances.get(t), null, null, points, missed, false);
    }

    // ---------------------------------------------------------------- misc

    @Transactional(readOnly = true)
    public List<RecalcJournalDto> journal(String inn, int limit) {
        return recalcJournalService.list(inn, limit);
    }

    @Transactional(readOnly = true)
    public DataSourcesResponse sources(String inn) {
        List<DataSourcesResponse.SourceStatus> list = new ArrayList<>();
        for (OperationSource src : OperationSource.values()) {
            long count = operationRepository.countByCompanyInnAndSource(inn, src);
            Optional<LocalDate> last = operationRepository.findMaxDateBySource(inn, src);
            list.add(new DataSourcesResponse.SourceStatus(src.getCode(), src.name(), count > 0,
                    last.orElse(null), count));
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

    /** Keeps the checked exception type visible for callers that map it to HTTP 422. */
    @SuppressWarnings("unused")
    private static void throwsInsufficientHistory() throws InsufficientHistoryException {
    }
}
