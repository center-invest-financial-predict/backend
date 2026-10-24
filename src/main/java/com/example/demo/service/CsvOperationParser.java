package com.example.demo.service;

import com.example.demo.dto.operation.OperationDto;
import com.example.demo.entity.Direction;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class CsvOperationParser {

    public record ParseResult(List<OperationDto> operations, int invalid) {
    }

    public ParseResult parse(InputStream inputStream) throws IOException {
        List<OperationDto> operations = new ArrayList<>();
        int invalid = 0;

        try (BufferedReader reader = openCsvReader(inputStream);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setDelimiter(';')
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .setAllowMissingColumnNames(true)
                     .build()
                     .parse(reader)) {

            for (CSVRecord record : parser) {
                try {
                    operations.add(toDto(record));
                } catch (RuntimeException ex) {
                    invalid++;
                }
            }
        }

        return new ParseResult(operations, invalid);
    }

    private OperationDto toDto(CSVRecord record) {
        String companyId = blankToNull(optional(record, "company_id"));
        String companyInn = blankToNull(optional(record, "company_inn"));
        if (companyInn == null) {
            companyInn = blankToNull(optional(record, "inn"));
        }

        String operationId = required(record, "operation_id");
        LocalDate operationDate = LocalDate.parse(required(record, "operation_date"));
        BigDecimal amount = new BigDecimal(required(record, "amount"));
        Direction direction = Direction.from(required(record, "direction"));

        String counterpartyInn = optional(record, "counterparty_inn");
        if (counterpartyInn != null && counterpartyInn.isBlank()) {
            counterpartyInn = null;
        }

        String counterpartyName = optional(record, "counterparty_name");
        String paymentPurpose = optional(record, "payment_purpose");
        String balanceRaw = optional(record, "balance_eod");
        BigDecimal balanceEod = (balanceRaw == null || balanceRaw.isBlank())
                ? null
                : new BigDecimal(balanceRaw);

        return new OperationDto(
                companyId,
                companyInn,
                operationId,
                operationDate,
                amount,
                direction,
                counterpartyInn,
                blankToNull(counterpartyName),
                blankToNull(paymentPurpose),
                balanceEod
        );
    }

    private static BufferedReader openCsvReader(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        reader.mark(1);
        int first = reader.read();
        if (first != '\uFEFF') {
            reader.reset();
        }
        return reader;
    }

    private static String get(CSVRecord record, String column) {
        if (record.isMapped(column)) {
            return record.get(column);
        }
        String bomColumn = "\uFEFF" + column;
        if (record.isMapped(bomColumn)) {
            return record.get(bomColumn);
        }
        throw new IllegalArgumentException("Missing column: " + column);
    }

    private static String required(CSVRecord record, String column) {
        String value = get(record, column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Empty value for column: " + column);
        }
        if (value.charAt(0) == '\uFEFF') {
            value = value.substring(1);
        }
        return value.trim();
    }

    private static String optional(CSVRecord record, String column) {
        try {
            String value = get(record, column);
            if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
                value = value.substring(1);
            }
            return value;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
