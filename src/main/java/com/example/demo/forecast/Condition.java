package com.example.demo.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Typed scenario condition. Stored in {@code forecast_scenarios.conditions_json} as a JSON list of
 * {@code {"type": "...", ...}} objects, so the persisted contract is the {@link #toMap()} form.
 *
 * <pre>
 * delay_payment        {days, match?}       incoming payments (all, or those whose title/counterparty contains match) arrive later
 * revenue_drop         {pct}                regular and unscheduled revenue is lower by pct %
 * extra_expense        {amount, date|dayOffset}  one-off unplanned payment (default: day 7)
 * extra_income         {amount, date|dayOffset}  one-off additional receipt (default: day 7)
 * fixed_cost_growth    {pct}                rent / salary / credit payments are higher by pct %
 * shift_payment        {match, days|newDate}     move a matching payment (days &lt; 0 = earlier)
 * </pre>
 */
public record Condition(
        Type type,
        int days,
        double pct,
        BigDecimal amount,
        LocalDate date,
        Integer dayOffset,
        String match,
        LocalDate newDate
) {

    public static final int DEFAULT_OFFSET_DAYS = 7;
    public static final int STRESS_DELAY_DAYS = 14;

    /** All incoming payments arrive {@code days} later (the default stress test). */
    public static Condition delayAllIncome(int days) {
        return new Condition(Type.DELAY_PAYMENT, days, 0, null, null, null, null, null);
    }

    public enum Type {
        DELAY_PAYMENT("delay_payment"),
        REVENUE_DROP("revenue_drop"),
        EXTRA_EXPENSE("extra_expense"),
        EXTRA_INCOME("extra_income"),
        FIXED_COST_GROWTH("fixed_cost_growth"),
        SHIFT_PAYMENT("shift_payment");

        private final String code;

        Type(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        static Type of(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("Scenario condition requires 'type'. Supported: " + supported());
            }
            String n = raw.trim().toLowerCase(Locale.ROOT);
            for (Type t : values()) {
                if (t.code.equals(n)) {
                    return t;
                }
            }
            throw new IllegalArgumentException("Unknown scenario condition type '" + raw + "'. Supported: " + supported());
        }

        private static String supported() {
            StringBuilder sb = new StringBuilder();
            for (Type t : values()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(t.code);
            }
            return sb.toString();
        }
    }

    /** Parses the JSON-ish value stored/sent by clients: a list of maps (or a single map). */
    public static List<Condition> parse(Object raw) {
        List<Condition> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        if (raw instanceof Map<?, ?> single) {
            result.add(parseOne(single));
            return result;
        }
        if (raw instanceof Iterable<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    throw new IllegalArgumentException("Scenario conditions must be a list of objects");
                }
                result.add(parseOne(m));
            }
            return result;
        }
        throw new IllegalArgumentException("Scenario conditions must be a list of objects");
    }

    private static Condition parseOne(Map<?, ?> m) {
        Type type = Type.of(str(m.get("type")));
        String match = firstNonBlank(str(m.get("match")), str(m.get("counterparty")), str(m.get("title")));
        int days = (int) Math.round(num(m.get("days"), 0));
        double pct = num(m.get("pct"), num(m.get("percent"), 0));
        BigDecimal amount = m.get("amount") == null ? null : BigDecimal.valueOf(num(m.get("amount"), 0));
        LocalDate date = date(m.get("date"), "date");
        LocalDate newDate = date(m.get("newDate"), "newDate");
        Integer dayOffset = m.get("dayOffset") == null ? null : (int) Math.round(num(m.get("dayOffset"), 0));

        switch (type) {
            case DELAY_PAYMENT -> {
                if (days < 1 || days > 90) {
                    throw new IllegalArgumentException("delay_payment.days must be in 1..90");
                }
            }
            case REVENUE_DROP -> {
                if (pct <= 0 || pct > 100) {
                    throw new IllegalArgumentException("revenue_drop.pct must be in (0..100]");
                }
            }
            case FIXED_COST_GROWTH -> {
                if (pct <= 0 || pct > 200) {
                    throw new IllegalArgumentException("fixed_cost_growth.pct must be in (0..200]");
                }
            }
            case EXTRA_EXPENSE, EXTRA_INCOME -> {
                if (amount == null || amount.signum() <= 0) {
                    throw new IllegalArgumentException(type.code() + ".amount must be > 0");
                }
                if (date == null && dayOffset == null) {
                    dayOffset = DEFAULT_OFFSET_DAYS;
                }
            }
            case SHIFT_PAYMENT -> {
                if (match == null) {
                    throw new IllegalArgumentException("shift_payment.match is required");
                }
                if (newDate == null && (days == 0 || Math.abs(days) > 90)) {
                    throw new IllegalArgumentException("shift_payment requires newDate or days in -90..90 (non-zero)");
                }
            }
        }
        return new Condition(type, days, pct, amount, date, dayOffset, match, newDate);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type.code());
        switch (type) {
            case DELAY_PAYMENT -> {
                m.put("days", days);
                if (match != null) {
                    m.put("match", match);
                }
            }
            case REVENUE_DROP, FIXED_COST_GROWTH -> m.put("pct", pct);
            case EXTRA_EXPENSE, EXTRA_INCOME -> {
                m.put("amount", amount);
                if (date != null) {
                    m.put("date", date.toString());
                } else {
                    m.put("dayOffset", dayOffset);
                }
            }
            case SHIFT_PAYMENT -> {
                m.put("match", match);
                if (newDate != null) {
                    m.put("newDate", newDate.toString());
                } else {
                    m.put("days", days);
                }
            }
        }
        return m;
    }

    public static List<Map<String, Object>> toMaps(List<Condition> conditions) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Condition c : conditions) {
            out.add(c.toMap());
        }
        return out;
    }

    /** Human readable (Russian) description, used in UI chips and LLM context. */
    public String describe() {
        return switch (type) {
            case DELAY_PAYMENT -> (match == null ? "Поступления" : "Поступления от «" + match + "»")
                    + " задерживаются на " + days + " дн.";
            case REVENUE_DROP -> "Выручка ниже на " + trim(pct) + "%";
            case FIXED_COST_GROWTH -> "Постоянные расходы выше на " + trim(pct) + "%";
            case EXTRA_EXPENSE -> "Внеплановый расход " + Money.rub(amount) + " " + when();
            case EXTRA_INCOME -> "Дополнительное поступление " + Money.rub(amount) + " " + when();
            case SHIFT_PAYMENT -> newDate != null
                    ? "Платёж «" + match + "» переносится на " + newDate
                    : "Платёж «" + match + "» " + (days < 0 ? "раньше на " + (-days) : "позже на " + days) + " дн.";
        };
    }

    public static String describeAll(List<Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return "без условий (базовый прогноз)";
        }
        List<String> parts = new ArrayList<>();
        for (Condition c : conditions) {
            parts.add(c.describe());
        }
        return String.join("; ", parts);
    }

    private String when() {
        if (date != null) {
            return "на " + date;
        }
        return "через " + (dayOffset == null ? DEFAULT_OFFSET_DAYS : dayOffset) + " дн.";
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static double num(Object o, double def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(o.toString().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number: " + o);
        }
    }

    private static LocalDate date(Object o, String field) {
        if (o == null || o.toString().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(o.toString().trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Condition field '" + field + "' must be yyyy-MM-dd: " + o);
        }
    }
}
