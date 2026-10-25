package com.example.demo.service;

import com.example.demo.dto.operation.OperationDto;
import com.example.demo.entity.Direction;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CsvOperationParserTest {

    private final CsvOperationParser parser = new CsvOperationParser();

    @Test
    void parsesBomSemicolonLeadingZerosAndEmptyCounterpartyInn() throws Exception {
        String csv = """
                \uFEFFcompany_id;operation_id;operation_date;amount;direction;counterparty_inn;counterparty_name;payment_purpose;balance_eod
                C001;C001-000001;2024-10-01;990.00;Списание;0068275202;ПАО «Банк»;Комиссия;1244821.98
                C001;C001-000002;2024-10-02;170892.06;Поступление;;ООО «Клиент»;Оплата;1527714.04
                C001;BAD;not-a-date;x;Списание;1;Name;Purpose;1.00
                """;

        CsvOperationParser.ParseResult result = parser.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))
        );

        assertThat(result.invalid()).isEqualTo(1);
        assertThat(result.operations()).hasSize(2);

        OperationDto first = result.operations().get(0);
        assertThat(first.getCompanyId()).isEqualTo("C001");
        assertThat(first.getOperationId()).isEqualTo("C001-000001");
        assertThat(first.getCounterpartyInn()).isEqualTo("0068275202");
        assertThat(first.getDirection()).isEqualTo(Direction.EXPENSE);
        assertThat(first.getAmount()).isEqualByComparingTo(new BigDecimal("990.00"));

        OperationDto second = result.operations().get(1);
        assertThat(second.getDirection()).isEqualTo(Direction.INCOME);
        assertThat(second.getCounterpartyInn()).isNull();
    }

    @Test
    void parsesExcelCsvWithWindows1251CommaDelimiterRussianDatesAndDecimalComma() throws Exception {
        String csv = "operation_id,operation_date,amount,direction,counterparty_name\r\n"
                + "A1,01.10.2024,\"1 234,50\",Списание,ООО Ромашка\r\n"
                + "A2,02.10.2024,\"99,00\",Поступление,ООО Клиент\r\n";

        CsvOperationParser.ParseResult result = parser.parse(
                new ByteArrayInputStream(csv.getBytes(java.nio.charset.Charset.forName("windows-1251"))));

        assertThat(result.invalid()).isZero();
        assertThat(result.operations()).hasSize(2);
        assertThat(result.operations().get(0).getAmount()).isEqualByComparingTo("1234.50");
        assertThat(result.operations().get(0).getOperationDate()).isEqualTo(java.time.LocalDate.of(2024, 10, 1));
        assertThat(result.operations().get(0).getDirection()).isEqualTo(Direction.EXPENSE);
        assertThat(result.operations().get(0).getCounterpartyName()).isEqualTo("ООО Ромашка");
    }

    @Test
    void parsesRussianHeadersAndDerivesDirectionFromSign() throws Exception {
        String csv = "sep=;\n№;Дата;Сумма;Контрагент\n1;01.10.2024;-100,5;A\n2;02.10.2024;50;B\n";

        CsvOperationParser.ParseResult result = parser.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.invalid()).isZero();
        assertThat(result.operations().get(0).getDirection()).isEqualTo(Direction.EXPENSE);
        assertThat(result.operations().get(0).getAmount()).isEqualByComparingTo("100.5");
        assertThat(result.operations().get(1).getDirection()).isEqualTo(Direction.INCOME);
    }

    @Test
    void parsesXlsxWorkbook() throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/sharedStrings.xml"));
            zip.write(("<sst><si><t>operation_id</t></si><si><t>operation_date</t></si><si><t>amount</t></si>"
                    + "<si><t>direction</t></si><si><t>X1</t></si><si><t>Списание</t></si></sst>")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(("<worksheet><sheetData>"
                    + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c>"
                    + "<c r=\"C1\" t=\"s\"><v>2</v></c><c r=\"D1\" t=\"s\"><v>3</v></c></row>"
                    + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>4</v></c><c r=\"B2\"><v>45566</v></c>"
                    + "<c r=\"C2\"><v>990.5</v></c><c r=\"D2\" t=\"s\"><v>5</v></c></row>"
                    + "</sheetData></worksheet>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        CsvOperationParser.ParseResult result = parser.parse(new ByteArrayInputStream(out.toByteArray()));

        assertThat(result.invalid()).isZero();
        assertThat(result.operations()).hasSize(1);
        assertThat(result.operations().get(0).getOperationId()).isEqualTo("X1");
        assertThat(result.operations().get(0).getOperationDate()).isEqualTo(java.time.LocalDate.of(2024, 10, 1));
        assertThat(result.operations().get(0).getAmount()).isEqualByComparingTo("990.5");
        assertThat(result.operations().get(0).getDirection()).isEqualTo(Direction.EXPENSE);
    }
}
