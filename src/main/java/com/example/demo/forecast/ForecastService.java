package com.example.demo.forecast;

import com.example.demo.entity.CashEvent;
import com.example.demo.entity.CashEventStatus;
import com.example.demo.entity.Direction;
import com.example.demo.entity.Operation;
import com.example.demo.entity.OperationSource;
import com.example.demo.exception.InsufficientHistoryException;
import com.example.demo.forecast.ForecastTypes.EventItem;
import com.example.demo.forecast.ForecastTypes.Inputs;
import com.example.demo.forecast.ForecastTypes.Residual;
import com.example.demo.forecast.ForecastTypes.Run;
import com.example.demo.repository.CashEventRepository;
import com.example.demo.repository.CompanySourceRepository;
import com.example.demo.repository.OperationRepository;
import com.example.demo.service.RecalcJournalService;
import com.example.demo.service.SourceFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single entry point for the cash forecast. Builds inputs from operations and expected events,
 * runs the {@link ForecastEngine} and caches the result per company so that every screen
 * (dashboard, chart, alerts, assistant) reads the very same calculation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ForecastService {

    public static final int DEFAULT_HORIZON = 30;
    public static final int MIN_HISTORY_DAYS = 14;

    private static final int HISTORY_WINDOW_DAYS = 365;
    private static final int RESIDUAL_WINDOW_DAYS = 56;
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.01");

    private final OperationRepository operationRepository;
    private final CashEventRepository cashEventRepository;
    private final RegularDetector regularDetector;
    private final ForecastEngine engine;
    private final RecalcJournalService journalService;
    private final ServerForecastSource serverSource;
    private final CompanySourceRepository companySourceRepository;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private static final class Cached {
        final Inputs inputs;
        final RegularDetector.Detection detection;
        final Instant createdAt = Instant.now();
        final Map<String, Run> runs = new ConcurrentHashMap<>();
        private ServerForecastSource.Outcome server;

        Cached(Inputs inputs, RegularDetector.Detection detection) {
            this.inputs = inputs;
            this.detection = detection;
        }

        /** The server is asked at most once per cached inputs (a failure is remembered until the TTL ends). */
        synchronized ServerForecastSource.Outcome server(ServerForecastSource source) {
            if (server == null) {
                server = source.fetch(inputs);
            }
            return server;
        }

        boolean expired() {
            return createdAt.plus(TTL).isBefore(Instant.now());
        }
    }

    // ---------------------------------------------------------------- public API

    public Run baseline(String inn) {
        return run(inn, DEFAULT_HORIZON, List.of());
    }

    public Run baseline(String inn, int horizon) {
        return run(inn, horizon, List.of());
    }

    /** Default stress test: all incoming payments are 14 days late. */
    public Run stress(String inn, int horizon) {
        return run(inn, horizon, List.of(Condition.delayAllIncome(Condition.STRESS_DELAY_DAYS)));
    }

    public Run run(String inn, int horizon, List<Condition> conditions) {
        Cached c = cached(inn);
        List<Condition> cs = conditions == null ? List.of() : conditions;
        String key = horizon + "|" + Condition.toMaps(cs);
        Run known = c.runs.get(key);
        if (known != null) {
            return known;
        }
        Run created = compute(c, cs, horizon);
        Run raced = c.runs.putIfAbsent(key, created);
        return raced != null ? raced : created;
    }

    private Run compute(Cached c, List<Condition> cs, int horizon) {
        Run builtin = engine.run(c.inputs, cs, horizon, ForecastEngine.DEFAULT_SIMULATIONS);
        if (!serverSource.active()) {
            return builtin;
        }
        if (!cs.isEmpty()) {
            return ServerForecastSource.withNote(builtin,
                    "Сценарий посчитан встроенным движком: сервер ML Core пока не считает пользовательские условия.");
        }
        ServerForecastSource.Outcome o = c.server(serverSource);
        if (o.result() == null) {
            return ServerForecastSource.withNote(builtin, o.note());
        }
        Run adopted;
        try {
            adopted = serverSource.adopt(builtin, o.result());
        } catch (RuntimeException e) {
            log.warn("ML Core forecast could not be converted, using the built-in one: {}", e.toString());
            adopted = null;
        }
        if (adopted == null) {
            return ServerForecastSource.withNote(builtin, "Использован встроенный расчёт: даты или горизонт "
                    + "прогноза сервера ML Core не совпадают с запрошенными.");
        }
        return adopted;
    }

    public Inputs inputs(String inn) {
        return cached(inn).inputs;
    }

    public List<RegularItem> regulars(String inn) {
        return cached(inn).detection.items();
    }

    public LocalDate asOf(String inn) {
        return cached(inn).inputs.asOf();
    }

    public void invalidate(String inn) {
        cache.remove(inn);
    }

    /**
     * Called after anything that changes the forecast inputs (operations imported, event created or
     * changed, ...). Drops the cache, recalculates the baseline and writes "before → after" to the journal.
     */
    public void onDataChanged(String inn, String reason) {
        Run before = peekBaseline(inn);
        invalidate(inn);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate(inn);
                }
            });
        }
        try {
            Run after = baseline(inn);
            journalService.record(inn, journalMessage(reason, before, after));
        } catch (InsufficientHistoryException e) {
            journalService.record(inn, reason + ": прогноз пока недоступен — " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Forecast recalculation failed for {}: {}", inn, e.toString());
            invalidate(inn);
        }
    }

    // ---------------------------------------------------------------- internals

    private Run peekBaseline(String inn) {
        Cached c = cache.get(inn);
        if (c == null || c.expired()) {
            return null;
        }
        return c.runs.get(DEFAULT_HORIZON + "|" + Condition.toMaps(List.of()));
    }

    private String journalMessage(String reason, Run before, Run after) {
        StringBuilder sb = new StringBuilder(reason).append(": ");
        sb.append("мин. остаток ");
        if (before != null) {
            sb.append(Money.rub(before.minBalance())).append(" → ");
        }
        sb.append(Money.rub(after.minBalance())).append(" (").append(after.minBalanceDate()).append("); ");
        sb.append("вероятность разрыва ");
        if (before != null) {
            sb.append(Money.pct(before.probability())).append(" → ");
        }
        sb.append(Money.pct(after.probability())).append("; ");
        if (after.firstGap() != null) {
            sb.append("первый разрыв ").append(after.firstGap().date())
                    .append(", не хватает ").append(Money.rub(after.firstGap().deficit()));
        } else {
            sb.append("разрыва в базовом прогнозе нет");
        }
        sb.append("; источник: ").append(after.source());
        return sb.toString();
    }

    private Cached cached(String inn) {
        Cached c = cache.get(inn);
        if (c != null && c.expired()) {
            cache.remove(inn, c);
            c = null;
        }
        if (c == null) {
            c = cache.computeIfAbsent(inn, this::build);
        }
        return c;
    }

    private Set<OperationSource> disabledSources(String inn) {
        return SourceFilter.disabled(companySourceRepository, inn);

    }

    private Cached build(String inn) {
        Set<OperationSource> disabled = disabledSources(inn);
        LocalDate asOf = operationRepository.findLastOperationDate(inn)
                .orElseThrow(() -> new InsufficientHistoryException("cash-forecast", MIN_HISTORY_DAYS, 0));
        LocalDate first = operationRepository.findFirstOperationDate(inn).orElse(asOf);

        LocalDate from = asOf.minusDays(HISTORY_WINDOW_DAYS);
        List<Operation> ops = operationRepository.findByCompanyInnAndDateRange(inn, from, asOf);
        if (!disabled.isEmpty()) {
            // The user switched sources off: the forecast is built on what is left, including its own "today".
            ops = ops.stream().filter(o -> !disabled.contains(o.getSource())).toList();
            if (ops.isEmpty()) {
                throw new InsufficientHistoryException("cash-forecast", MIN_HISTORY_DAYS, 0);
            }
            asOf = ops.stream().map(Operation::getOperationDate).max(Comparator.naturalOrder()).orElse(asOf);
            first = ops.stream().map(Operation::getOperationDate).min(Comparator.naturalOrder()).orElse(asOf);
        }
        int historyDays = (int) ChronoUnit.DAYS.between(first, asOf) + 1;
        if (historyDays < MIN_HISTORY_DAYS) {
            throw new InsufficientHistoryException("cash-forecast", MIN_HISTORY_DAYS, historyDays);
        }

        BigDecimal start = startBalance(inn, ops);
        RegularDetector.Detection detection = regularDetector.detect(ops, asOf);
        Residual residual = residual(ops, detection.keys(), asOf, historyDays);
        List<EventItem> events = events(inn, asOf);

        Inputs inputs = new Inputs(inn, asOf, start, historyDays, detection.items(), events, residual, disabled);
        return new Cached(inputs, detection);
    }

    private BigDecimal startBalance(String inn, List<Operation> ops) {
        // Anchor on the latest balance that is consistent with the flow (balance_after - balance_before == amount).
        // A balance that contradicts the operations around it (demo rows, broken exports) is not trusted: the
        // amounts that follow the last consistent balance are added to it instead.
        List<Operation> sorted = new ArrayList<>(ops);
        sorted.sort(java.util.Comparator.comparing(Operation::getOperationDate));
        int anchor = -1;
        int lastWithBalance = -1;
        BigDecimal prevBalance = null;
        BigDecimal flowSincePrev = BigDecimal.ZERO;
        for (int i = 0; i < sorted.size(); i++) {
            Operation op = sorted.get(i);
            flowSincePrev = flowSincePrev.add(signed(op));
            if (op.getBalanceEod() == null) {
                continue;
            }
            lastWithBalance = i;
            if (prevBalance != null
                    && op.getBalanceEod().subtract(prevBalance).subtract(flowSincePrev).abs()
                    .compareTo(BALANCE_TOLERANCE) <= 0) {
                anchor = i;
            }
            prevBalance = op.getBalanceEod();
            flowSincePrev = BigDecimal.ZERO;
        }
        if (anchor < 0) {
            anchor = lastWithBalance; // nothing to cross-check against: trust the latest stated balance
        }
        if (anchor >= 0) {
            BigDecimal balance = sorted.get(anchor).getBalanceEod();
            for (int i = anchor + 1; i < sorted.size(); i++) {
                balance = balance.add(signed(sorted.get(i)));
            }
            return balance;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Operation op : sorted) {
            sum = sum.add(signed(op));
        }
        return sum;
    }

    private static BigDecimal signed(Operation op) {
        return op.getDirection() == Direction.INCOME ? op.getAmount() : op.getAmount().negate();
    }

    private List<EventItem> events(String inn, LocalDate asOf) {
        List<EventItem> out = new ArrayList<>();
        List<CashEvent> list = cashEventRepository
                .findByCompanyInnAndEventDateGreaterThanEqualAndStatusInOrderByEventDateAscIdAsc(
                        inn, asOf.minusDays(30), List.of(CashEventStatus.EXPECTED, CashEventStatus.OVERDUE));
        for (CashEvent e : list) {
            LocalDate date = e.getEventDate();
            if (!date.isAfter(asOf)) {
                // Missed expected event. Unpaid obligations are assumed to be paid right away,
                // missed receipts are NOT counted (conservative).
                if (e.getDirection() == Direction.INCOME) {
                    continue;
                }
                date = asOf.plusDays(1);
            }
            out.add(new EventItem(e.getId(), date, e.getTitle(), e.getDirection(), e.getAmount(),
                    e.getCategory(), e.getSource()));
        }
        return out;
    }

    private Residual residual(List<Operation> ops, Set<String> regularKeys, LocalDate asOf, int historyDays) {
        int window = Math.min(RESIDUAL_WINDOW_DAYS, historyDays);
        LocalDate start = asOf.minusDays(window - 1L);
        double[] dayIn = new double[window];
        double[] dayOut = new double[window];
        for (Operation op : ops) {
            LocalDate d = op.getOperationDate();
            if (d.isBefore(start) || d.isAfter(asOf) || regularKeys.contains(regularDetector.keyOf(op))) {
                continue;
            }
            int idx = (int) ChronoUnit.DAYS.between(start, d);
            if (op.getDirection() == Direction.INCOME) {
                dayIn[idx] += op.getAmount().doubleValue();
            } else {
                dayOut[idx] += op.getAmount().doubleValue();
            }
        }
        double capIn = cap(dayIn);
        double capOut = cap(dayOut);

        List<List<Double>> in = new ArrayList<>();
        List<List<Double>> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            in.add(new ArrayList<>());
            out.add(new ArrayList<>());
        }
        for (int i = 0; i < window; i++) {
            int dow = start.plusDays(i).getDayOfWeek().getValue() - 1;
            in.get(dow).add(Math.min(dayIn[i], capIn));
            out.get(dow).add(Math.min(dayOut[i], capOut));
        }
        double[] meanIn = new double[7];
        double[] meanOut = new double[7];
        double[][] sampleIn = new double[7][];
        double[][] sampleOut = new double[7][];
        for (int i = 0; i < 7; i++) {
            sampleIn[i] = in.get(i).stream().mapToDouble(Double::doubleValue).toArray();
            sampleOut[i] = out.get(i).stream().mapToDouble(Double::doubleValue).toArray();
            meanIn[i] = java.util.Arrays.stream(sampleIn[i]).average().orElse(0);
            meanOut[i] = java.util.Arrays.stream(sampleOut[i]).average().orElse(0);
        }
        return new Residual(meanIn, meanOut, sampleIn, sampleOut, window);
    }

    /** Winsorization cap: 3 x median of positive daily totals, so a single huge one-off does not become "normal". */
    private static double cap(double[] daily) {
        List<Double> positive = new ArrayList<>();
        for (double v : daily) {
            if (v > 0) {
                positive.add(v);
            }
        }
        if (positive.size() < 4) {
            return Double.MAX_VALUE;
        }
        return 3 * RegularDetector.median(positive);
    }
}
