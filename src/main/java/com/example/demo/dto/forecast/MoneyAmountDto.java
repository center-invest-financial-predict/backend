package com.example.demo.dto.forecast;

import java.math.BigDecimal;

public record MoneyAmountDto(BigDecimal amount, String currency) {
    public static MoneyAmountDto rub(BigDecimal amount) {
        return new MoneyAmountDto(amount == null ? BigDecimal.ZERO : amount, "RUB");
    }
}
