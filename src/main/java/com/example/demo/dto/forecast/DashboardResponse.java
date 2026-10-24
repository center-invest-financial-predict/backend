package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DashboardResponse(
        String companyInn,
        BigDecimal currentBalance,
        BigDecimal balanceChange7d,
        BigDecimal minBalance30d,
        LocalDate minBalanceDate,
        BigDecimal gapProbability,
        LocalDate gapDate,
        BigDecimal gapAmount,
        BigDecimal weeklyIncome,
        BigDecimal weeklyIncomeChangePct,
        BigDecimal weeklyExpense,
        BigDecimal weeklyExpenseChangePct,
        List<BigDecimal> balanceSparkline,
        LocalDateTime calculatedAt,
        boolean stubMlFields
) {
}
