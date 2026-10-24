package com.example.demo.forecast;

import com.example.demo.entity.Direction;
import com.example.demo.entity.Operation;
import com.example.demo.forecast.RegularItem.Cadence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Detects recurring payments and receipts (weekly / bi-weekly / monthly) in operation history.
 *
 * <p>A series is operations with the same direction, counterparty, category and sub-kind.
 * It is "regular" when at least 70% of the gaps between consecutive dates fit one cadence
 * and the series is still active. Receipts additionally need stable amounts (CV &lt;= 0.35),
 * because unscheduled customer payments must stay in the statistical "other" part of the forecast.
 */
@Component
@RequiredArgsConstructor
public class RegularDetector {

    static final int MIN_OCCURRENCES = 3;
    static final double MIN_REGULARITY = 0.7;
    static final double MAX_INCOME_AMOUNT_CV = 0.35;
    static final double UNSTABLE_AMOUNT_CV = 0.5;
    private static final int RECENT = 6;

    private final CategoryClassifier classifier;

    public record Detection(List<RegularItem> items, Set<String> keys) {
    }

    public String keyOf(Operation op) {
        String category = classifier.classify(op);
        String cp = op.getCounterpartyInn() != null && !op.getCounterpartyInn().isBlank()
                ? op.getCounterpartyInn().trim()
                : (op.getCounterpartyName() == null ? "" : op.getCounterpartyName().trim().toLowerCase(Locale.ROOT));
        return op.getDirection().name() + "|" + cp + "|" + category + "|"
                + classifier.subKey(category, op.getPaymentPurpose());
    }

    public Detection detect(List<Operation> operations, LocalDate asOf) {
        Map<String, List<Operation>> groups = new LinkedHashMap<>();
        for (Operation op : operations) {
            if (op.getAmount() == null || op.getDirection() == null || op.getOperationDate() == null) {
                continue;
            }
            groups.computeIfAbsent(keyOf(op), k -> new ArrayList<>()).add(op);
        }

        List<RegularItem> items = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, List<Operation>> g : groups.entrySet()) {
            RegularItem item = analyze(g.getKey(), g.getValue(), asOf);
            if (item != null) {
                items.add(item);
                keys.add(g.getKey());
            }
        }
        items.sort(Comparator.comparing(RegularItem::lastDate).reversed());
        return new Detection(items, keys);
    }

    private RegularItem analyze(String key, List<Operation> ops, LocalDate asOf) {
        TreeMap<LocalDate, Double> byDate = new TreeMap<>();
        for (Operation op : ops) {
            byDate.merge(op.getOperationDate(), op.getAmount().doubleValue(), Double::sum);
        }
        if (byDate.size() < MIN_OCCURRENCES) {
            return null;
        }
        List<LocalDate> dates = new ArrayList<>(byDate.keySet());
        List<Double> amounts = new ArrayList<>(byDate.values());

        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < dates.size(); i++) {
            gaps.add((int) ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i)));
        }
        double medianGap = median(gaps.stream().map(Integer::doubleValue).toList());

        Cadence cadence;
        int lo;
        int hi;
        if (medianGap >= 5 && medianGap <= 9) {
            cadence = Cadence.WEEKLY;
            lo = 4;
            hi = 10;
        } else if (medianGap >= 12 && medianGap <= 17) {
            cadence = Cadence.BIWEEKLY;
            lo = 11;
            hi = 18;
        } else if (medianGap >= 24 && medianGap <= 36) {
            cadence = Cadence.MONTHLY;
            lo = 24;
            hi = 37;
        } else {
            return null;
        }
        long fit = gaps.stream().filter(g -> g >= lo && g <= hi).count();
        double regularity = (double) fit / gaps.size();
        if (regularity < MIN_REGULARITY) {
            return null;
        }

        LocalDate last = dates.get(dates.size() - 1);
        if (ChronoUnit.DAYS.between(last, asOf) > cadence.days() * 2L) {
            return null; // series stopped
        }

        double cv = coefficientOfVariation(amounts);
        Operation sample = ops.get(ops.size() - 1);
        if (sample.getDirection() == Direction.INCOME && cv > MAX_INCOME_AMOUNT_CV) {
            return null;
        }

        int from = Math.max(0, amounts.size() - RECENT);
        List<Double> recent = amounts.subList(from, amounts.size());
        List<BigDecimal> recentAmounts = recent.stream().map(Money::bd).toList();
        BigDecimal amount = Money.bd(median(recent));
        List<LocalDate> lastDates = new ArrayList<>(dates.subList(Math.max(0, dates.size() - 3), dates.size()));
        int dom = (int) Math.round(median(dates.subList(Math.max(0, dates.size() - RECENT), dates.size()).stream()
                .map(d -> (double) d.getDayOfMonth()).toList()));

        String category = classifier.classify(sample);
        return new RegularItem(
                key,
                title(sample, category),
                sample.getDirection(),
                category,
                sample.getCounterpartyInn(),
                sample.getCounterpartyName(),
                cadence,
                dom,
                amount,
                recentAmounts,
                last,
                lastDates,
                dates.size(),
                Math.round(regularity * 100) / 100.0,
                cv > UNSTABLE_AMOUNT_CV
        );
    }

    private String title(Operation sample, String category) {
        String sub = classifier.subKey(category, sample.getPaymentPurpose());
        return switch (category) {
            case CategoryClassifier.SALARY -> "advance".equals(sub) ? "Зарплата (аванс)" : "Зарплата";
            case CategoryClassifier.TAXES -> "Налоги и взносы";
            case CategoryClassifier.CREDIT -> "Платёж по кредиту";
            case CategoryClassifier.BANK -> "Комиссия банка";
            case CategoryClassifier.RENT -> "Аренда и коммунальные";
            case CategoryClassifier.TRANSFER -> "Перевод между своими счетами";
            default -> sample.getCounterpartyName() != null && !sample.getCounterpartyName().isBlank()
                    ? sample.getCounterpartyName()
                    : category;
        };
    }

    static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    static double coefficientOfVariation(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (mean == 0) {
            return 0;
        }
        double var = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        return Math.sqrt(var) / Math.abs(mean);
    }
}
