package com.example.demo.service;

import com.example.demo.client.MlServiceClient;
import com.example.demo.dto.ml.MlForecastRequest;
import com.example.demo.dto.ml.MlForecastResponse;
import com.example.demo.dto.ml.MlForecastResultResponse;
import com.example.demo.entity.Company;
import com.example.demo.entity.Direction;
import com.example.demo.entity.Operation;
import com.example.demo.exception.InsufficientHistoryException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.CompanySourceRepository;
import com.example.demo.repository.OperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds daily history from {@code operations} (same preprocessing as the reference
 * ML scripts), calls ml-service {@code POST /forecast} and maps the answer.
 */
@Service
@RequiredArgsConstructor
public class MlForecastService {

    public static final String MODEL_GRADIENT_BOOSTING = "gradient_boosting";
    public static final String MODEL_DYNAMICAL = "dynamical";

    public static final int MIN_HORIZON_DAYS = 1;
    public static final int MAX_HORIZON_DAYS = 90;
    /** Max days of history sent to ml-service. */
    static final int HISTORY_WINDOW_DAYS = 365;

    private final OperationRepository operationRepository;
    private final CompanyRepository companyRepository;
    private final MlServiceClient mlServiceClient;
    private final CompanySourceRepository companySourceRepository;

    public MlForecastResultResponse forecast(String inn, int horizonDays, String model) {
        String modelName = normalizeModel(model);
        if (horizonDays < MIN_HORIZON_DAYS || horizonDays > MAX_HORIZON_DAYS) {
            throw new IllegalArgumentException(
                    "horizonDays must be in range " + MIN_HORIZON_DAYS + ".." + MAX_HORIZON_DAYS);
        }

        Company company = companyRepository.findById(inn)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found: " + inn));

        LocalDate lastDate = operationRepository.findLastOperationDate(inn)
                .orElseThrow(() -> new InsufficientHistoryException(modelName, minHistoryDays(modelName), 0));
        // Only the sources the user has switched on go to the model.
        List<Operation> operations = SourceFilter.without(operationRepository.findByCompanyInnAndDateRange(
                inn, lastDate.minusDays(HISTORY_WINDOW_DAYS - 1L), lastDate),
                SourceFilter.disabled(companySourceRepository, inn));

        List<MlForecastRequest.HistoryDay> history = buildDailyHistory(operations);
        int required = minHistoryDays(modelName);
        if (history.size() < required) {
            throw new InsufficientHistoryException(modelName, required, history.size());
        }

        MlForecastRequest request = new MlForecastRequest(
                inn,
                company.getCompanyCode(),
                company.getIndustry(),
                company.getLegalForm(),
                modelName,
                horizonDays,
                history
        );

        MlForecastResponse response;
        try {
            response = mlServiceClient.forecast(request);
        } catch (MlServiceClient.MlServiceException e) {
            if (e.getCause() instanceof HttpStatusCodeException http
                    && http.getStatusCode().value() == 400
                    && http.getResponseBodyAsString().contains("INSUFFICIENT_HISTORY")) {
                throw new InsufficientHistoryException(modelName, required, history.size());
            }
            throw e;
        }

        return toResult(inn, modelName, history.size(), response);
    }

    static String normalizeModel(String model) {
        String m = model == null || model.isBlank() ? MODEL_GRADIENT_BOOSTING : model.trim().toLowerCase();
        if (!m.equals(MODEL_GRADIENT_BOOSTING) && !m.equals(MODEL_DYNAMICAL)) {
            throw new IllegalArgumentException(
                    "Unknown model '" + model + "'. Supported: " + MODEL_GRADIENT_BOOSTING + ", " + MODEL_DYNAMICAL);
        }
        return m;
    }

    static int minHistoryDays(String model) {
        return MODEL_DYNAMICAL.equals(model) ? 30 : 90;
    }

    /**
     * Daily aggregation identical to the reference scripts: inflow/outflow sums per day,
     * missing days filled with 0, balance_eod = last value of the day, forward-filled then back-filled.
     * {@code operations} must be ordered by date, id (as returned by the repository).
     */
    static List<MlForecastRequest.HistoryDay> buildDailyHistory(List<Operation> operations) {
        if (operations == null || operations.isEmpty()) {
            return List.of();
        }

        TreeMap<LocalDate, double[]> byDay = new TreeMap<>(); // [inflow, outflow]
        TreeMap<LocalDate, BigDecimal> balanceByDay = new TreeMap<>();
        operations.stream()
                .sorted(Comparator.comparing(Operation::getOperationDate))
                .forEach(op -> {
                    double[] acc = byDay.computeIfAbsent(op.getOperationDate(), d -> new double[2]);
                    double amount = op.getAmount() == null ? 0.0 : op.getAmount().doubleValue();
                    if (op.getDirection() == Direction.INCOME) {
                        acc[0] += amount;
                    } else {
                        acc[1] += amount;
                    }
                    if (op.getBalanceEod() != null) {
                        balanceByDay.put(op.getOperationDate(), op.getBalanceEod());
                    }
                });

        if (balanceByDay.isEmpty()) {
            throw new IllegalArgumentException("Operations of the company have no balance_eod values");
        }

        LocalDate first = byDay.firstKey();
        LocalDate last = byDay.lastKey();
        int days = (int) ChronoUnit.DAYS.between(first, last) + 1;

        double[] balances = new double[days];
        boolean[] known = new boolean[days];
        for (Map.Entry<LocalDate, BigDecimal> e : balanceByDay.entrySet()) {
            int idx = (int) ChronoUnit.DAYS.between(first, e.getKey());
            balances[idx] = e.getValue().doubleValue();
            known[idx] = true;
        }
        // forward fill
        double lastSeen = Double.NaN;
        for (int i = 0; i < days; i++) {
            if (known[i]) {
                lastSeen = balances[i];
            } else if (!Double.isNaN(lastSeen)) {
                balances[i] = lastSeen;
                known[i] = true;
            }
        }
        // back fill the leading gap
        double nextSeen = Double.NaN;
        for (int i = days - 1; i >= 0; i--) {
            if (known[i]) {
                nextSeen = balances[i];
            } else {
                balances[i] = nextSeen;
            }
        }

        List<MlForecastRequest.HistoryDay> result = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate date = first.plusDays(i);
            double[] acc = byDay.getOrDefault(date, new double[2]);
            result.add(new MlForecastRequest.HistoryDay(date, acc[0], acc[1], balances[i]));
        }
        return result;
    }

    static MlForecastResultResponse toResult(String inn, String requestedModel, int historyDays, MlForecastResponse r) {
        List<MlForecastResultResponse.ForecastPointDto> points = r.points().stream()
                .map(p -> new MlForecastResultResponse.ForecastPointDto(
                        p.date(), money(p.predictedNetCashFlow()), money(p.predictedBalance())))
                .toList();

        List<MlForecastResultResponse.CashGapDto> gaps;
        if (r.cashGaps() != null) {
            gaps = r.cashGaps().stream()
                    .map(MlForecastService::toGap)
                    .toList();
        } else {
            gaps = r.points().stream()
                    .filter(p -> p.predictedBalance() != null && p.predictedBalance() < 0)
                    .map(p -> toGap(new MlForecastResponse.Gap(
                            p.date(), p.predictedBalance(), Math.abs(p.predictedBalance()))))
                    .toList();
        }
        MlForecastResultResponse.CashGapDto firstGap = r.firstGap() != null
                ? toGap(r.firstGap())
                : (gaps.isEmpty() ? null : gaps.get(0));

        MlForecastResultResponse.MinBalanceDto min;
        if (r.minBalance() != null) {
            min = new MlForecastResultResponse.MinBalanceDto(r.minBalance().date(), money(r.minBalance().predictedBalance()));
        } else {
            min = points.stream()
                    .min(Comparator.comparing(MlForecastResultResponse.ForecastPointDto::predictedBalance))
                    .map(p -> new MlForecastResultResponse.MinBalanceDto(p.date(), p.predictedBalance()))
                    .orElse(null);
        }

        return new MlForecastResultResponse(
                inn,
                r.model() != null ? r.model() : requestedModel,
                r.modelVersion(),
                r.horizonDays() != null ? r.horizonDays() : points.size(),
                r.lastHistoryDate(),
                historyDays,
                money(r.startBalance()),
                points,
                gaps,
                firstGap,
                min,
                LocalDateTime.now().withNano(0)
        );
    }

    private static MlForecastResultResponse.CashGapDto toGap(MlForecastResponse.Gap g) {
        BigDecimal balance = money(g.predictedBalance());
        BigDecimal deficit = g.deficit() != null ? money(g.deficit()) : balance.abs();
        return new MlForecastResultResponse.CashGapDto(g.date(), balance, deficit);
    }

    private static BigDecimal money(Double v) {
        if (v == null || v.isNaN() || v.isInfinite()) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
