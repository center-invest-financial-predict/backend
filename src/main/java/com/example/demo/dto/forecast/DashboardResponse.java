package com.example.demo.dto.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Overview KPIs. Everything forecast-related comes from one calculation ({@code runId});
 * {@code gapProbability} is a fraction 0..1 produced by Monte-Carlo simulation, not by the LLM.
 */
public record DashboardResponse(
        String companyInn,
        /** Date of the last operation in the data; the forecast starts on the next day. */
        LocalDate dataAsOf,
        BigDecimal currentBalance,
        BigDecimal balanceChange7d,
        BigDecimal minBalance30d,
        LocalDate minBalanceDate,
        /** Share (0..1) of simulated paths where the balance drops below zero within the horizon. */
        BigDecimal gapProbability,
        /** First day of the base forecast with a negative balance, null if none. */
        LocalDate gapDate,
        /** Shortfall on {@code gapDate}, null if no gap. */
        BigDecimal gapAmount,
        /** First day where the pessimistic (10th percentile) path is negative, null if none. */
        LocalDate riskGapDate,
        BigDecimal riskGapAmount,
        BigDecimal weeklyIncome,
        BigDecimal weeklyIncomeChangePct,
        BigDecimal weeklyExpense,
        BigDecimal weeklyExpenseChangePct,
        BigDecimal expectedIncome30d,
        BigDecimal expectedExpense30d,
        List<BigDecimal> balanceSparkline,
        LocalDateTime calculatedAt,
        String runId,
        int historyDays,
        List<String> warnings,
        /** Who calculated the forecast: {@code builtin} or {@code ml-core}. */
        String source,
        /** Why the source is what it is (e.g. ML Core returned a stub or unsupported risk estimate); null otherwise. */
        String sourceNote
) {
}
