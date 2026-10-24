package com.example.demo.forecast;

import com.example.demo.entity.Direction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** A recurring payment/receipt detected from operation history. */
public record RegularItem(
        String key,
        String title,
        Direction direction,
        String category,
        String counterpartyInn,
        String counterpartyName,
        Cadence cadence,
        int dayOfMonth,
        BigDecimal amount,
        List<BigDecimal> recentAmounts,
        LocalDate lastDate,
        List<LocalDate> lastDates,
        int occurrences,
        double confidence,
        boolean amountUnstable
) {

    public enum Cadence {
        WEEKLY(7, "Каждую неделю"),
        BIWEEKLY(14, "Раз в две недели"),
        MONTHLY(30, "Раз в месяц");

        private final int days;
        private final String label;

        Cadence(int days, String label) {
            this.days = days;
            this.label = label;
        }

        public int days() {
            return days;
        }

        public String label() {
            return label;
        }
    }

    /** Projected dates strictly after {@code after} and not after {@code until}. */
    public List<LocalDate> occurrences(LocalDate after, LocalDate until) {
        List<LocalDate> out = new ArrayList<>();
        if (cadence == Cadence.MONTHLY) {
            YearMonth ym = YearMonth.from(lastDate);
            for (int i = 1; i <= 48; i++) {
                YearMonth m = ym.plusMonths(i);
                LocalDate d = m.atDay(Math.min(dayOfMonth, m.lengthOfMonth()));
                if (d.isAfter(until)) {
                    break;
                }
                if (d.isAfter(after)) {
                    out.add(d);
                }
            }
        } else {
            LocalDate d = lastDate.plusDays(cadence.days());
            while (!d.isAfter(until)) {
                if (d.isAfter(after)) {
                    out.add(d);
                }
                d = d.plusDays(cadence.days());
            }
        }
        return out;
    }

    /** First projected occurrence after {@code after}, or null. */
    public LocalDate nextDate(LocalDate after) {
        List<LocalDate> dates = occurrences(after, after.plusDays(400));
        return dates.isEmpty() ? null : dates.get(0);
    }
}
