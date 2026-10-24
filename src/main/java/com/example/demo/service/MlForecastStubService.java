package com.example.demo.service;

import com.example.demo.dto.forecast.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic stub for ML-backed cash-forecast features.
 * Replace with real ml-service calls later.
 */
@Service
public class MlForecastStubService {

    public LocalDateTime calculatedAt() {
        return LocalDateTime.now().withSecond(0).withNano(0);
    }

    public ForecastSeriesResponse forecastSeries(String inn, int horizonDays, Long scenarioId, BigDecimal startBalance) {
        int days = Math.max(1, Math.min(horizonDays, 90));
        long seed = seed(inn) + (scenarioId == null ? 0 : scenarioId);
        BigDecimal balance = startBalance == null ? BigDecimal.valueOf(400_000) : startBalance;
        LocalDate start = LocalDate.now();
        List<ForecastSeriesResponse.DayPoint> points = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate date = start.plusDays(i);
            BigDecimal income = money(seed, i, 8_000, 45_000);
            BigDecimal expense = money(seed, i + 17, 10_000, 55_000);
            // salary-like spike
            if (date.getDayOfMonth() == 5) {
                expense = expense.add(BigDecimal.valueOf(180_000));
            }
            balance = balance.add(income).subtract(expense);
            BigDecimal negative = balance.subtract(BigDecimal.valueOf(30_000 + (seed % 20_000)));
            BigDecimal conf = BigDecimal.valueOf(15_000 + (seed % 10_000));
            points.add(new ForecastSeriesResponse.DayPoint(
                    date,
                    balance.setScale(2, RoundingMode.HALF_UP),
                    negative.setScale(2, RoundingMode.HALF_UP),
                    income,
                    expense,
                    balance.subtract(conf).setScale(2, RoundingMode.HALF_UP),
                    balance.add(conf).setScale(2, RoundingMode.HALF_UP)
            ));
        }
        return new ForecastSeriesResponse(inn, days, scenarioId, calculatedAt(), points, true);
    }

    public ForecastDayResponse forecastDay(String inn, LocalDate date, BigDecimal startBalance) {
        ForecastSeriesResponse series = forecastSeries(inn, 30, null, startBalance);
        ForecastSeriesResponse.DayPoint point = series.points().stream()
                .filter(p -> p.date().equals(date))
                .findFirst()
                .orElseGet(() -> {
                    BigDecimal bal = startBalance == null ? BigDecimal.valueOf(400_000) : startBalance;
                    return new ForecastSeriesResponse.DayPoint(
                            date, bal, bal.subtract(BigDecimal.valueOf(30_000)),
                            BigDecimal.valueOf(20_000), BigDecimal.valueOf(25_000),
                            bal.subtract(BigDecimal.valueOf(15_000)), bal.add(BigDecimal.valueOf(15_000))
                    );
                });
        BigDecimal start = point.baseBalance().subtract(point.income()).add(point.expense());
        List<ForecastDayResponse.DayEvent> events = List.of(
                new ForecastDayResponse.DayEvent("Поступление от клиентов", "csv", point.income(), "INCOME"),
                new ForecastDayResponse.DayEvent("Операционные расходы", "forecast", point.expense().negate(), "EXPENSE"),
                new ForecastDayResponse.DayEvent("Прогноз прочих поступлений по истории", "ml-stub",
                        BigDecimal.valueOf(12_000 + seed(inn) % 8_000), "INCOME")
        );
        return new ForecastDayResponse(
                date,
                start.setScale(2, RoundingMode.HALF_UP),
                point.income(),
                point.expense(),
                point.baseBalance(),
                BigDecimal.valueOf(12_000 + seed(inn) % 8_000),
                point.negativeBalance(),
                events,
                true
        );
    }

    public GapInfo gapInfo(String inn, BigDecimal startBalance) {
        ForecastSeriesResponse series = forecastSeries(inn, 30, null, startBalance);
        ForecastSeriesResponse.DayPoint worst = series.points().get(0);
        for (ForecastSeriesResponse.DayPoint p : series.points()) {
            if (p.negativeBalance().compareTo(worst.negativeBalance()) < 0) {
                worst = p;
            }
        }
        BigDecimal gapAmount = worst.negativeBalance().min(BigDecimal.ZERO).abs();
        if (gapAmount.signum() == 0) {
            gapAmount = BigDecimal.valueOf(30_100);
        }
        BigDecimal probability = BigDecimal.valueOf(0.35 + (seed(inn) % 30) / 100.0)
                .setScale(2, RoundingMode.HALF_UP);
        return new GapInfo(worst.date(), gapAmount, probability, worst.baseBalance().min(
                series.points().stream().map(ForecastSeriesResponse.DayPoint::baseBalance).min(BigDecimal::compareTo).orElse(worst.baseBalance())
        ));
    }

    public List<AlertDto> alerts(String inn, GapInfo gap) {
        return List.of(
                new AlertDto(
                        "gap-" + inn,
                        "critical",
                        "Возможна нехватка " + gap.gapAmount() + " ₽, " + gap.gapDate(),
                        "Если контрагент задержит оплату на 14 дней. Расчёт stub ML.",
                        gap.gapDate(),
                        gap.gapAmount().negate(),
                        "Проверить варианты",
                        "recommendations"
                ),
                new AlertDto(
                        "late-payment-" + inn,
                        "warning",
                        "Клиент не пришёл в срок",
                        "Ожидаемое поступление не вошло в базовый прогноз.",
                        LocalDate.now().minusDays(2),
                        BigDecimal.valueOf(128_400),
                        "Открыть план-факт",
                        "plan-fact"
                ),
                new AlertDto(
                        "tax-" + inn,
                        "info",
                        "Крупный обязательный платёж",
                        "Налоговый календарь / зарплата.",
                        LocalDate.now().plusDays(5),
                        BigDecimal.valueOf(-186_400),
                        "Смотреть платежи",
                        "events"
                ),
                new AlertDto(
                        "stale-" + inn,
                        "info",
                        "Прогноз по данным до " + LocalDate.now().minusDays(1),
                        "Пересчитан stub-моделью " + calculatedAt(),
                        LocalDate.now(),
                        null,
                        "Источники данных",
                        "sources"
                )
        );
    }

    public List<RecommendationDto> recommendations(String inn, GapInfo gap) {
        return List.of(
                new RecommendationDto(
                        "rec-collect",
                        "Получить оплату до разрыва",
                        "Ускорить поступление от ключевого контрагента.",
                        BigDecimal.valueOf(340_000),
                        gap.gapAmount().negate(),
                        gap.gapAmount().negate().add(BigDecimal.valueOf(26_000)),
                        "Проверить вариант"
                ),
                new RecommendationDto(
                        "rec-postpone",
                        "Перенести крупный платёж на 2 дня",
                        "Освобождает оборотные средства до зарплаты.",
                        BigDecimal.valueOf(214_000),
                        gap.gapAmount().negate(),
                        BigDecimal.valueOf(-4_100),
                        "Проверить вариант"
                )
        );
    }

    public List<BigDecimal> sparkline(String inn, BigDecimal startBalance) {
        ForecastSeriesResponse series = forecastSeries(inn, 14, null, startBalance);
        return series.points().stream().map(ForecastSeriesResponse.DayPoint::baseBalance).toList();
    }

    public List<CategoryCashflowDto.CategoryBucket> expenseCategories(String inn, BigDecimal totalExpense) {
        BigDecimal total = totalExpense == null || totalExpense.signum() == 0
                ? BigDecimal.valueOf(400_000)
                : totalExpense;
        List<String> names = List.of("Аренда и ЖКУ", "Зарплата", "Поставщики", "Налоги", "Прочее");
        List<Integer> weights = List.of(22, 35, 25, 10, 8);
        List<CategoryCashflowDto.CategoryBucket> buckets = new ArrayList<>();
        int sumW = weights.stream().mapToInt(Integer::intValue).sum();
        for (int i = 0; i < names.size(); i++) {
            BigDecimal amount = total.multiply(BigDecimal.valueOf(weights.get(i)))
                    .divide(BigDecimal.valueOf(sumW), 2, RoundingMode.HALF_UP);
            BigDecimal share = BigDecimal.valueOf(weights.get(i) * 100.0 / sumW).setScale(1, RoundingMode.HALF_UP);
            buckets.add(new CategoryCashflowDto.CategoryBucket(names.get(i), amount, share));
        }
        return buckets;
    }

    public String categorize(String purpose, String counterparty) {
        String text = ((purpose == null ? "" : purpose) + " " + (counterparty == null ? "" : counterparty)).toLowerCase();
        if (text.contains("аренд") || text.contains("жкх")) return "Аренда и ЖКУ";
        if (text.contains("зарплат") || text.contains("фод")) return "Зарплата";
        if (text.contains("налог") || text.contains("ндс") || text.contains("усн")) return "Налоги";
        if (text.contains("постав") || text.contains("товар")) return "Поставщики";
        long s = Math.abs((long) text.hashCode());
        List<String> fallback = List.of("Поставщики", "Прочее", "Аренда и ЖКУ");
        return fallback.get((int) (s % fallback.size()));
    }

    public List<RegularOperationDto> regularOperations(String inn) {
        LocalDate next = LocalDate.now().plusDays(3 + (int) (seed(inn) % 7));
        return List.of(
                new RegularOperationDto("reg-rent", "Аренда помещения", "Аренда и ЖКУ",
                        BigDecimal.valueOf(95_000), "EXPENSE", next, "monthly"),
                new RegularOperationDto("reg-salary", "Зарплата", "Зарплата",
                        BigDecimal.valueOf(186_400), "EXPENSE", next.withDayOfMonth(Math.min(5, next.lengthOfMonth())), "monthly"),
                new RegularOperationDto("reg-delta", "Оплата от Дельта-Сервис", "Поступления",
                        BigDecimal.valueOf(340_000), "INCOME", next.plusDays(4), "biweekly")
        );
    }

    public List<ScenarioDto.ExampleDto> scenarioExamples(String inn) {
        GapInfo gap = gapInfo(inn, null);
        return List.of(
                new ScenarioDto.ExampleDto(
                        "ex-delay-14",
                        "Контрагент позже на 14 дней",
                        "Негативный сценарий оплаты",
                        List.of(java.util.Map.of("type", "delay_payment", "days", 14, "counterparty", "Дельта-Сервис")),
                        gap.minBalance30d(),
                        gap.gapDate(),
                        gap.gapAmount()
                ),
                new ScenarioDto.ExampleDto(
                        "ex-collect-early",
                        "Собрать оплаты раньше",
                        "Ускорение поступлений на неделю",
                        List.of(java.util.Map.of("type", "accelerate_inflow", "days", 7)),
                        gap.minBalance30d().add(BigDecimal.valueOf(80_000)),
                        gap.gapDate().plusDays(5),
                        BigDecimal.ZERO
                )
        );
    }

    public PlanFactResponse planFact(String inn, LocalDate from, LocalDate to, List<PlanFactResponse.DayPoint> factPoints) {
        List<PlanFactResponse.DayPoint> points = new ArrayList<>();
        BigDecimal plan = BigDecimal.valueOf(420_000 + seed(inn) % 50_000);
        BigDecimal lastFact = plan;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            final LocalDate day = d;
            final BigDecimal fallback = lastFact;
            BigDecimal fact = factPoints.stream()
                    .filter(p -> p.date().equals(day))
                    .map(PlanFactResponse.DayPoint::fact)
                    .findFirst()
                    .orElse(fallback);
            lastFact = fact;
            plan = plan.add(money(seed(inn), day.getDayOfYear(), -5_000, 12_000));
            points.add(new PlanFactResponse.DayPoint(day, fact, plan.setScale(2, RoundingMode.HALF_UP)));
        }
        BigDecimal factEnd = points.isEmpty() ? BigDecimal.ZERO : points.get(points.size() - 1).fact();
        BigDecimal planEnd = points.isEmpty() ? BigDecimal.ZERO : points.get(points.size() - 1).plan();
        List<PlanFactResponse.MissedItem> missed = List.of(
                new PlanFactResponse.MissedItem(
                        "Мельница",
                        from.plusDays(Math.max(0, from.until(to).getDays() / 2)),
                        2,
                        BigDecimal.valueOf(128_400)
                )
        );
        return new PlanFactResponse(from, to, factEnd, planEnd, factEnd.subtract(planEnd), points, missed, true);
    }

    public WeeklyCashflowDto stubWeekly(String inn) {
        LocalDate start = LocalDate.now().minusWeeks(3).with(java.time.DayOfWeek.MONDAY);
        List<WeeklyCashflowDto.WeekBucket> weeks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            LocalDate ws = start.plusWeeks(i);
            weeks.add(new WeeklyCashflowDto.WeekBucket(
                    ws,
                    ws.plusDays(6),
                    money(seed(inn), i, 200_000, 600_000),
                    money(seed(inn), i + 9, 150_000, 450_000)
            ));
        }
        return new WeeklyCashflowDto(weeks, false);
    }

    public List<CashEventDto> stubUpcomingPayments(String inn) {
        LocalDate today = LocalDate.now();
        return List.of(
                new CashEventDto(null, inn, today.plusDays(2), "Налоги / взносы",
                        com.example.demo.entity.Direction.EXPENSE, BigDecimal.valueOf(86_000),
                        "Налоги", com.example.demo.entity.CashEventStatus.EXPECTED, "stub", null, null, null),
                new CashEventDto(null, inn, today.plusDays(5), "Зарплата",
                        com.example.demo.entity.Direction.EXPENSE, BigDecimal.valueOf(186_400),
                        "Зарплата", com.example.demo.entity.CashEventStatus.EXPECTED, "stub", null, null, null),
                new CashEventDto(null, inn, today.plusDays(7), "Оплата от Дельта-Сервис",
                        com.example.demo.entity.Direction.INCOME, BigDecimal.valueOf(340_000),
                        "Поступления", com.example.demo.entity.CashEventStatus.EXPECTED, "stub", null, null, null)
        );
    }

    public record GapInfo(LocalDate gapDate, BigDecimal gapAmount, BigDecimal gapProbability, BigDecimal minBalance30d) {
    }

    private static long seed(String inn) {
        return Math.abs(inn == null ? 42L : (long) inn.hashCode());
    }

    private static BigDecimal money(long seed, int salt, long min, long max) {
        long span = Math.max(1, max - min);
        long v = min + Math.floorMod(seed * 31 + salt * 17L, span);
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
