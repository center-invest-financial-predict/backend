package com.example.demo.forecast;

import com.example.demo.entity.Direction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Value types of the forecast engine. */
public final class ForecastTypes {

    private ForecastTypes() {
    }

    /** A user-entered or imported expected payment/receipt inside the forecast window. */
    public record EventItem(Long id, LocalDate date, String title, Direction direction,
                            BigDecimal amount, String category, String source) {
    }

    /**
     * Statistical profile of everything that is neither regular nor a known event:
     * mean and raw daily samples per weekday (0 = Monday), over the last weeks of history.
     * Samples are paired (same date) to keep income/expense correlation in the simulation.
     */
    public record Residual(double[] meanIn, double[] meanOut, double[][] sampleIn, double[][] sampleOut,
                           int windowDays) {
    }

    /** {@code excludedSources}: sources the user switched off; their operations are not part of the forecast. */
    public record Inputs(String inn, LocalDate asOf, BigDecimal startBalance, int historyDays,
                         List<RegularItem> regulars, List<EventItem> events, Residual residual,
                         java.util.Set<com.example.demo.entity.OperationSource> excludedSources) {
        public Inputs(String inn, LocalDate asOf, BigDecimal startBalance, int historyDays,
                      List<RegularItem> regulars, List<EventItem> events, Residual residual) {
            this(inn, asOf, startBalance, historyDays, regulars, events, residual,
                 java.util.EnumSet.noneOf(com.example.demo.entity.OperationSource.class));
        }
    }

    public record DayItem(String title, String source, String direction, BigDecimal amount,
                          String category, String basis, Long eventId) {
    }

    public record DayPoint(LocalDate date, BigDecimal inflow, BigDecimal outflow,
                           BigDecimal otherInflow, BigDecimal otherOutflow,
                           BigDecimal balance, BigDecimal p10, BigDecimal p90, List<DayItem> items) {
    }

    public record Gap(LocalDate date, BigDecimal balance, BigDecimal deficit) {
    }

    /**
     * One consistent calculation. Everything shown for a company (chart, KPIs, alerts, day drill-down,
     * LLM context) must be derived from the same {@code Run}.
     *
     * @param firstGap    first day of the deterministic path with balance &lt; 0
     * @param deepestGap  day with the lowest negative balance of the deterministic path
     * @param p10Gap      same for the pessimistic (10th percentile) path of the simulation
     * @param probability share of simulated paths whose balance drops below zero at least once
     */
    public record Run(String inn, String runId, LocalDate asOf, int historyDays, BigDecimal startBalance,
                      int horizon, List<DayPoint> points, BigDecimal minBalance, LocalDate minBalanceDate,
                      Gap firstGap, Gap deepestGap, Gap p10Gap, double probability, int simulations,
                      List<Condition> conditions, List<String> warnings, LocalDateTime calculatedAt,
                      String source, String note) {

        public static final String SOURCE_BUILTIN = "builtin";
        public static final String SOURCE_ML_CORE = "ml-core";

        public boolean fromMlCore() {
            return SOURCE_ML_CORE.equals(source);
        }

        public BigDecimal endBalance() {
            return points.isEmpty() ? startBalance : points.get(points.size() - 1).balance();
        }

        public BigDecimal totalInflow() {
            return points.stream().map(DayPoint::inflow).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal totalOutflow() {
            return points.stream().map(DayPoint::outflow).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }
}
