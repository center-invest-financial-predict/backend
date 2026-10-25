package com.example.demo.service;

import com.example.demo.dto.operation.OperationDto;
import com.example.demo.entity.Direction;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads bank statements exported from Excel: either a CSV (UTF-8 / Windows-1251, delimiter ';' ',' or TAB,
 * dd.MM.yyyy or ISO dates, decimal comma) or a real .xlsx workbook (first sheet). The header row may use the
 * canonical English names or common Russian ones.
 */
@Slf4j
@Component
public class CsvOperationParser {

    public record ParseResult(List<OperationDto> operations, int invalid, List<String> errors) {
        public ParseResult(List<OperationDto> operations, int invalid) {
            this(operations, invalid, List.of());
        }
    }

    private static final int MAX_LOGGED_ERRORS = 5;
    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");
    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};

    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        alias("company_id", "company_id", "companyid", "company_code", "код_компании", "компания");
        alias("company_inn", "company_inn", "inn", "инн", "инн_компании");
        alias("operation_id", "operation_id", "operationid", "id", "id_операции", "ид_операции",
                "номер_операции", "номер", "№", "идентификатор");
        alias("operation_date", "operation_date", "date", "дата", "дата_операции", "дата_платежа");
        alias("amount", "amount", "sum", "сумма", "сумма_операции");
        alias("direction", "direction", "type", "тип", "тип_операции", "направление", "вид_операции");
        alias("counterparty_inn", "counterparty_inn", "инн_контрагента", "контрагент_инн");
        alias("counterparty_name", "counterparty_name", "counterparty", "контрагент",
                "наименование_контрагента", "название_контрагента");
        alias("payment_purpose", "payment_purpose", "purpose", "назначение", "назначение_платежа",
                "описание", "комментарий");
        alias("balance_eod", "balance_eod", "balance", "остаток", "остаток_на_конец_дня", "баланс");
    }

    private static void alias(String canonical, String... names) {
        for (String name : names) {
            ALIASES.put(name, canonical);
        }
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d.M.uuuu"),
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ofPattern("uuuu/M/d"),
            DateTimeFormatter.ofPattern("uuuu.M.d"),
            DateTimeFormatter.ofPattern("d.M.uu")
    );

    public ParseResult parse(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        List<List<String>> rows = isZip(bytes) ? readXlsx(bytes) : readCsv(bytes);
        return toOperations(rows);
    }

    // ---------------------------------------------------------------- rows -> operations

    private ParseResult toOperations(List<List<String>> rows) {
        List<OperationDto> operations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int invalid = 0;

        int headerIdx = 0;
        while (headerIdx < rows.size() && isBlankRow(rows.get(headerIdx))) {
            headerIdx++;
        }
        if (headerIdx >= rows.size()) {
            return new ParseResult(operations, 0, errors);
        }

        Map<String, Integer> columns = new HashMap<>();
        List<String> header = rows.get(headerIdx);
        for (int i = 0; i < header.size(); i++) {
            String canonical = ALIASES.get(normalizeHeader(header.get(i)));
            if (canonical != null) {
                columns.putIfAbsent(canonical, i);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String required : List.of("operation_date", "amount")) {
            if (!columns.containsKey(required)) {
                missing.add(required);
            }
        }
        if (!columns.containsKey("direction")) {
            log.info("CSV has no direction column: direction will be derived from the sign of amount");
        }
        if (!missing.isEmpty()) {
            int dataRows = 0;
            for (int i = headerIdx + 1; i < rows.size(); i++) {
                if (!isBlankRow(rows.get(i))) {
                    dataRows++;
                }
            }
            log.warn("CSV rejected: required columns {} not found in header {}", missing, header);
            errors.add("Не найдены колонки " + missing + ", заголовок: " + header);
            return new ParseResult(operations, dataRows, errors);
        }

        Map<String, Integer> generatedIdCounters = new HashMap<>();
        for (int i = headerIdx + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (isBlankRow(row)) {
                continue;
            }
            try {
                operations.add(toDto(row, columns, generatedIdCounters));
            } catch (RuntimeException ex) {
                invalid++;
                if (errors.size() < MAX_LOGGED_ERRORS) {
                    errors.add("Строка " + (i + 1) + ": " + ex.getMessage());
                }
            }
        }
        if (invalid > 0) {
            log.warn("CSV: {} invalid rows, first errors: {}", invalid, errors);
        }
        return new ParseResult(operations, invalid, errors);
    }

    private OperationDto toDto(List<String> row, Map<String, Integer> columns, Map<String, Integer> counters) {
        String companyId = blankToNull(cell(row, columns, "company_id"));
        String companyInn = blankToNull(cell(row, columns, "company_inn"));

        LocalDate operationDate = parseDate(requiredCell(row, columns, "operation_date"));
        BigDecimal amount = parseNumber(requiredCell(row, columns, "amount"), "amount");

        String directionRaw = blankToNull(cell(row, columns, "direction"));
        Direction direction;
        if (directionRaw != null) {
            direction = parseDirection(directionRaw);
        } else {
            direction = amount.signum() < 0 ? Direction.EXPENSE : Direction.INCOME;
            amount = amount.abs();
        }

        String counterpartyInn = blankToNull(cell(row, columns, "counterparty_inn"));
        String counterpartyName = blankToNull(cell(row, columns, "counterparty_name"));
        String paymentPurpose = blankToNull(cell(row, columns, "payment_purpose"));
        String balanceRaw = blankToNull(cell(row, columns, "balance_eod"));
        BigDecimal balanceEod = balanceRaw == null ? null : parseNumber(balanceRaw, "balance_eod");

        String operationId = blankToNull(cell(row, columns, "operation_id"));
        if (operationId == null) {
            if (columns.containsKey("operation_id")) {
                throw new IllegalArgumentException("Empty value for column: operation_id");
            }
            // No id column at all: derive a stable id so re-uploading the same file yields duplicates, not copies.
            String key = String.join("|", operationDate.toString(), amount.stripTrailingZeros().toPlainString(),
                    direction.name(), String.valueOf(counterpartyInn), String.valueOf(counterpartyName),
                    String.valueOf(paymentPurpose));
            int n = counters.merge(key, 1, Integer::sum);
            operationId = "gen-" + Integer.toHexString(key.hashCode()) + "-" + n;
        }

        return new OperationDto(companyId, companyInn, operationId, operationDate, amount, direction,
                counterpartyInn, counterpartyName, paymentPurpose, balanceEod);
    }

    private static String cell(List<String> row, Map<String, Integer> columns, String name) {
        Integer idx = columns.get(name);
        if (idx == null || idx >= row.size()) {
            return null;
        }
        String value = row.get(idx);
        return value == null ? null : cleanCell(value);
    }

    private static String requiredCell(List<String> row, Map<String, Integer> columns, String name) {
        String value = blankToNull(cell(row, columns, name));
        if (value == null) {
            throw new IllegalArgumentException("Empty value for column: " + name);
        }
        return value;
    }

    // ---------------------------------------------------------------- value parsing

    static LocalDate parseDate(String raw) {
        String value = raw.trim();
        // Excel serial date ("45566" or "45566.0")
        if (value.matches("\\d{5}(\\.0+)?")) {
            long serial = Long.parseLong(value.split("\\.")[0]);
            return LocalDate.of(1899, 12, 30).plusDays(serial);
        }
        int cut = value.indexOf(' ');
        int t = value.indexOf('T');
        if (t > 0 && (cut < 0 || t < cut)) {
            cut = t;
        }
        String datePart = cut > 0 ? value.substring(0, cut) : value;
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(datePart, format);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        throw new IllegalArgumentException("Bad date: " + raw);
    }

    static BigDecimal parseNumber(String raw, String column) {
        String value = raw.replace("\u00A0", "").replace("\u202F", "").replace(" ", "")
                .replace("₽", "").replace("р.", "").replace("руб.", "").trim();
        boolean negativeParens = value.startsWith("(") && value.endsWith(")");
        if (negativeParens) {
            value = "-" + value.substring(1, value.length() - 1);
        }
        int comma = value.lastIndexOf(',');
        int dot = value.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            if (comma > dot) {
                value = value.replace(".", "").replace(',', '.');
            } else {
                value = value.replace(",", "");
            }
        } else if (comma >= 0) {
            value = value.replace(',', '.');
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Bad number in " + column + ": " + raw);
        }
    }

    static Direction parseDirection(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "приход", "поступление", "доход", "in", "income", "credit", "кредит", "+":
                return Direction.INCOME;
            case "расход", "списание", "out", "expense", "debit", "дебет", "-", "−":
                return Direction.EXPENSE;
            default:
                return Direction.from(raw);
        }
    }

    private static String normalizeHeader(String header) {
        if (header == null) {
            return "";
        }
        String value = cleanCell(header).toLowerCase(Locale.ROOT).replace('ё', 'е');
        return value.replaceAll("[\\s\\-]+", "_");
    }

    private static String cleanCell(String value) {
        String v = value;
        if (!v.isEmpty() && v.charAt(0) == '\uFEFF') {
            v = v.substring(1);
        }
        return v.replace("\u00A0", " ").trim();
    }

    private static boolean isBlankRow(List<String> row) {
        for (String cell : row) {
            if (cell != null && !cleanCell(cell).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ---------------------------------------------------------------- CSV reading

    private static List<List<String>> readCsv(byte[] bytes) throws IOException {
        String text = decode(bytes);
        text = stripSepLine(text);
        char delimiter = detectDelimiter(text);

        List<List<String>> rows = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build()
                .parse(new StringReader(text))) {
            for (CSVRecord record : parser) {
                List<String> row = new ArrayList<>(record.size());
                for (String value : record) {
                    row.add(value);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static String decode(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        int offset = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException ex) {
            // "CSV" saved by Excel on Windows is Windows-1251
            return new String(bytes, offset, bytes.length - offset, WINDOWS_1251);
        }
    }

    /** Excel may start the file with a "sep=;" hint line. */
    private static String stripSepLine(String text) {
        if (text.regionMatches(true, 0, "sep=", 0, 4)) {
            int nl = text.indexOf('\n');
            return nl < 0 ? "" : text.substring(nl + 1);
        }
        return text;
    }

    private static char detectDelimiter(String text) {
        int semicolons = 0;
        int commas = 0;
        int tabs = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '\n') {
                    if (semicolons + commas + tabs > 0) {
                        break; // the first non-empty line decides
                    }
                } else if (c == ';') {
                    semicolons++;
                } else if (c == ',') {
                    commas++;
                } else if (c == '\t') {
                    tabs++;
                }
            }
        }
        if (semicolons >= commas && semicolons >= tabs && semicolons > 0) {
            return ';';
        }
        if (tabs > commas) {
            return '\t';
        }
        return commas > 0 ? ',' : ';';
    }

    // ---------------------------------------------------------------- XLSX reading

    private static boolean isZip(byte[] bytes) {
        if (bytes.length < ZIP_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (bytes[i] != ZIP_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static List<List<String>> readXlsx(byte[] bytes) throws IOException {
        byte[] sharedStrings = null;
        TreeMap<String, byte[]> sheets = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("xl/sharedStrings.xml")) {
                    sharedStrings = zip.readAllBytes();
                } else if (name.startsWith("xl/worksheets/") && name.endsWith(".xml")) {
                    sheets.put(name, zip.readAllBytes());
                }
            }
        }
        if (sheets.isEmpty()) {
            throw new IOException("Excel file has no worksheets");
        }
        try {
            List<String> strings = sharedStrings == null ? List.of() : readSharedStrings(sharedStrings);
            // natural order: sheet1, sheet2, ..., sheet10
            String first = sheets.keySet().stream()
                    .min((a, b) -> Integer.compare(sheetNumber(a), sheetNumber(b)))
                    .orElseThrow();
            return readSheet(sheets.get(first), strings);
        } catch (XMLStreamException ex) {
            throw new IOException("Cannot read Excel file: " + ex.getMessage(), ex);
        }
    }

    private static int sheetNumber(String name) {
        String digits = name.replaceAll("\\D", "");
        return digits.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(digits);
    }

    private static XMLStreamReader xml(byte[] data) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory.createXMLStreamReader(new ByteArrayInputStream(data));
    }

    private static List<String> readSharedStrings(byte[] data) throws XMLStreamException {
        List<String> result = new ArrayList<>();
        XMLStreamReader r = xml(data);
        StringBuilder current = null;
        boolean inText = false;
        int phoneticDepth = 0;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = r.getLocalName();
                if (name.equals("si")) {
                    current = new StringBuilder();
                } else if (name.equals("rPh")) {
                    phoneticDepth++;
                } else if (name.equals("t") && current != null && phoneticDepth == 0) {
                    inText = true;
                }
            } else if (event == XMLStreamConstants.CHARACTERS && inText) {
                current.append(r.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String name = r.getLocalName();
                if (name.equals("t")) {
                    inText = false;
                } else if (name.equals("rPh")) {
                    phoneticDepth--;
                } else if (name.equals("si") && current != null) {
                    result.add(current.toString());
                    current = null;
                }
            }
        }
        r.close();
        return result;
    }

    private static List<List<String>> readSheet(byte[] data, List<String> strings) throws XMLStreamException {
        List<List<String>> rows = new ArrayList<>();
        XMLStreamReader r = xml(data);
        List<String> row = null;
        String cellType = null;
        int cellCol = 0;
        StringBuilder value = null;
        boolean inValue = false;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> row = new ArrayList<>();
                    case "c" -> {
                        cellType = r.getAttributeValue(null, "t");
                        cellCol = columnIndex(r.getAttributeValue(null, "r"), row == null ? 0 : row.size());
                        value = new StringBuilder();
                    }
                    case "v", "t" -> inValue = value != null;
                    default -> { }
                }
            } else if (event == XMLStreamConstants.CHARACTERS && inValue) {
                value.append(r.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                switch (r.getLocalName()) {
                    case "v", "t" -> inValue = false;
                    case "c" -> {
                        if (row != null && value != null) {
                            while (row.size() < cellCol) {
                                row.add("");
                            }
                            row.add(resolveCell(cellType, value.toString(), strings));
                        }
                        value = null;
                    }
                    case "row" -> {
                        if (row != null) {
                            rows.add(row);
                        }
                        row = null;
                    }
                    default -> { }
                }
            }
        }
        r.close();
        return rows;
    }

    private static String resolveCell(String type, String raw, List<String> strings) {
        if ("s".equals(type)) {
            try {
                int idx = Integer.parseInt(raw.trim());
                return idx >= 0 && idx < strings.size() ? strings.get(idx) : "";
            } catch (NumberFormatException ex) {
                return "";
            }
        }
        if (type == null || "n".equals(type)) {
            String v = raw.trim();
            // integer stored as a double ("1234.0") — keep ids and INNs clean
            if (v.matches("-?\\d+\\.0+")) {
                return v.substring(0, v.indexOf('.'));
            }
            return v;
        }
        return raw;
    }

    private static int columnIndex(String ref, int fallback) {
        if (ref == null) {
            return fallback;
        }
        int col = 0;
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        return i == 0 ? fallback : col - 1;
    }
}
