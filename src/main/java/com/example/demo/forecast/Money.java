package com.example.demo.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Formatting and rounding helpers shared by the forecast code. */
public final class Money {

    private Money() {
    }

    public static BigDecimal bd(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    /** "1 234 567 ₽" (no kopecks, regular spaces so the text is safe for logs and LLM prompts). */
    public static String rub(BigDecimal v) {
        if (v == null) {
            return "—";
        }
        long rounded = v.setScale(0, RoundingMode.HALF_UP).longValue();
        String digits = Long.toString(Math.abs(rounded));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                sb.append(' ');
            }
            sb.append(digits.charAt(i));
        }
        return (rounded < 0 ? "-" : "") + sb + " ₽";
    }

    public static String rub(double v) {
        return rub(bd(v));
    }

    public static String pct(double probability) {
        return Math.round(probability * 100) + "%";
    }
}
