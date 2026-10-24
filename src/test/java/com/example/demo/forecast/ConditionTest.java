package com.example.demo.forecast;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConditionTest {

    @Test
    void roundTripsThroughStoredForm() {
        List<Condition> parsed = Condition.parse(List.of(
                Map.of("type", "delay_payment", "days", 10, "counterparty", "Клиент-1"),
                Map.of("type", "extra_expense", "amount", 250000, "date", "2026-10-15"),
                Map.of("type", "revenue_drop", "pct", 15)));
        List<Condition> again = Condition.parse(Condition.toMaps(parsed));
        assertThat(again).isEqualTo(parsed);
        assertThat(parsed.get(0).match()).isEqualTo("Клиент-1");
        assertThat(Condition.describeAll(parsed)).contains("Клиент-1").contains("10 дн.").contains("15%");
    }

    @Test
    void rejectsUnknownOrInvalidConditions() {
        assertThatThrownBy(() -> Condition.parse(List.of(Map.of("type", "magic"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown scenario condition type");
        assertThatThrownBy(() -> Condition.parse(List.of(Map.of("type", "delay_payment", "days", 0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Condition.parse(List.of(Map.of("type", "revenue_drop", "pct", 150))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Condition.parse(List.of(Map.of("type", "extra_expense", "amount", -5))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Condition.parse(List.of(Map.of("type", "shift_payment", "days", 3))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Condition.parse("not a list")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skipPaymentRequiresMatchAndRoundTrips() {
        List<Condition> parsed = Condition.parse(List.of(Map.of("type", "skip_payment", "match", "Аренда")));
        assertThat(Condition.parse(Condition.toMaps(parsed))).isEqualTo(parsed);
        assertThat(parsed.get(0).describe()).contains("Аренда").contains("не состоится");
        assertThatThrownBy(() -> Condition.parse(List.of(Map.of("type", "skip_payment"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("skip_payment.match");
    }

    @Test
    void delayPaymentAllowsPaymentsComingEarlier() {
        Condition earlier = Condition.parse(List.of(Map.of("type", "delay_payment", "days", -3))).get(0);
        assertThat(earlier.days()).isEqualTo(-3);
        assertThat(earlier.describe()).contains("раньше на 3");
        assertThatThrownBy(() -> Condition.parse(List.of(Map.of("type", "delay_payment", "days", -31))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyInputMeansNoConditions() {
        assertThat(Condition.parse(null)).isEmpty();
        assertThat(Condition.parse(List.of())).isEmpty();
    }
}
