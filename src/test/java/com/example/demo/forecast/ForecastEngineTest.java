package com.example.demo.forecast;

import com.example.demo.entity.CashEvent;
import com.example.demo.entity.CashEventStatus;
import com.example.demo.entity.Direction;
import com.example.demo.entity.Operation;
import com.example.demo.exception.InsufficientHistoryException;
import com.example.demo.forecast.ForecastTypes.DayItem;
import com.example.demo.forecast.ForecastTypes.DayPoint;
import com.example.demo.forecast.ForecastTypes.Run;
import com.example.demo.repository.CashEventRepository;
import com.example.demo.repository.OperationRepository;
import com.example.demo.service.RecalcJournalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class ForecastEngineTest {

    private static final String INN = SyntheticCompany.INN;
    private static final LocalDate AS_OF = SyntheticCompany.AS_OF;

    private OperationRepository operations;
    private CashEventRepository events;
    private RecalcJournalService journal;
    private ForecastService service;
    private List<Operation> ops;
    private final List<CashEvent> eventList = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ops = SyntheticCompany.operations(240, 1_000_000);
        operations = Mockito.mock(OperationRepository.class);
        events = Mockito.mock(CashEventRepository.class);
        journal = Mockito.mock(RecalcJournalService.class);
        Mockito.when(operations.findLastOperationDate(INN)).thenAnswer(i -> ops.stream()
                .map(Operation::getOperationDate).max(LocalDate::compareTo));
        Mockito.when(operations.findFirstOperationDate(INN)).thenAnswer(i -> ops.stream()
                .map(Operation::getOperationDate).min(LocalDate::compareTo));
        Mockito.when(operations.findByCompanyInnAndDateRange(anyString(), any(), any())).thenAnswer(i -> {
            LocalDate a = i.getArgument(1);
            LocalDate b = i.getArgument(2);
            return ops.stream().filter(o -> !o.getOperationDate().isBefore(a) && !o.getOperationDate().isAfter(b)).toList();
        });
        Mockito.when(events.findByCompanyInnAndEventDateGreaterThanEqualAndStatusInOrderByEventDateAscIdAsc(any(), any(), any()))
                .thenAnswer(i -> eventList);
        service = new ForecastService(operations, events, new RegularDetector(new CategoryClassifier()),
                new ForecastEngine(), journal, ServerForecastSource.disabled(),
                Mockito.mock(com.example.demo.repository.CompanySourceRepository.class));
    }

    private CashEvent event(LocalDate date, String title, Direction direction, double amount, String category) {
        CashEvent e = new CashEvent();
        e.setId((long) (eventList.size() + 1));
        e.setCompanyInn(INN);
        e.setEventDate(date);
        e.setTitle(title);
        e.setDirection(direction);
        e.setAmount(BigDecimal.valueOf(amount));
        e.setCategory(category);
        e.setStatus(CashEventStatus.EXPECTED);
        e.setSource("manual");
        eventList.add(e);
        return e;
    }

    @Test
    void detectsRegularPaymentsAndLeavesNoisyReceiptsOut() {
        List<RegularItem> regulars = service.regulars(INN);
        assertThat(regulars).extracting(RegularItem::title)
                .contains("Зарплата", "Зарплата (аванс)", "Налоги и взносы", "ООО «Топливная компания»");
        RegularItem tax = regulars.stream().filter(r -> r.category().equals("Налоги")).findFirst().orElseThrow();
        assertThat(tax.cadence()).isEqualTo(RegularItem.Cadence.MONTHLY);
        assertThat(tax.dayOfMonth()).isEqualTo(28);
        assertThat(tax.amount()).isEqualByComparingTo("150000");
        RegularItem fuel = regulars.stream().filter(r -> r.title().contains("Топливная")).findFirst().orElseThrow();
        assertThat(fuel.cadence()).isEqualTo(RegularItem.Cadence.WEEKLY);
        // customer receipts have unstable amounts and must stay in the statistical remainder
        assertThat(regulars).noneMatch(r -> r.direction() == Direction.INCOME);
    }

    @Test
    void stoppedSeriesIsNotProjected() {
        // drop all salary operations of the last 3 months: the series has stopped
        ops.removeIf(o -> o.getPaymentPurpose().contains("заработной платы за месяц")
                && o.getOperationDate().isAfter(AS_OF.minusDays(90)));
        assertThat(service.regulars(INN)).noneMatch(r -> r.title().equals("Зарплата"));
    }

    @Test
    void forecastIsReproducibleAndAnchoredToLastOperation() {
        Run a = service.baseline(INN);
        service.invalidate(INN);
        Run b = service.baseline(INN);
        assertThat(a.asOf()).isEqualTo(AS_OF);
        assertThat(a.points()).hasSize(30);
        assertThat(a.points().get(0).date()).isEqualTo(AS_OF.plusDays(1));
        assertThat(a.startBalance()).isEqualByComparingTo(ops.get(ops.size() - 1).getBalanceEod());
        assertThat(a.probability()).isEqualTo(b.probability());
        assertThat(a.points().get(29).balance()).isEqualByComparingTo(b.points().get(29).balance());
        assertThat(a.runId()).isEqualTo(b.runId());
    }

    @Test
    void regularPaymentsLandOnTheirCalendarDates() {
        Run run = service.baseline(INN);
        DayPoint oct10 = run.points().stream().filter(p -> p.date().equals(LocalDate.of(2026, 10, 10))).findFirst().orElseThrow();
        assertThat(oct10.items()).anySatisfy(i -> {
            assertThat(i.title()).isEqualTo("Зарплата");
            assertThat(i.amount()).isEqualByComparingTo("300000");
            assertThat(i.source()).isEqualTo("regular");
        });
        DayPoint oct28 = run.points().stream().filter(p -> p.date().equals(LocalDate.of(2026, 10, 28))).findFirst().orElseThrow();
        assertThat(oct28.items()).anySatisfy(i -> assertThat(i.category()).isEqualTo("Налоги"));
    }

    @Test
    void futureEventChangesTheForecastAndCreatesAGapWithDateAndAmount() {
        Run before = service.baseline(INN);
        assertThat(before.firstGap()).isNull();

        LocalDate day = AS_OF.plusDays(5);
        double payment = before.startBalance().doubleValue() + 500_000;
        event(day, "Закупка оборудования", Direction.EXPENSE, payment, "Прочее");
        service.invalidate(INN);
        Run after = service.baseline(INN);

        assertThat(after.firstGap()).isNotNull();
        assertThat(after.firstGap().date()).isBeforeOrEqualTo(day);
        assertThat(after.firstGap().deficit().signum()).isPositive();
        assertThat(after.minBalance()).isLessThan(before.minBalance());
        assertThat(after.probability()).isGreaterThan(before.probability());
        DayPoint p = after.points().stream().filter(x -> x.date().equals(day)).findFirst().orElseThrow();
        assertThat(p.items()).extracting(DayItem::title).contains("Закупка оборудования");
    }

    @Test
    void eventThatDuplicatesARegularPaymentIsNotCountedTwice() {
        Run before = service.baseline(INN);
        event(LocalDate.of(2026, 10, 28), "Налоги за сентябрь", Direction.EXPENSE, 150_000, "Налоги");
        service.invalidate(INN);
        Run after = service.baseline(INN);
        assertThat(after.totalOutflow()).isEqualByComparingTo(before.totalOutflow());
        DayPoint p = after.points().stream().filter(x -> x.date().equals(LocalDate.of(2026, 10, 28))).findFirst().orElseThrow();
        assertThat(p.items()).filteredOn(i -> i.category().equals("Налоги")).hasSize(1)
                .allSatisfy(i -> assertThat(i.source()).isEqualTo("event"));
    }

    @Test
    void overdueReceiptsAreNotCountedButOverdueExpensesArePaidFirstDay() {
        Run before = service.baseline(INN);
        event(AS_OF.minusDays(3), "Просроченное поступление", Direction.INCOME, 700_000, "Продажи");
        event(AS_OF.minusDays(2), "Просроченный платёж", Direction.EXPENSE, 200_000, "Прочее");
        service.invalidate(INN);
        Run after = service.baseline(INN);
        assertThat(after.totalInflow()).isEqualByComparingTo(before.totalInflow());
        assertThat(after.totalOutflow().subtract(before.totalOutflow())).isEqualByComparingTo("200000");
        assertThat(after.points().get(0).items()).extracting(DayItem::title).contains("Просроченный платёж");
    }

    @Test
    void scenariosReallyRecalculate() {
        Run base = service.baseline(INN);

        Run delayed = service.run(INN, 30, Condition.parse(List.of(Map.of("type", "delay_payment", "days", 14))));
        assertThat(delayed.minBalance()).isLessThan(base.minBalance());
        assertThat(delayed.totalInflow()).isLessThan(base.totalInflow());

        Run drop = service.run(INN, 30, Condition.parse(List.of(Map.of("type", "revenue_drop", "pct", 50))));
        assertThat(drop.totalInflow()).isLessThan(base.totalInflow());

        Run growth = service.run(INN, 30, Condition.parse(List.of(Map.of("type", "fixed_cost_growth", "pct", 20))));
        assertThat(growth.totalOutflow().subtract(base.totalOutflow()).doubleValue())
                .isCloseTo((300_000 + 120_000) * 0.2, org.assertj.core.data.Offset.offset(1.0));

        double big = base.startBalance().doubleValue() * 2;
        Run extra = service.run(INN, 30, Condition.parse(List.of(
                Map.of("type", "extra_expense", "amount", big, "dayOffset", 3))));
        assertThat(extra.firstGap()).isNotNull();
        assertThat(extra.firstGap().date()).isEqualTo(AS_OF.plusDays(3));
        assertThat(extra.probability()).isGreaterThan(0.9);

        Run shifted = service.run(INN, 30, Condition.parse(List.of(
                Map.of("type", "shift_payment", "match", "Налоги", "days", 40))));
        assertThat(shifted.totalOutflow()).isLessThan(base.totalOutflow());

        assertThat(extra.conditions()).hasSize(1);
        assertThat(service.baseline(INN).totalOutflow()).isEqualByComparingTo(base.totalOutflow()); // base untouched
    }

    @Test
    void skippedPaymentDisappearsAndEarlierReceiptsDoNotHurt() {
        Run base = service.baseline(INN);

        Run skipped = service.run(INN, 30, Condition.parse(List.of(Map.of("type", "skip_payment", "match", "Налоги"))));
        assertThat(skipped.totalOutflow().subtract(base.totalOutflow())).isEqualByComparingTo("-150000");
        assertThat(skipped.endBalance()).isGreaterThan(base.endBalance());

        Run earlier = service.run(INN, 30, Condition.parse(List.of(Map.of("type", "delay_payment", "days", -3))));
        assertThat(earlier.minBalance()).isGreaterThanOrEqualTo(base.minBalance());
        assertThat(earlier.conditions()).hasSize(1);
    }

    @Test
    void onDataChangedWritesBeforeAfterJournalEntry() {
        service.baseline(INN);
        event(AS_OF.plusDays(2), "Крупный платёж", Direction.EXPENSE, 5_000_000, "Прочее");
        service.onDataChanged(INN, "Добавлено событие");
        org.mockito.ArgumentCaptor<String> msg = org.mockito.ArgumentCaptor.forClass(String.class);
        Mockito.verify(journal).record(Mockito.eq(INN), msg.capture());
        assertThat(msg.getValue()).contains("Добавлено событие").contains("→").contains("первый разрыв");
    }

    @Test
    void shortHistoryIsRejected() {
        ops = SyntheticCompany.operations(5, 1_000_000);
        assertThatThrownBy(() -> service.baseline(INN)).isInstanceOf(InsufficientHistoryException.class);
    }

    @Test
    void fakeBalanceOnTheLastDayIsIgnored() {
        Operation demo = new Operation();
        demo.setCompanyInn(INN);
        demo.setOperationId("DEMO-1");
        demo.setOperationDate(AS_OF);
        demo.setAmount(new BigDecimal("1234.56"));
        demo.setDirection(Direction.INCOME);
        demo.setCounterpartyName("ООО «1С Демо»");
        demo.setPaymentPurpose("Демо-операция 1C");
        demo.setBalanceEod(new BigDecimal("999999.99"));
        BigDecimal real = ops.get(ops.size() - 1).getBalanceEod();
        ops.add(demo);
        Run run = service.baseline(INN);
        assertThat(run.startBalance().subtract(real).doubleValue())
                .isBetween(0.0, 1234.56 + 0.01);
    }
}
