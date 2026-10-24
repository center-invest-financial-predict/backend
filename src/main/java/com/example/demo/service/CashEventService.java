package com.example.demo.service;

import com.example.demo.dto.forecast.CalendarResponse;
import com.example.demo.dto.forecast.CashEventDto;
import com.example.demo.dto.forecast.RegularOperationDto;
import com.example.demo.entity.CashEvent;
import com.example.demo.entity.CashEventStatus;
import com.example.demo.entity.Direction;
import com.example.demo.exception.InsufficientHistoryException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.forecast.ForecastService;
import com.example.demo.forecast.RegularItem;
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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Expected payments/receipts. Every change goes through {@link ForecastService#onDataChanged}, so the forecast,
 * alerts and the recalculation journal reflect it immediately.
 */
@Service
@RequiredArgsConstructor
public class CashEventService {

    private static final DateTimeFormatter RU_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final CashEventRepository cashEventRepository;
    private final ForecastService forecastService;

    @Transactional(readOnly = true)
    public List<CashEventDto> list(String inn, CashEventStatus status, LocalDate from, LocalDate to) {
        LocalDate ref = reference(inn);
        // OVERDUE is partly derived (EXPECTED in the past), so filter by effective status, not the stored one.
        return cashEventRepository.findFiltered(inn, null, from, to).stream()
                .map(e -> toDto(e, ref))
                .filter(dto -> status == null || dto.status() == status)
                .toList();
    }

    @Transactional
    public CashEventDto create(String inn, CashEventDto.CreateRequest request) {
        CashEvent saved = cashEventRepository.save(build(inn, request));
        forecastService.onDataChanged(inn, "Добавлено событие «" + saved.getTitle() + "» ("
                + saved.getEventDate() + ")");
        return toDto(saved, reference(inn));
    }

    @Transactional
    public CashEventDto update(String inn, Long id, CashEventDto.UpdateRequest request) {
        CashEvent e = require(inn, id);
        if (request.eventDate() != null) e.setEventDate(request.eventDate());
        if (request.title() != null) e.setTitle(request.title());
        if (request.direction() != null) e.setDirection(request.direction());
        if (request.amount() != null) {
            requirePositive(request.amount());
            e.setAmount(request.amount());
        }
        if (request.category() != null) e.setCategory(request.category());
        if (request.status() != null) e.setStatus(request.status());
        if (request.source() != null) e.setSource(request.source());
        CashEvent saved = cashEventRepository.save(e);
        forecastService.onDataChanged(inn, "Изменено событие «" + saved.getTitle() + "»");
        return toDto(saved, reference(inn));
    }

    @Transactional
    public void delete(String inn, Long id) {
        CashEvent e = require(inn, id);
        cashEventRepository.delete(e);
        forecastService.onDataChanged(inn, "Удалено событие «" + e.getTitle() + "»");
    }

    @Transactional(readOnly = true)
    public CalendarResponse calendar(String inn, YearMonth month) {
        YearMonth ym = month == null ? YearMonth.now() : month;
        LocalDate ref = reference(inn);
        Map<String, List<CashEventDto>> days = new LinkedHashMap<>();
        for (CashEvent e : cashEventRepository.findByCompanyInnAndEventDateBetweenOrderByEventDateAscIdAsc(
                inn, ym.atDay(1), ym.atEndOfMonth())) {
            days.computeIfAbsent(e.getEventDate().toString(), k -> new ArrayList<>()).add(toDto(e, ref));
        }
        return new CalendarResponse(ym, days);
    }

    @Transactional(readOnly = true)
    public List<RegularOperationDto> regular(String inn) {
        LocalDate asOf = forecastService.asOf(inn);
        List<RegularOperationDto> out = new ArrayList<>();
        for (RegularItem r : forecastService.regulars(inn)) {
            out.add(new RegularOperationDto(
                    Integer.toHexString(r.key().hashCode()), r.title(), r.category(), r.amount(),
                    r.direction().name(), r.nextDate(asOf), r.cadence().name().toLowerCase(Locale.ROOT),
                    r.cadence().label(), r.lastDate(), r.occurrences(), r.confidence(), r.amountUnstable()));
        }
        return out;
    }

    /** Imports future payments from CSV; the forecast is recalculated once for the whole file. */
    @Transactional
    public List<CashEventDto> importCsv(String inn, MultipartFile file) throws IOException {
        List<CashEvent> created = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build()
                .parse(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            for (CSVRecord record : parser) {
                long line = record.getRecordNumber() + 1;
                try {
                    CashEventDto.CreateRequest req = new CashEventDto.CreateRequest(
                            parseDate(required(record, "date", "event_date", "дата")),
                            required(record, "title", "name", "название"),
                            Direction.from(required(record, "direction", "направление")),
                            new BigDecimal(required(record, "amount", "сумма").replace(" ", "").replace(',', '.')),
                            optional(record, "category"),
                            CashEventStatus.EXPECTED,
                            "future-csv"
                    );
                    created.add(cashEventRepository.save(build(inn, req)));
                } catch (IllegalArgumentException | DateTimeParseException e) {
                    throw new IllegalArgumentException("CSV line " + line + ": " + e.getMessage());
                }
            }
        }
        if (!created.isEmpty()) {
            forecastService.onDataChanged(inn, "Импортированы будущие платежи: " + created.size() + " шт.");
        }
        LocalDate ref = reference(inn);
        return created.stream().map(e -> toDto(e, ref)).toList();
    }

    // ---------------------------------------------------------------- helpers

    private CashEvent build(String inn, CashEventDto.CreateRequest request) {
        requirePositive(request.amount());
        CashEvent e = new CashEvent();
        e.setCompanyInn(inn);
        e.setEventDate(request.eventDate());
        e.setTitle(request.title().trim());
        e.setDirection(request.direction());
        e.setAmount(request.amount());
        e.setCategory(request.category());
        e.setStatus(request.status() == null ? CashEventStatus.EXPECTED : request.status());
        e.setSource(request.source() == null ? "manual" : request.source());
        return e;
    }

    private CashEvent require(String inn, Long id) {
        return cashEventRepository.findById(id)
                .filter(x -> inn.equals(x.getCompanyInn()))
                .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + id));
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Event amount must be positive; direction defines the sign");
        }
    }

    /** The day the data ends: events up to and including it that are not done are overdue. */
    private LocalDate reference(String inn) {
        try {
            return forecastService.asOf(inn);
        } catch (InsufficientHistoryException e) {
            return LocalDate.now();
        }
    }

    private static CashEventDto toDto(CashEvent e, LocalDate ref) {
        CashEventDto dto = CashEventDto.from(e);
        if (dto.status() == CashEventStatus.EXPECTED && !dto.eventDate().isAfter(ref)) {
            return new CashEventDto(dto.id(), dto.companyInn(), dto.eventDate(), dto.title(), dto.direction(),
                    dto.amount(), dto.category(), CashEventStatus.OVERDUE, dto.source(), dto.operationId(),
                    dto.createdAt(), dto.updatedAt());
        }
        return dto;
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return LocalDate.parse(value, RU_DATE);
        }
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
