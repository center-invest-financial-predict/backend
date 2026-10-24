package com.example.demo.forecast;

import com.example.demo.entity.Direction;
import com.example.demo.entity.Operation;
import com.example.demo.entity.OperationSource;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Deterministic synthetic bank statement shaped like the official dataset (monthly salary/tax, weekly fuel, noisy receipts). */
public final class SyntheticCompany {

    public static final String INN = "0098524470";
    public static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);

    private SyntheticCompany() {
    }

    public static List<Operation> operations(int days, double startBalance) {
        List<Operation> ops = new ArrayList<>();
        double balance = startBalance;
        int seq = 0;
        for (LocalDate d = AS_OF.minusDays(days - 1L); !d.isAfter(AS_OF); d = d.plusDays(1)) {
            List<Operation> day = new ArrayList<>();
            if (d.getDayOfMonth() == 10) {
                day.add(op(++seq, d, 300_000, Direction.EXPENSE, "0068275202", "ПАО «Банк»",
                        "Перечисление заработной платы за месяц по реестру № " + seq));
            }
            if (d.getDayOfMonth() == 25) {
                day.add(op(++seq, d, 120_000, Direction.EXPENSE, "0068275202", "ПАО «Банк»",
                        "Перечисление заработной платы (аванс) по реестру № " + seq));
            }
            if (d.getDayOfMonth() == 28) {
                day.add(op(++seq, d, 150_000, Direction.EXPENSE, "7727406020", "УФК по г. Москве (ФНС)",
                        "Единый налоговый платеж (ЕНП)"));
            }
            if (d.getDayOfWeek() == DayOfWeek.MONDAY) {
                day.add(op(++seq, d, 50_000, Direction.EXPENSE, "0062343230", "ООО «Топливная компания»",
                        "Пополнение лицевого счета по договору № 3672 (топливные карты). в том числе НДС (22%)"));
            }
            if (d.getDayOfWeek().getValue() <= 5 && Math.floorMod(d.toEpochDay() * 7919, 10) < 7) {
                int customer = (int) Math.floorMod(d.toEpochDay(), 7);
                double amount = 20_000 + Math.floorMod(d.toEpochDay() * 31, 9) * 12_500;
                day.add(op(++seq, d, amount, Direction.INCOME, "00484012" + (10 + customer), "ООО «Клиент-" + customer + "»",
                        "Оплата по УПД № " + seq + ". Без налога (НДС)"));
            }
            if (d.getDayOfWeek() == DayOfWeek.THURSDAY && Math.floorMod(d.toEpochDay(), 3) != 0) {
                double amount = 30_000 + Math.floorMod(d.toEpochDay() * 13, 6) * 15_000;
                day.add(op(++seq, d, amount, Direction.EXPENSE, "00953584" + (10 + Math.floorMod(d.toEpochDay(), 5)),
                        "ООО «Запчасти-" + Math.floorMod(d.toEpochDay(), 5) + "»",
                        "Оплата по счету № " + seq + " за запчасти. В т.ч. НДС 22%"));
            }
            for (Operation o : day) {
                balance += o.getDirection() == Direction.INCOME ? o.getAmount().doubleValue() : -o.getAmount().doubleValue();
            }
            for (Operation o : day) {
                o.setBalanceEod(BigDecimal.valueOf(balance).setScale(2, java.math.RoundingMode.HALF_UP));
            }
            ops.addAll(day);
        }
        return ops;
    }

    private static Operation op(int seq, LocalDate date, double amount, Direction direction, String cpInn,
                                String cpName, String purpose) {
        Operation o = new Operation();
        o.setCompanyInn(INN);
        o.setOperationId("T-" + seq);
        o.setOperationDate(date);
        o.setAmount(BigDecimal.valueOf(amount).setScale(2));
        o.setDirection(direction);
        o.setCounterpartyInn(cpInn);
        o.setCounterpartyName(cpName);
        o.setPaymentPurpose(purpose);
        o.setSource(OperationSource.ONE_C);
        return o;
    }
}
