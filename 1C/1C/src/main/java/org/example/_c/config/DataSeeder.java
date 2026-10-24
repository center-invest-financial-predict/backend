package org.example._c.config;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.example._c.entity.Company;
import org.example._c.entity.Operation;
import org.example._c.repository.CompanyRepository;
import org.example._c.repository.OperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final CompanyRepository companyRepository;
    private final OperationRepository operationRepository;
    private final Path dataDir;
    private final Set<String> seedCompanyCodes;
    private final String filterMode;
    private final String source;

    public DataSeeder(
            CompanyRepository companyRepository,
            OperationRepository operationRepository,
            @Value("${data.dir}") String dataDir,
            @Value("${data.seed-company-codes:C001,C002,C003}") String seedCompanyCodes,
            @Value("${integration.filter-mode:all}") String filterMode,
            @Value("${integration.source}") String source
    ) {
        this.companyRepository = companyRepository;
        this.operationRepository = operationRepository;
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
        this.seedCompanyCodes = new HashSet<>();
        Arrays.stream(seedCompanyCodes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(this.seedCompanyCodes::add);
        this.filterMode = filterMode;
        this.source = source;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (companyRepository.count() > 0) {
            log.info("1C/Gos DB already seeded (companies={})", companyRepository.count());
            ensureDemoOperations();
            return;
        }

        Path companiesFile = dataDir.resolve("companies.csv");
        if (!Files.exists(companiesFile)) {
            log.warn("companies.csv not found at {}, inserting demo companies only", companiesFile);
            seedDemoCompanies();
            ensureDemoOperations();
            return;
        }

        seedCompanies(companiesFile);
        for (Company company : companyRepository.findAll()) {
            seedOperations(company);
        }
        ensureDemoOperations();
        log.info("Seeded {} companies and {} operations for source={}",
                companyRepository.count(), operationRepository.count(), source);
    }

    private void seedDemoCompanies() {
        companyRepository.save(new Company("0098524470", "C001", "ООО «Грузоперевозки-063»", "ООО", "Грузоперевозки"));
        companyRepository.save(new Company("003844671674", "C002", "ИП Предприниматель-156", "ИП", "Грузоперевозки"));
        companyRepository.save(new Company("0040581584", "C003", "ООО «Агро-191»", "ООО", "Сельское хозяйство"));
    }

    private void seedCompanies(Path companiesFile) throws IOException {
        try (BufferedReader reader = openCsvReader(companiesFile);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setDelimiter(';')
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            for (CSVRecord record : parser) {
                String code = stripBom(get(record, "company_id"));
                if (!seedCompanyCodes.contains(code)) {
                    continue;
                }
                Company company = new Company(
                        stripBom(get(record, "company_inn")),
                        code,
                        stripBom(get(record, "company_name")),
                        blankToNull(optional(record, "legal_form")),
                        blankToNull(optional(record, "industry"))
                );
                companyRepository.save(company);
            }
        }
        if (companyRepository.count() == 0) {
            seedDemoCompanies();
        }
    }

    private void seedOperations(Company company) throws IOException {
        Path statement = dataDir.resolve("statements").resolve(company.getCompanyCode() + ".csv");
        if (!Files.exists(statement)) {
            log.warn("Statement not found for {}: {}", company.getCompanyCode(), statement);
            return;
        }

        int index = 0;
        try (BufferedReader reader = openCsvReader(statement);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setDelimiter(';')
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setTrim(true)
                     .setIgnoreEmptyLines(true)
                     .build()
                     .parse(reader)) {
            for (CSVRecord record : parser) {
                try {
                    Operation operation = toOperation(company.getInn(), record);
                    if ("gos".equalsIgnoreCase(filterMode) && !includeForGos(operation, index)) {
                        index++;
                        continue;
                    }
                    if (!operationRepository.existsByCompanyInnAndOperationId(company.getInn(), operation.getOperationId())) {
                        operationRepository.save(operation);
                    }
                    index++;
                } catch (RuntimeException ex) {
                    // skip malformed
                }
            }
        }
    }

    private boolean includeForGos(Operation operation, int index) {
        boolean everySecond = index % 2 == 1;
        String purpose = operation.getPaymentPurpose() == null ? "" : operation.getPaymentPurpose().toLowerCase(Locale.ROOT);
        String name = operation.getCounterpartyName() == null ? "" : operation.getCounterpartyName().toLowerCase(Locale.ROOT);
        boolean treasury = purpose.contains("казначейств") || purpose.contains("уфк")
                || name.contains("казначейств") || name.contains("уфк");
        return everySecond || treasury;
    }

    private void ensureDemoOperations() {
        for (Company company : companyRepository.findAll()) {
            String prefix = "1C".equalsIgnoreCase(source) ? "1C-DEMO" : "GOS-DEMO";
            String opId = prefix + "-" + company.getCompanyCode() + "-0001";
            if (operationRepository.existsByCompanyInnAndOperationId(company.getInn(), opId)) {
                continue;
            }
            Operation demo = new Operation();
            demo.setCompanyInn(company.getInn());
            demo.setOperationId(opId);
            demo.setOperationDate(LocalDate.of(2026, 9, 30));
            demo.setAmount(new BigDecimal("1234.56"));
            demo.setDirection("Поступление");
            demo.setCounterpartyInn("7700000000");
            demo.setCounterpartyName("1C".equalsIgnoreCase(source) ? "ООО «1С Демо»" : "УФК по г. Москве");
            demo.setPaymentPurpose("1C".equalsIgnoreCase(source)
                    ? "Демо-операция 1C для проверки интеграции"
                    : "Единый налоговый платеж (демо Gos_service)");
            demo.setBalanceEod(new BigDecimal("999999.99"));
            operationRepository.save(demo);
        }
    }

    private Operation toOperation(String companyInn, CSVRecord record) {
        Operation operation = new Operation();
        operation.setCompanyInn(companyInn);
        operation.setOperationId(required(record, "operation_id"));
        operation.setOperationDate(LocalDate.parse(required(record, "operation_date")));
        operation.setAmount(new BigDecimal(required(record, "amount")));
        operation.setDirection(required(record, "direction"));
        String counterpartyInn = optional(record, "counterparty_inn");
        operation.setCounterpartyInn(counterpartyInn == null || counterpartyInn.isBlank() ? null : counterpartyInn.trim());
        operation.setCounterpartyName(blankToNull(optional(record, "counterparty_name")));
        operation.setPaymentPurpose(blankToNull(optional(record, "payment_purpose")));
        String balanceRaw = optional(record, "balance_eod");
        operation.setBalanceEod(balanceRaw == null || balanceRaw.isBlank() ? null : new BigDecimal(balanceRaw));
        return operation;
    }

    private static BufferedReader openCsvReader(Path file) throws IOException {
        BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
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
        String value = stripBom(get(record, column));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + column);
        }
        return value.trim();
    }

    private static String optional(CSVRecord record, String column) {
        try {
            return stripBom(get(record, column));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
