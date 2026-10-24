package com.example.demo.forecast;

import com.example.demo.client.MlCoreClient;
import com.example.demo.dto.mlcore.MlCoreApi;
import com.example.demo.entity.Company;
import com.example.demo.entity.Operation;
import com.example.demo.forecast.ForecastTypes.DayPoint;
import com.example.demo.forecast.ForecastTypes.Gap;
import com.example.demo.forecast.ForecastTypes.Inputs;
import com.example.demo.forecast.ForecastTypes.Run;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.OperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Optional second source of the baseline forecast: the "Cashflow ML Core" server.
 * <p>
 * {@code forecast.source}: {@code builtin} - never call the server; {@code auto} (default) and {@code mlcore} - ask the
 * server and adopt its numbers only when they are a real forecast ({@code is_stub=false} AND a calculated
 * {@code cash_gap_probability}). Anything else (stub, error, timeout, mismatching days) quietly falls back to the
 * built-in engine and the reason is exposed as {@link Run#note()}. The server never breaks an endpoint.
 * <p>
 * The server gives balances and a probability but no uncertainty band, regular payments or per-day items:
 * those stay from the built-in run (and the note says so), nothing is invented.
 */
@Component
public class ServerForecastSource {

    private static final Logger log = LoggerFactory.getLogger(ServerForecastSource.class);

    public enum Mode {
        BUILTIN, AUTO, MLCORE;

        static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "builtin", "built-in", "local" -> BUILTIN;
                case "mlcore", "ml-core" -> MLCORE;
                default -> AUTO;
            };
        }
    }

    /** {@code result == null} means "use the built-in run"; {@code note} explains why (null when the server is off). */
    public record Outcome(MlCoreApi.ForecastResult result, String note) {
        static final Outcome OFF = new Outcome(null, null);
    }

    private final MlCoreClient client;
    private final OperationRepository operationRepository;
    private final CompanyRepository companyRepository;
    private final Mode mode;
    private final int historyDays;

    @Autowired
    public ServerForecastSource(MlCoreClient client, OperationRepository operationRepository,
                                CompanyRepository companyRepository,
                                @Value("${forecast.source:auto}") String source,
                                @Value("${ml.core.history-days:365}") int historyDays) {
        this.client = client;
        this.operationRepository = operationRepository;
        this.companyRepository = companyRepository;
        this.mode = Mode.parse(source);
        this.historyDays = historyDays;
    }

    /** Never calls the server (unit tests of the engine). */
    public static ServerForecastSource disabled() {
        return new ServerForecastSource(null, null, null, "builtin", 365);
    }

    public boolean active() {
        return mode != Mode.BUILTIN && client != null && client.isEnabled();
    }

    public Outcome fetch(Inputs in) {
        if (!active()) {
            return Outcome.OFF;
        }
        try {
            MlCoreApi.ForecastResult r = client.forecast(request(in));
            boolean stub = r.isStub() == null || r.isStub();
            if (stub) {
                return fallback("сервер ML Core вернул заглушку (is_stub=true), вероятность разрыва не рассчитана",
                        null);
            }
            if (r.cashGapProbability() == null) {
                return fallback("сервер ML Core не вернул вероятность разрыва", null);
            }
            if (r.days() == null || r.days().isEmpty()) {
                return fallback("сервер ML Core вернул пустой прогноз", null);
            }
            return new Outcome(r, null);
        } catch (RuntimeException e) {
            return fallback("сервер ML Core недоступен (" + e.getMessage() + ")", e);
        }
    }

    private Outcome fallback(String why, RuntimeException cause) {
        String text = "Использован встроенный расчёт: " + why + ".";
        if (mode == Mode.MLCORE) {
            log.warn("forecast.source=mlcore, but {}", why);
        } else {
            log.info("ML Core forecast not used: {}", why);
        }
        if (cause != null) {
            log.debug("ML Core forecast failure", cause);
        }
        return new Outcome(null, text);
    }

    private MlCoreApi.ForecastRequest request(Inputs in) {
        Company company = companyRepository.findById(in.inn()).orElse(null);
        String code = company == null ? in.inn() : company.getCompanyCode();
        List<Operation> ops = operationRepository.findByCompanyInnAndDateRange(
                in.inn(), in.asOf().minusDays(Math.max(historyDays, 1)), in.asOf());
        List<MlCoreApi.Operation> payload = new ArrayList<>(ops.size());
        for (Operation op : ops) {
            if (op.getAmount() == null || op.getAmount().signum() <= 0 || op.getDirection() == null) {
                continue;
            }
            payload.add(new MlCoreApi.Operation(code, op.getOperationId(), op.getOperationDate(), op.getAmount(),
                    op.getDirection().getLabel(), op.getCounterpartyInn(), op.getCounterpartyName(),
                    op.getPaymentPurpose(), op.getBalanceEod()));
        }
        if (payload.isEmpty()) {
            throw new IllegalStateException("нет операций для отправки");
        }
        return new MlCoreApi.ForecastRequest(payload, in.asOf(), in.startBalance());
    }

    /**
     * Builds a {@link Run} from the server forecast. Days, balances, the first gap and the probability are the server's;
     * regulars/day items and the p10/p90 band are taken from the built-in run. Returns {@code null} (and the caller keeps
     * the built-in run) when the server days do not match the requested horizon/dates.
     */
    public Run adopt(Run builtin, MlCoreApi.ForecastResult r) {
        List<MlCoreApi.DailyBalance> days = r.days();
        if (days.size() != builtin.horizon() || builtin.points().size() != builtin.horizon()) {
            return null;
        }
        List<DayPoint> points = new ArrayList<>(days.size());
        BigDecimal min = null;
        LocalDate minDate = null;
        Gap first = null;
        Gap deepest = null;
        for (int i = 0; i < days.size(); i++) {
            MlCoreApi.DailyBalance d = days.get(i);
            DayPoint b = builtin.points().get(i);
            if (d.date() == null || !d.date().equals(b.date()) || d.balance() == null) {
                return null;
            }
            BigDecimal income = nz(d.income());
            BigDecimal expenses = nz(d.expenses());
            BigDecimal knownIn = b.inflow().subtract(b.otherInflow());
            BigDecimal knownOut = b.outflow().subtract(b.otherOutflow());
            BigDecimal balance = d.balance().setScale(2, RoundingMode.HALF_UP);
            points.add(new DayPoint(d.date(), income, expenses,
                    income.subtract(knownIn).max(BigDecimal.ZERO), expenses.subtract(knownOut).max(BigDecimal.ZERO),
                    balance, b.p10(), b.p90(), b.items()));
            if (min == null || balance.compareTo(min) < 0) {
                min = balance;
                minDate = d.date();
            }
            if (balance.signum() < 0) {
                Gap g = new Gap(d.date(), balance, balance.negate());
                if (first == null) {
                    first = g;
                }
                if (deepest == null || balance.compareTo(deepest.balance()) < 0) {
                    deepest = g;
                }
            }
        }
        double probability = Math.max(0, Math.min(1, r.cashGapProbability()));
        List<String> warnings = new ArrayList<>(builtin.warnings());
        if (r.warnings() != null) {
            warnings.addAll(r.warnings());
        }
        String note = "Баланс, первый разрыв и вероятность разрыва рассчитаны сервером ML Core; "
                + "коридор p10/p90 и разбивка по платежам взяты из встроенного расчёта.";
        return new Run(builtin.inn(), builtin.runId() + "-ml", builtin.asOf(), builtin.historyDays(),
                builtin.startBalance(), builtin.horizon(), points, min, minDate, first, deepest, builtin.p10Gap(),
                Math.round(probability * 1000) / 1000.0, 0, builtin.conditions(), warnings, builtin.calculatedAt(),
                Run.SOURCE_ML_CORE, note);
    }

    /** Copy of a built-in run with a note about why the server was not used. */
    public static Run withNote(Run run, String note) {
        if (note == null) {
            return run;
        }
        return new Run(run.inn(), run.runId(), run.asOf(), run.historyDays(), run.startBalance(), run.horizon(),
                run.points(), run.minBalance(), run.minBalanceDate(), run.firstGap(), run.deepestGap(), run.p10Gap(),
                run.probability(), run.simulations(), run.conditions(), run.warnings(), run.calculatedAt(),
                run.source(), note);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }
}
