package com.example.demo.forecast;

import com.example.demo.entity.Direction;
import com.example.demo.forecast.ForecastTypes.DayItem;
import com.example.demo.forecast.ForecastTypes.DayPoint;
import com.example.demo.forecast.ForecastTypes.EventItem;
import com.example.demo.forecast.ForecastTypes.Gap;
import com.example.demo.forecast.ForecastTypes.Inputs;
import com.example.demo.forecast.ForecastTypes.Residual;
import com.example.demo.forecast.ForecastTypes.Run;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/**
 * Cash balance forecast = known + regular + statistical remainder.
 *
 * <ol>
 *   <li><b>Known</b>: expected events entered/imported by the user (invoices, contracts, taxes, manual).</li>
 *   <li><b>Regular</b>: recurring payments detected in history ({@link RegularDetector}); an occurrence is
 *       skipped if a known event covers it, so nothing is counted twice.</li>
 *   <li><b>Remainder</b>: everything else, by weekday, from the last weeks of history.</li>
 * </ol>
 *
 * <p>Scenario conditions modify the same pipeline (delays, shifts, drops, extra payments). The same pipeline
 * is replayed {@code simulations} times with bootstrapped remainder days, random regular amounts and random
 * delays of expected receipts; the share of paths that go below zero is the gap probability and the 10th/90th
 * percentiles give the band. Random seed is derived from the inputs, so the result is reproducible and
 * every screen sees the same numbers.
 */
@Component
public class ForecastEngine {

    public static final int DEFAULT_SIMULATIONS = 400;
    public static final int MAX_HORIZON_DAYS = 90;

    static final double EVENT_DELAY_PROBABILITY = 0.25;
    static final int EVENT_DELAY_MAX_DAYS = 14;
    private static final int EVENT_MATCH_DAYS = 4;

    public Run run(Inputs in, List<Condition> conditions, int horizon, int simulations) {
        if (horizon < 1 || horizon > MAX_HORIZON_DAYS) {
            throw new IllegalArgumentException("horizonDays must be in range 1.." + MAX_HORIZON_DAYS);
        }
        List<Condition> cs = conditions == null ? List.of() : List.copyOf(conditions);

        Path det = path(in, cs, horizon, new MeanSampler(in.residual()), true);

        int sims = Math.max(0, simulations);
        double[][] columns = new double[horizon + 1][sims];
        Random rnd = new Random(seed(in, cs, horizon));
        int bad = 0;
        for (int s = 0; s < sims; s++) {
            Path p = path(in, cs, horizon, new RandomSampler(in.residual(), rnd), false);
            boolean negative = false;
            for (int d = 1; d <= horizon; d++) {
                columns[d][s] = p.bal[d];
                if (p.bal[d] < 0) {
                    negative = true;
                }
            }
            if (negative) {
                bad++;
            }
        }

        double[] p10 = new double[horizon + 1];
        double[] p90 = new double[horizon + 1];
        for (int d = 1; d <= horizon; d++) {
            if (sims == 0) {
                p10[d] = det.bal[d];
                p90[d] = det.bal[d];
            } else {
                Arrays.sort(columns[d]);
                p10[d] = columns[d][(int) Math.floor(0.10 * (sims - 1))];
                p90[d] = columns[d][(int) Math.floor(0.90 * (sims - 1))];
            }
        }

        List<DayPoint> points = new ArrayList<>(horizon);
        double min = Double.MAX_VALUE;
        int minDay = 1;
        int firstGapDay = -1;
        int deepestDay = -1;
        int firstP10Day = -1;
        double deepestP10 = 0;
        for (int d = 1; d <= horizon; d++) {
            points.add(new DayPoint(
                    in.asOf().plusDays(d),
                    Money.bd(det.in[d]), Money.bd(det.out[d]),
                    Money.bd(det.resIn[d]), Money.bd(det.resOut[d]),
                    Money.bd(det.bal[d]), Money.bd(p10[d]), Money.bd(p90[d]),
                    det.items[d]));
            if (det.bal[d] < min) {
                min = det.bal[d];
                minDay = d;
            }
            if (det.bal[d] < 0) {
                if (firstGapDay < 0) {
                    firstGapDay = d;
                }
                if (deepestDay < 0 || det.bal[d] < det.bal[deepestDay]) {
                    deepestDay = d;
                }
            }
            if (p10[d] < 0) {
                if (firstP10Day < 0) {
                    firstP10Day = d;
                }
                deepestP10 = Math.max(deepestP10, -p10[d]);
            }
        }

        double probability = sims == 0 ? (firstGapDay > 0 ? 1.0 : 0.0) : (double) bad / sims;
        return new Run(
                in.inn(),
                runId(in, cs, horizon),
                in.asOf(),
                in.historyDays(),
                in.startBalance(),
                horizon,
                points,
                Money.bd(min),
                in.asOf().plusDays(minDay),
                firstGapDay < 0 ? null : gap(in.asOf(), firstGapDay, det.bal[firstGapDay], -det.bal[firstGapDay]),
                deepestDay < 0 ? null : gap(in.asOf(), deepestDay, det.bal[deepestDay], -det.bal[deepestDay]),
                firstP10Day < 0 ? null : gap(in.asOf(), firstP10Day, p10[firstP10Day], deepestP10),
                Math.round(probability * 1000) / 1000.0,
                sims,
                cs,
                warnings(in),
                LocalDateTime.now().withNano(0),
                Run.SOURCE_BUILTIN,
                null
        );
    }

    // ---------------------------------------------------------------- path

    private Path path(Inputs in, List<Condition> cs, int horizon, Sampler sampler, boolean detail) {
        LocalDate asOf = in.asOf();
        Path p = new Path(horizon);

        double[] r = new double[2];
        for (int d = 1; d <= horizon; d++) {
            int dow = asOf.plusDays(d).getDayOfWeek().getValue() - 1;
            sampler.residual(dow, r);
            p.resIn[d] = r[0];
            p.resOut[d] = r[1];
        }
        for (Condition c : cs) {
            if (c.type() == Condition.Type.DELAY_PAYMENT && c.match() == null) {
                shift(p.resIn, c.days());
            } else if (c.type() == Condition.Type.REVENUE_DROP) {
                for (int d = 1; d <= horizon; d++) {
                    p.resIn[d] *= 1 - c.pct() / 100.0;
                }
            }
        }

        List<Item> items = new ArrayList<>();
        LocalDate until = asOf.plusDays(horizon);

        for (RegularItem reg : in.regulars()) {
            for (LocalDate dt : reg.occurrences(asOf, until)) {
                double amount = sampler.regular(reg);
                if (coveredByEvent(reg, dt, in.events())) {
                    continue;
                }
                items.add(new Item(day(asOf, dt), amount, reg.direction() == Direction.INCOME, reg.title(),
                        reg.category(), "regular",
                        reg.cadence().label() + ", по истории: последний платёж " + reg.lastDate(),
                        (reg.title() + " " + nz(reg.counterpartyName())).toLowerCase(Locale.ROOT)));
            }
        }
        for (EventItem e : in.events()) {
            int d0 = day(asOf, e.date());
            if (d0 < 1 || d0 > horizon) {
                continue;
            }
            int delay = e.direction() == Direction.INCOME ? sampler.eventDelay(e) : 0;
            Item ev = new Item(d0 + delay, e.amount().doubleValue(), e.direction() == Direction.INCOME, e.title(),
                    e.category(), "event", "Ожидаемое событие (" + nz(e.source()) + ") на " + e.date(),
                    (e.title() + " " + nz(e.category())).toLowerCase(Locale.ROOT));
            ev.eventId = e.id();
            items.add(ev);
        }

        for (Condition c : cs) {
            switch (c.type()) {
                case DELAY_PAYMENT -> {
                    for (Item it : items) {
                        if (it.income && !it.scenario && (c.match() == null || matches(c.match(), it))) {
                            it.day += c.days();
                        }
                    }
                }
                case SHIFT_PAYMENT -> {
                    for (Item it : items) {
                        if (!it.scenario && matches(c.match(), it)) {
                            it.day = c.newDate() != null ? day(asOf, c.newDate()) : it.day + c.days();
                            it.basis = it.basis + "; перенос по сценарию";
                        }
                    }
                }
                case FIXED_COST_GROWTH -> {
                    for (Item it : items) {
                        if (!it.income && !it.scenario && CategoryClassifier.FIXED_COSTS.contains(it.category)) {
                            it.amount *= 1 + c.pct() / 100.0;
                        }
                    }
                }
                case REVENUE_DROP -> {
                    for (Item it : items) {
                        if (it.income && !it.scenario && "regular".equals(it.source)) {
                            it.amount *= 1 - c.pct() / 100.0;
                        }
                    }
                }
                case EXTRA_EXPENSE, EXTRA_INCOME -> {
                    int d0 = c.date() != null ? day(asOf, c.date()) : (c.dayOffset() == null ? 7 : c.dayOffset());
                    boolean income = c.type() == Condition.Type.EXTRA_INCOME;
                    Item it = new Item(d0, c.amount().doubleValue(), income,
                            income ? "Дополнительное поступление (сценарий)" : "Внеплановый расход (сценарий)",
                            income ? CategoryClassifier.SALES : CategoryClassifier.OTHER, "scenario",
                            c.describe(), "");
                    it.scenario = true;
                    items.add(it);
                }
            }
        }

        for (int d = 1; d <= horizon; d++) {
            p.in[d] = p.resIn[d];
            p.out[d] = p.resOut[d];
        }
        for (Item it : items) {
            if (it.day < 1 || it.day > horizon) {
                continue;
            }
            if (it.income) {
                p.in[it.day] += it.amount;
            } else {
                p.out[it.day] += it.amount;
            }
            if (detail) {
                p.itemLists[it.day].add(it);
            }
        }
        double bal = in.startBalance().doubleValue();
        p.bal[0] = bal;
        for (int d = 1; d <= horizon; d++) {
            bal += p.in[d] - p.out[d];
            p.bal[d] = bal;
            if (detail) {
                List<Item> list = p.itemLists[d];
                list.sort(Comparator.comparingDouble((Item i) -> -i.amount));
                List<DayItem> out = new ArrayList<>(list.size());
                for (Item i : list) {
                    out.add(new DayItem(i.title, i.source, i.income ? "INCOME" : "EXPENSE",
                            Money.bd(i.amount), i.category, i.basis, i.eventId));
                }
                p.items[d] = out;
            }
        }
        if (!detail) {
            for (int d = 1; d <= horizon; d++) {
                p.items[d] = List.of();
            }
        }
        return p;
    }

    private static boolean coveredByEvent(RegularItem reg, LocalDate date, List<EventItem> events) {
        for (EventItem e : events) {
            if (e.direction() != reg.direction()) {
                continue;
            }
            if (Math.abs(ChronoUnit.DAYS.between(e.date(), date)) > EVENT_MATCH_DAYS) {
                continue;
            }
            if (e.category() != null && e.category().equalsIgnoreCase(reg.category())) {
                return true;
            }
            boolean categoryKnown = e.category() != null && !e.category().isBlank()
                    && !CategoryClassifier.OTHER.equalsIgnoreCase(e.category());
            if (categoryKnown) {
                continue; // an event of another known category is a different payment, whatever its amount
            }
            double ratio = reg.amount().signum() == 0 ? 0 : e.amount().doubleValue() / reg.amount().doubleValue();
            if (ratio >= 0.65 && ratio <= 1.35 && sharesWord(e.title(), reg.title() + " " + nz(reg.counterpartyName()))) {
                return true;
            }
        }
        return false;
    }

    /** At least one word of 4+ letters in common (stems are not handled: it is a cheap guard, not a matcher). */
    private static boolean sharesWord(String a, String b) {
        java.util.Set<String> words = new java.util.HashSet<>();
        for (String w : nz(a).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 4) {
                words.add(w);
            }
        }
        for (String w : nz(b).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 4 && words.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String match, Item it) {
        return match != null && it.text.contains(match.toLowerCase(Locale.ROOT));
    }

    private static void shift(double[] a, int days) {
        for (int d = a.length - 1; d >= 1; d--) {
            a[d] = d - days >= 1 ? a[d - days] : 0;
        }
    }

    private static int day(LocalDate asOf, LocalDate d) {
        return (int) ChronoUnit.DAYS.between(asOf, d);
    }

    private static Gap gap(LocalDate asOf, int day, double balance, double deficit) {
        return new Gap(asOf.plusDays(day), Money.bd(balance), Money.bd(deficit));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static long seed(Inputs in, List<Condition> cs, int horizon) {
        long h = 1125899906842597L;
        for (char c : (in.inn() + "|" + in.asOf() + "|" + horizon + "|" + Condition.describeAll(cs)
                + "|" + in.startBalance()).toCharArray()) {
            h = 31 * h + c;
        }
        return h;
    }

    private static String runId(Inputs in, List<Condition> cs, int horizon) {
        int h = Objects.hash(in.inn(), in.asOf(), in.startBalance(), horizon, Condition.describeAll(cs),
                in.regulars().size(), in.events().size(),
                in.events().stream().mapToLong(e -> Objects.hash(e.id(), e.date(), e.amount(), e.title())).sum());
        return Integer.toHexString(h);
    }

    private static List<String> warnings(Inputs in) {
        List<String> w = new ArrayList<>();
        if (in.historyDays() < 90) {
            w.add("История короткая (" + in.historyDays() + " дн.): прогноз приблизительный, диапазон шире обычного.");
        }
        if (in.regulars().isEmpty()) {
            w.add("Регулярные платежи по истории не обнаружены: прогноз опирается на средние значения по дням недели.");
        }
        long lag = ChronoUnit.DAYS.between(in.asOf(), LocalDate.now());
        if (lag > 3) {
            w.add("Данные по операциям до " + in.asOf() + ": свежие операции не получены (" + lag + " дн.).");
        }
        return w;
    }

    // ---------------------------------------------------------------- internals

    private static final class Item {
        int day;
        double amount;
        final boolean income;
        final String title;
        final String category;
        final String source;
        String basis;
        final String text;
        boolean scenario;
        Long eventId;

        Item(int day, double amount, boolean income, String title, String category, String source,
             String basis, String text) {
            this.day = day;
            this.amount = amount;
            this.income = income;
            this.title = title;
            this.category = category;
            this.source = source;
            this.basis = basis;
            this.text = text;
        }
    }

    private static final class Path {
        final double[] in;
        final double[] out;
        final double[] bal;
        final double[] resIn;
        final double[] resOut;
        final List<Item>[] itemLists;
        final List<DayItem>[] items;

        @SuppressWarnings("unchecked")
        Path(int horizon) {
            in = new double[horizon + 1];
            out = new double[horizon + 1];
            bal = new double[horizon + 1];
            resIn = new double[horizon + 1];
            resOut = new double[horizon + 1];
            itemLists = new List[horizon + 1];
            items = new List[horizon + 1];
            for (int i = 0; i <= horizon; i++) {
                itemLists[i] = new ArrayList<>();
            }
        }
    }

    private interface Sampler {
        void residual(int dow, double[] out);

        double regular(RegularItem r);

        int eventDelay(EventItem e);
    }

    private record MeanSampler(Residual res) implements Sampler {
        @Override
        public void residual(int dow, double[] out) {
            out[0] = res.meanIn()[dow];
            out[1] = res.meanOut()[dow];
        }

        @Override
        public double regular(RegularItem r) {
            return r.amount().doubleValue();
        }

        @Override
        public int eventDelay(EventItem e) {
            return 0;
        }
    }

    private record RandomSampler(Residual res, Random rnd) implements Sampler {
        @Override
        public void residual(int dow, double[] out) {
            double[] in = res.sampleIn()[dow];
            if (in.length == 0) {
                out[0] = 0;
                out[1] = 0;
                return;
            }
            int k = rnd.nextInt(in.length);
            out[0] = in[k];
            out[1] = res.sampleOut()[dow][k];
        }

        @Override
        public double regular(RegularItem r) {
            List<java.math.BigDecimal> a = r.recentAmounts();
            return a.isEmpty() ? r.amount().doubleValue() : a.get(rnd.nextInt(a.size())).doubleValue();
        }

        @Override
        public int eventDelay(EventItem e) {
            return rnd.nextDouble() < EVENT_DELAY_PROBABILITY ? 1 + rnd.nextInt(EVENT_DELAY_MAX_DAYS) : 0;
        }
    }
}
