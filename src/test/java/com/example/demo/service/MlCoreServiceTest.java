package com.example.demo.service;

import com.example.demo.client.MlCoreClient;
import com.example.demo.dto.mlcore.MlCoreApi;
import com.example.demo.dto.mlcore.MlCoreForecastResponse;
import com.example.demo.dto.mlcore.MlCoreHealthResponse;
import com.example.demo.dto.operation.ImportResult;
import com.example.demo.dto.operation.OperationDto;
import com.example.demo.entity.Company;
import com.example.demo.entity.Direction;
import com.example.demo.entity.Operation;
import com.example.demo.entity.OperationSource;
import com.example.demo.exception.InsufficientHistoryException;
import com.example.demo.exception.MlCoreException;
import com.example.demo.forecast.ForecastService;
import com.example.demo.forecast.ForecastTypes.Inputs;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.OperationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MlCoreServiceTest {

    private static final String INN = "0098524470";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);

    private final MlCoreClient client = mock(MlCoreClient.class);
    private final ForecastService forecastService = mock(ForecastService.class);
    private final OperationRepository operationRepository = mock(OperationRepository.class);
    private final CompanyRepository companyRepository = mock(CompanyRepository.class);
    private final OperationImportService importService = mock(OperationImportService.class);
    private final MlCoreService service = new MlCoreService(client, forecastService, operationRepository,
            companyRepository, importService);

    private void givenCompany() {
        when(companyRepository.findById(INN)).thenReturn(
                Optional.of(new Company(INN, "C001", "ООО Тест", "ООО", "Торговля")));
    }

    // ------------------------------------------------------------------ health

    @Test
    void healthIsDisabledWithoutCallingTheServer() {
        when(client.isEnabled()).thenReturn(false);
        when(client.baseUrl()).thenReturn("http://x");
        MlCoreHealthResponse h = service.health();
        assertThat(h.status()).isEqualTo("DISABLED");
        assertThat(h.enabled()).isFalse();
        verify(client, never()).live();
    }

    @Test
    void healthUpReportsLlmMode() {
        when(client.isEnabled()).thenReturn(true);
        when(client.live()).thenReturn(Map.of("status", "ok"));
        when(client.ready()).thenReturn(Map.of("status", "ready", "llm_mode", "local"));
        MlCoreHealthResponse h = service.health();
        assertThat(h.status()).isEqualTo("UP");
        assertThat(h.llmMode()).isEqualTo("local");
        assertThat(h.live()).isTrue();
        assertThat(h.ready()).isTrue();
    }

    @Test
    void healthDegradedAndDownNeverThrow() {
        when(client.isEnabled()).thenReturn(true);
        when(client.live()).thenReturn(Map.of("status", "ok"));
        when(client.ready()).thenThrow(new MlCoreException("boom", 503, null, null));
        assertThat(service.health().status()).isEqualTo("DEGRADED");

        when(client.live()).thenThrow(new MlCoreException("down", 0, null, null));
        MlCoreHealthResponse down = service.health();
        assertThat(down.status()).isEqualTo("DOWN");
        assertThat(down.message()).isEqualTo("down");
    }

    // ------------------------------------------------------------------ forecast

    @Test
    void forecastUsesSameOpeningBalanceAndIsLabelledStubNotUsedOnScreens() {
        givenCompany();
        when(forecastService.inputs(INN)).thenReturn(
                new Inputs(INN, AS_OF, new BigDecimal("123456.78"), 200, List.of(), List.of(), null));
        Operation in = op("o1", Direction.INCOME, "1000.50", AS_OF);
        Operation zero = op("o2", Direction.EXPENSE, "0", AS_OF);
        when(operationRepository.findByCompanyInnAndDateRange(eq(INN), any(), eq(AS_OF)))
                .thenReturn(List.of(in, zero, op("o3", Direction.EXPENSE, "300", AS_OF.minusDays(1))));
        when(client.forecast(any())).thenReturn(new MlCoreApi.ForecastResult(null, null, AS_OF, AS_OF.plusDays(1),
                List.of(new MlCoreApi.DailyBalance(AS_OF.plusDays(1), BigDecimal.TEN, BigDecimal.ZERO,
                        new BigDecimal("123466.78"))),
                null, BigDecimal.ZERO, new BigDecimal("123456.78"), "hash", List.of("forecast_model_stub")));

        MlCoreForecastResponse r = service.forecast(INN);

        ArgumentCaptor<MlCoreApi.ForecastRequest> cap = ArgumentCaptor.forClass(MlCoreApi.ForecastRequest.class);
        verify(client).forecast(cap.capture());
        assertThat(cap.getValue().openingBalance()).isEqualByComparingTo("123456.78");
        assertThat(cap.getValue().asOfDate()).isEqualTo(AS_OF);
        assertThat(cap.getValue().operations()).hasSize(2); // zero-amount row is not sent
        assertThat(cap.getValue().operations().get(0).companyId()).isEqualTo("C001");
        assertThat(cap.getValue().industry()).isEqualTo("Торговля");
        assertThat(cap.getValue().legalForm()).isEqualTo("ООО");
        assertThat(cap.getValue().operations().get(0).direction()).isEqualTo("Поступление");

        assertThat(r.source()).isEqualTo("ml-core");
        assertThat(r.stub()).isTrue(); // is_stub missing -> schema default true
        assertThat(r.usedOnScreens()).isFalse();
        assertThat(r.cashGapProbability()).isNull();
        assertThat(r.days()).hasSize(1);
        assertThat(r.operationsSent()).isEqualTo(2);
        assertThat(r.warnings()).containsExactly("forecast_model_stub");
    }

    @Test
    void forecastPropagatesInsufficientHistoryAndMlCoreFailures() {
        givenCompany();
        doThrow(new InsufficientHistoryException("cash-forecast", 14, 3)).when(forecastService).inputs(INN);
        assertThatThrownBy(() -> service.forecast(INN)).isInstanceOf(InsufficientHistoryException.class);

        doReturn(new Inputs(INN, AS_OF, BigDecimal.ONE, 200, List.of(), List.of(), null))
                .when(forecastService).inputs(INN);
        when(operationRepository.findByCompanyInnAndDateRange(eq(INN), any(), any()))
                .thenReturn(List.of(op("o1", Direction.INCOME, "10", AS_OF)));
        when(client.forecast(any())).thenThrow(new MlCoreException("down", 0, null, null));
        assertThatThrownBy(() -> service.forecast(INN)).isInstanceOf(MlCoreException.class);
    }

    // ------------------------------------------------------------------ confirm

    @Test
    void confirmForcesCompanyGeneratesStableIdsAndRecalculates() {
        OperationDto good = dto(null, "2026-09-01", "15000.50", Direction.INCOME);
        good.setCompanyInn("9999999999"); // must be ignored
        OperationDto sameAgain = dto(null, "2026-09-01", "15000.5", Direction.INCOME);
        OperationDto withId = dto("my-id", "2026-09-02", "10", Direction.EXPENSE);
        OperationDto zero = dto(null, "2026-09-03", "0", Direction.EXPENSE);
        OperationDto noDate = dto(null, null, "5", Direction.EXPENSE);
        when(importService.upsertBatch(eq(INN), eq(OperationSource.CSV), any()))
                .thenReturn(new ImportResult(2, 1, 0, 0));

        ImportResult r = service.confirm(INN, List.of(good, sameAgain, withId, zero, noDate));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OperationDto>> cap = ArgumentCaptor.forClass(List.class);
        verify(importService).upsertBatch(eq(INN), eq(OperationSource.CSV), cap.capture());
        List<OperationDto> sent = cap.getValue();
        assertThat(sent).hasSize(3);
        assertThat(sent).allSatisfy(o -> assertThat(o.getCompanyInn()).isEqualTo(INN));
        assertThat(sent.get(0).getOperationId()).startsWith("mlc-").isEqualTo(sent.get(1).getOperationId());
        assertThat(sent.get(2).getOperationId()).isEqualTo("my-id");
        assertThat(r.getInserted()).isEqualTo(2);
        assertThat(r.getInvalid()).isEqualTo(2);
        verify(forecastService).onDataChanged(eq(INN), anyString());
    }

    @Test
    void confirmWithoutInsertsDoesNotRecalculate() {
        when(importService.upsertBatch(eq(INN), any(), any())).thenReturn(new ImportResult(0, 1, 0, 0));
        service.confirm(INN, List.of(dto("a", "2026-09-01", "1", Direction.INCOME)));
        verify(forecastService, never()).onDataChanged(anyString(), anyString());
    }

    // ------------------------------------------------------------------ helpers

    private static Operation op(String id, Direction direction, String amount, LocalDate date) {
        Operation o = new Operation();
        o.setOperationId(id);
        o.setDirection(direction);
        o.setAmount(new BigDecimal(amount));
        o.setOperationDate(date);
        return o;
    }

    private static OperationDto dto(String id, String date, String amount, Direction direction) {
        OperationDto d = new OperationDto();
        d.setOperationId(id);
        d.setOperationDate(date == null ? null : LocalDate.parse(date));
        d.setAmount(new BigDecimal(amount));
        d.setDirection(direction);
        d.setCounterpartyName("ООО Ромашка");
        d.setPaymentPurpose("Оплата");
        return d;
    }
}
