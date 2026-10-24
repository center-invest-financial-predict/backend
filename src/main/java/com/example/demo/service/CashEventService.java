package com.example.demo.service;

import com.example.demo.dto.forecast.CalendarResponse;
import com.example.demo.dto.forecast.CashEventDto;
import com.example.demo.dto.forecast.RegularOperationDto;
import com.example.demo.entity.CashEvent;
import com.example.demo.entity.CashEventStatus;
import com.example.demo.entity.Direction;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.CashEventRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CashEventService {

    private final CashEventRepository cashEventRepository;
    private final MlForecastStubService stubService;
    private final RecalcJournalService recalcJournalService;

    @Transactional(readOnly = true)
    public List<CashEventDto> list(String inn, CashEventStatus status, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        return cashEventRepository.findFiltered(inn, status, from, to).stream()
                .map(e -> {
                    CashEventDto dto = CashEventDto.from(e);
                    if (dto.status() == CashEventStatus.EXPECTED && dto.eventDate().isBefore(today)) {
                        return new CashEventDto(
                                dto.id(), dto.companyInn(), dto.eventDate(), dto.title(), dto.direction(),
                                dto.amount(), dto.category(), CashEventStatus.OVERDUE, dto.source(),
                                dto.operationId(), dto.createdAt(), dto.updatedAt()
                        );
                    }
                    return dto;
                })
                .toList();
    }

    @Transactional
    public CashEventDto create(String inn, CashEventDto.CreateRequest request) {
        CashEvent e = new CashEvent();
        e.setCompanyInn(inn);
        e.setEventDate(request.eventDate());
        e.setTitle(request.title());
        e.setDirection(request.direction());
        e.setAmount(request.amount());
        e.setCategory(request.category());
        e.setStatus(request.status() == null ? CashEventStatus.EXPECTED : request.status());
        e.setSource(request.source() == null ? "manual" : request.source());
        CashEvent saved = cashEventRepository.save(e);
        recalcJournalService.record(inn, "Добавлено событие в прогноз: " + saved.getTitle());
        return CashEventDto.from(saved);
    }

    @Transactional
    public CashEventDto update(String inn, Long id, CashEventDto.UpdateRequest request) {
        CashEvent e = cashEventRepository.findById(id)
                .filter(x -> inn.equals(x.getCompanyInn()))
                .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + id));
        if (request.eventDate() != null) e.setEventDate(request.eventDate());
        if (request.title() != null) e.setTitle(request.title());
        if (request.direction() != null) e.setDirection(request.direction());
        if (request.amount() != null) e.setAmount(request.amount());
        if (request.category() != null) e.setCategory(request.category());
        if (request.status() != null) e.setStatus(request.status());
        if (request.source() != null) e.setSource(request.source());
        return CashEventDto.from(cashEventRepository.save(e));
    }

    @Transactional
    public void delete(String inn, Long id) {
        CashEvent e = cashEventRepository.findById(id)
                .filter(x -> inn.equals(x.getCompanyInn()))
                .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + id));
        cashEventRepository.delete(e);
    }

    @Transactional(readOnly = true)
    public CalendarResponse calendar(String inn, YearMonth month) {
        YearMonth ym = month == null ? YearMonth.now() : month;
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        Map<String, List<CashEventDto>> days = new LinkedHashMap<>();
        for (CashEvent e : cashEventRepository.findByCompanyInnAndEventDateBetweenOrderByEventDateAscIdAsc(inn, from, to)) {
            days.computeIfAbsent(e.getEventDate().toString(), k -> new ArrayList<>()).add(CashEventDto.from(e));
        }
        return new CalendarResponse(ym, days);
    }

    @Transactional(readOnly = true)
    public List<RegularOperationDto> regular(String inn) {
        return stubService.regularOperations(inn);
    }

    @Transactional
    public List<CashEventDto> importCsv(String inn, MultipartFile file) throws IOException {
        List<CashEventDto> created = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build()
                .parse(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            for (CSVRecord record : parser) {
                CashEventDto.CreateRequest req = new CashEventDto.CreateRequest(
                        LocalDate.parse(required(record, "date", "event_date")),
                        required(record, "title", "name"),
                        Direction.from(required(record, "direction")),
                        new BigDecimal(required(record, "amount")),
                        optional(record, "category"),
                        CashEventStatus.EXPECTED,
                        "future-csv"
                );
                created.add(create(inn, req));
            }
        }
        recalcJournalService.record(inn, "Импортированы будущие платежи: " + created.size() + " шт.");
        return created;
    }

    private static String required(CSVRecord record, String... names) {
        for (String name : names) {
            if (record.isMapped(name) && record.get(name) != null && !record.get(name).isBlank()) {
                return record.get(name).trim();
            }
        }
        throw new IllegalArgumentException("Missing CSV column: " + String.join("/", names));
    }

    private static String optional(CSVRecord record, String name) {
        if (!record.isMapped(name)) {
            return null;
        }
        String v = record.get(name);
        return v == null || v.isBlank() ? null : v.trim();
    }
}
