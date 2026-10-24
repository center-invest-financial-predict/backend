package com.example.demo.forecast;

import com.example.demo.entity.Operation;
import com.example.demo.repository.CashEventRepository;
import com.example.demo.repository.OperationRepository;
import com.example.demo.service.RecalcJournalService;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/** Mock-backed {@link ForecastService} over the synthetic company, shared by tests that need a real forecast run. */
public final class ForecastFixture {

    public static final String INN = SyntheticCompany.INN;
    public static final LocalDate AS_OF = SyntheticCompany.AS_OF;

    public final OperationRepository operations = Mockito.mock(OperationRepository.class);
    public final List<Operation> ops = SyntheticCompany.operations(240, 1_000_000);
    public final ForecastService service;

    public ForecastFixture(java.util.function.Function<OperationRepository, ServerForecastSource> sourceFactory) {
        CashEventRepository events = Mockito.mock(CashEventRepository.class);
        RecalcJournalService journal = Mockito.mock(RecalcJournalService.class);
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
                .thenReturn(List.of());
        this.service = new ForecastService(operations, events, new RegularDetector(new CategoryClassifier()),
                new ForecastEngine(), journal, sourceFactory.apply(operations),
                Mockito.mock(com.example.demo.repository.CompanySourceRepository.class));
    }
}
