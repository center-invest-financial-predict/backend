package com.example.demo.service;

import com.example.demo.client.MlServiceClient;
import com.example.demo.dto.ml.MlForecastRequest;
import com.example.demo.dto.ml.MlForecastResponse;
import com.example.demo.dto.ml.MlForecastResultResponse;
import com.example.demo.entity.Company;
import com.example.demo.entity.Direction;
import com.example.demo.entity.Operation;
import com.example.demo.exception.InsufficientHistoryException;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.OperationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MlForecastServiceTest {

    private static final String INN = "7701234567";

    private final OperationRepository operationRepository = mock(OperationRepository.class);
    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final MlServiceClient client = mock(MlServiceClient.class);
    private final MlForecastService service = new MlForecastService(operationRepository, companyRepository, client,
            org.mockito.Mockito.mock(com.example.demo.repository.CompanySourceRepository.class));

    private static Operation op(String date, Direction dir, String amount, String balance) {
        Operation o = new Operation();
        o.setOperationDate(LocalDate.parse(date));
        o.setDirection(dir);
        o.setAmount(new BigDecimal(amount));
        o.setBalanceEod(balance == null ? null : new BigDecimal(balance));
        return o;
    }

    @Test
    void buildDailyHistoryAggregatesFillsGapsAndFillsBalance() {
        List<Operation> ops = List.of(
                op("2026-01-01", Direction.INCOME, "100.00", null),
                op("2026-01-01", Direction.EXPENSE, "30.00", "500.00"),
                op("2026-01-01", Direction.EXPENSE, "20.00", "450.00"),
                // 2026-01-02 and 01-03 have no operations
                op("2026-01-04", Direction.INCOME, "10.00", "460.00")
        );

        List<MlForecastRequest.HistoryDay> h = MlForecastService.buildDailyHistory(ops);

        assertThat(h).hasSize(4);
        assertThat(h.get(0).date()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(h.get(0).inflow()).isEqualTo(100.0);
        assertThat(h.get(0).outflow()).isEqualTo(50.0);
        assertThat(h.get(0).balanceEod()).isEqualTo(450.0); // last balance of the day
        assertThat(h.get(1).inflow()).isZero();
        assertThat(h.get(1).outflow()).isZero();
        assertThat(h.get(1).balanceEod()).isEqualTo(450.0); // forward fill
        assertThat(h.get(2).balanceEod()).isEqualTo(450.0);
        assertThat(h.get(3).balanceEod()).isEqualTo(460.0);
    }

    @Test
    void buildDailyHistoryBackFillsLeadingMissingBalance() {
        List<Operation> ops = List.of(
                op("2026-01-01", Direction.INCOME, "5.00", null),
                op("2026-01-02", Direction.INCOME, "5.00", "200.00")
        );

        List<MlForecastRequest.HistoryDay> h = MlForecastService.buildDailyHistory(ops);

        assertThat(h.get(0).balanceEod()).isEqualTo(200.0);
        assertThat(h.get(1).balanceEod()).isEqualTo(200.0);
    }

    @Test
    void buildDailyHistoryRejectsOperationsWithoutAnyBalance() {
        List<Operation> ops = List.of(op("2026-01-01", Direction.INCOME, "5.00", null));

        assertThatThrownBy(() -> MlForecastService.buildDailyHistory(ops))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forecastSendsHistoryAndMapsResponse() {
        stubCompanyAndHistory(100);
        when(client.forecast(any())).thenReturn(new MlForecastResponse(
                "gradient_boosting", "sklearn-1.6.1", INN, LocalDate.parse("2026-04-10"), 1000.0, 3,
                List.of(
                        new MlForecastResponse.Point(LocalDate.parse("2026-04-11"), -600.0, 400.0, null),
                        new MlForecastResponse.Point(LocalDate.parse("2026-04-12"), -700.0, -300.0, null),
                        new MlForecastResponse.Point(LocalDate.parse("2026-04-13"), -50.0, -350.0, null)
                ),
                List.of(
                        new MlForecastResponse.Gap(LocalDate.parse("2026-04-12"), -300.0, 300.0),
                        new MlForecastResponse.Gap(LocalDate.parse("2026-04-13"), -350.0, 350.0)
                ),
                new MlForecastResponse.Gap(LocalDate.parse("2026-04-12"), -300.0, 300.0),
                new MlForecastResponse.MinBalance(LocalDate.parse("2026-04-13"), -350.0),
                12.4
        ));

        MlForecastResultResponse result = service.forecast(INN, 3, null);

        ArgumentCaptor<MlForecastRequest> captor = ArgumentCaptor.forClass(MlForecastRequest.class);
        verify(client).forecast(captor.capture());
        MlForecastRequest sent = captor.getValue();
        assertThat(sent.model()).isEqualTo("gradient_boosting");
        assertThat(sent.horizonDays()).isEqualTo(3);
        assertThat(sent.industry()).isEqualTo("Retail");
        assertThat(sent.legalForm()).isEqualTo("OOO");
        assertThat(sent.companyCode()).isEqualTo("C001");
        assertThat(sent.history()).hasSize(100);

        assertThat(result.points()).hasSize(3);
        assertThat(result.historyDays()).isEqualTo(100);
        assertThat(result.startBalance()).isEqualByComparingTo("1000.00");
        assertThat(result.cashGaps()).hasSize(2);
        assertThat(result.firstGap().date()).isEqualTo(LocalDate.parse("2026-04-12"));
        assertThat(result.firstGap().deficit()).isEqualByComparingTo("300.00");
        assertThat(result.minBalance().predictedBalance()).isEqualByComparingTo("-350.00");
    }

    @Test
    void forecastDerivesGapsFromPointsWhenMlOmitsThem() {
        MlForecastResponse r = new MlForecastResponse(
                null, null, INN, LocalDate.parse("2026-04-10"), 100.0, null,
                List.of(
                        new MlForecastResponse.Point(LocalDate.parse("2026-04-11"), -10.0, 90.0, null),
                        new MlForecastResponse.Point(LocalDate.parse("2026-04-12"), -100.0, -10.0, null)
                ),
                null, null, null, null
        );

        MlForecastResultResponse result = MlForecastService.toResult(INN, "dynamical", 40, r);

        assertThat(result.model()).isEqualTo("dynamical");
        assertThat(result.horizonDays()).isEqualTo(2);
        assertThat(result.cashGaps()).hasSize(1);
        assertThat(result.firstGap().deficit()).isEqualByComparingTo("10.00");
        assertThat(result.minBalance().date()).isEqualTo(LocalDate.parse("2026-04-12"));
    }

    @Test
    void forecastWithoutGapsHasNullFirstGap() {
        MlForecastResponse r = new MlForecastResponse(
                "dynamical", null, INN, LocalDate.parse("2026-04-10"), 100.0, 1,
                List.of(new MlForecastResponse.Point(LocalDate.parse("2026-04-11"), 5.0, 105.0, 5.0)),
                List.of(), null, null, null
        );

        MlForecastResultResponse result = MlForecastService.toResult(INN, "dynamical", 40, r);

        assertThat(result.cashGaps()).isEmpty();
        assertThat(result.firstGap()).isNull();
    }

    @Test
    void forecastRejectsShortHistoryWithoutCallingMl() {
        stubCompanyAndHistory(50);

        assertThatThrownBy(() -> service.forecast(INN, 30, "gradient_boosting"))
                .isInstanceOfSatisfying(InsufficientHistoryException.class, e -> {
                    assertThat(e.getRequiredDays()).isEqualTo(90);
                    assertThat(e.getActualDays()).isEqualTo(50);
                });
        verifyNoInteractions(client);
    }

    @Test
    void dynamicalModelNeedsOnlyThirtyDays() {
        stubCompanyAndHistory(40);
        when(client.forecast(any())).thenReturn(new MlForecastResponse(
                "dynamical", null, INN, LocalDate.parse("2026-02-09"), 10.0, 1,
                List.of(new MlForecastResponse.Point(LocalDate.parse("2026-02-10"), 1.0, 11.0, 1.0)),
                List.of(), null, null, null));

        MlForecastResultResponse result = service.forecast(INN, 1, "DYNAMICAL");

        assertThat(result.model()).isEqualTo("dynamical");
    }

    @Test
    void mlInsufficientHistoryErrorIsMapped() {
        stubCompanyAndHistory(100);
        HttpClientErrorException http = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", null,
                "{\"error\":\"INSUFFICIENT_HISTORY\",\"message\":\"x\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(client.forecast(any())).thenThrow(new MlServiceClient.MlServiceException("fail", http));

        assertThatThrownBy(() -> service.forecast(INN, 30, "gradient_boosting"))
                .isInstanceOf(InsufficientHistoryException.class);
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> service.forecast(INN, 0, "gradient_boosting"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.forecast(INN, 91, "gradient_boosting"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.forecast(INN, 30, "prophet"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    private void stubCompanyAndHistory(int days) {
        when(companyRepository.findById(INN))
                .thenReturn(Optional.of(new Company(INN, "C001", "Test", "OOO", "Retail")));
        LocalDate start = LocalDate.parse("2026-01-01");
        LocalDate last = start.plusDays(days - 1L);
        List<Operation> ops = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            ops.add(op(start.plusDays(i).toString(), Direction.INCOME, "10.00", String.valueOf(1000 + i) + ".00"));
        }
        when(operationRepository.findLastOperationDate(INN)).thenReturn(Optional.of(last));
        when(operationRepository.findByCompanyInnAndDateRange(anyString(), any(), any())).thenReturn(ops);
    }
}
