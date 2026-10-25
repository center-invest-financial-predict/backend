package com.example.demo.service;

import com.example.demo.dto.operation.ImportResult;
import com.example.demo.dto.operation.OperationDto;
import com.example.demo.entity.Company;
import com.example.demo.entity.OperationSource;
import com.example.demo.entity.User;
import com.example.demo.forecast.ForecastService;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.security.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CsvImportService {

    private static final int MAX_SKIP_REASONS = 10;

    private final CsvOperationParser csvOperationParser;
    private final OperationImportService operationImportService;
    private final CompanyRepository companyRepository;
    private final CustomUserDetailsService userDetailsService;
    private final ForecastService forecastService;

    /**
     * Import CSV that may contain many company INNs / company_ids.
     * Only rows belonging to the current user's linked INNs are imported.
     */
    @Transactional
    public ImportResult importForUser(String email, MultipartFile file) throws IOException {
        User user = (User) userDetailsService.loadUserByUsername(email);
        Set<String> userInns = user.getInns() == null ? Set.of() : Set.copyOf(user.getInns());
        if (userInns.isEmpty()) {
            CsvOperationParser.ParseResult parsed = csvOperationParser.parse(file.getInputStream());
            return new ImportResult(0, 0, parsed.invalid(), parsed.operations().size());
        }
        return importFiltered(file.getInputStream(), userInns, null);
    }

    /**
     * Import CSV for one company path INN. Rows for other companies are skipped.
     */
    @Transactional
    public ImportResult importForCompany(String email, String pathInn, MultipartFile file) throws IOException {
        User user = (User) userDetailsService.loadUserByUsername(email);
        Set<String> userInns = user.getInns() == null ? Set.of() : Set.copyOf(user.getInns());
        if (!userInns.contains(pathInn)) {
            throw new com.example.demo.exception.ForbiddenInnException(pathInn);
        }
        return importFiltered(file.getInputStream(), userInns, pathInn);
    }

    private ImportResult importFiltered(InputStream inputStream, Set<String> userInns, String forceInn) throws IOException {
        CsvOperationParser.ParseResult parsed = csvOperationParser.parse(inputStream);
        Map<String, String> codeToInn = loadCodeToInn(parsed.operations());

        Company target = forceInn == null ? null : companyRepository.findById(forceInn).orElse(null);
        String forceCode = target == null ? null : target.getCompanyCode();
        // A company the user brought in has no statement format of ours: the file is its own statement, whatever
        // company_id the exporting system put there (one code or none). Only a file mixing several companies
        // has to be told apart by the company's code or company_inn.
        boolean ownStatement = target != null && target.isUserCreated();
        Set<String> codesInFile = new HashSet<>();
        for (OperationDto dto : parsed.operations()) {
            boolean noInn = dto.getCompanyInn() == null || dto.getCompanyInn().isBlank();
            if (noInn && dto.getCompanyId() != null && !dto.getCompanyId().isBlank()) {
                codesInFile.add(dto.getCompanyId().trim());
            }
        }
        boolean singleCompanyFile = codesInFile.size() <= 1;

        Map<String, List<OperationDto>> byInn = new LinkedHashMap<>();
        // reason -> number of rows; ownership is checked first, duplicates are looked for only afterwards
        Map<String, Integer> skipReasons = new LinkedHashMap<>();
        int skipped = 0;
        int unresolved = 0;

        for (OperationDto dto : parsed.operations()) {
            String code = dto.getCompanyId() == null ? null : dto.getCompanyId().trim();
            String explicitInn = dto.getCompanyInn() == null || dto.getCompanyInn().isBlank()
                    ? null : dto.getCompanyInn().trim();
            String codeInn = code == null || code.isBlank() ? null : codeToInn.get(code);

            if (ownStatement) {
                String own;
                if (explicitInn != null) {
                    own = explicitInn;
                } else if (code == null || code.isBlank() || singleCompanyFile || code.equals(forceCode)) {
                    own = forceInn;
                } else {
                    skipped++;
                    skipReasons.merge("в файле несколько компаний (company_id " + String.join(", ",
                            codesInFile.stream().sorted().limit(5).toList()) + (codesInFile.size() > 5 ? "…" : "")
                            + "); в компанию " + forceInn + " грузятся строки с её кодом " + forceCode
                            + " или с company_inn = " + forceInn, 1, Integer::sum);
                    continue;
                }
                if (!forceInn.equals(own)) {
                    skipped++;
                    skipReasons.merge("company_inn " + own + " не совпадает с ИНН " + forceInn
                            + ", для которого загружен файл", 1, Integer::sum);
                    continue;
                }
                byInn.computeIfAbsent(own, key -> new ArrayList<>()).add(dto);
                continue;
            }

            // company_id and INN given in the row must agree with each other
            if (explicitInn != null && codeInn != null && !explicitInn.equals(codeInn)) {
                skipped++;
                skipReasons.merge("company_id " + code + " (ИНН " + codeInn + ") противоречит company_inn "
                        + explicitInn, 1, Integer::sum);
                continue;
            }
            String resolvedInn = explicitInn != null ? explicitInn : codeInn;
            if (resolvedInn == null) {
                if (code != null && !code.isBlank()) {
                    // a company_id that is not in our directory can never match the INN we import into
                    if (forceInn != null) {
                        skipped++;
                        skipReasons.merge("company_id " + code + " неизвестен; код компании " + forceInn + " — "
                                + forceCode + " (укажите его в company_id или добавьте колонку company_inn)",
                                1, Integer::sum);
                    } else {
                        unresolved++;
                    }
                    continue;
                }
                // statement without any company marker: it belongs to the INN it is uploaded for
                resolvedInn = forceInn;
            }
            if (resolvedInn == null) {
                unresolved++;
                continue;
            }
            if (forceInn != null && !forceInn.equals(resolvedInn)) {
                skipped++;
                skipReasons.merge("company_id " + (code == null || code.isBlank() ? "-" : code) + " (ИНН "
                        + resolvedInn + ") не совпадает с ИНН " + forceInn + ", для которого загружен файл",
                        1, Integer::sum);
                continue;
            }
            if (!userInns.contains(resolvedInn)) {
                skipped++;
                skipReasons.merge("ИНН " + resolvedInn + " не привязан к вашему пользователю", 1, Integer::sum);
                continue;
            }
            byInn.computeIfAbsent(resolvedInn, key -> new ArrayList<>()).add(dto);
        }

        List<String> errors = new ArrayList<>(parsed.errors());
        int shown = 0;
        int hiddenRows = 0;
        for (Map.Entry<String, Integer> reason : skipReasons.entrySet()) {
            if (shown < MAX_SKIP_REASONS) {
                errors.add("Пропущено строк: " + reason.getValue() + " — " + reason.getKey());
                shown++;
            } else {
                hiddenRows += reason.getValue();
            }
        }
        if (hiddenRows > 0) {
            errors.add("Ещё пропущено строк: " + hiddenRows + " (по другим компаниям)");
        }
        ImportResult total = new ImportResult(0, 0, parsed.invalid() + unresolved, skipped, errors);
        for (Map.Entry<String, List<OperationDto>> entry : byInn.entrySet()) {
            ImportResult part = operationImportService.upsertBatch(entry.getKey(), OperationSource.CSV, entry.getValue());
            total = total.merge(part);
            if (part.getInserted() > 0) {
                forecastService.onDataChanged(
                        entry.getKey(),
                        "Загружена выписка CSV: +" + part.getInserted() + ", дублей " + part.getDuplicates()
                );
            }
        }
        return total;
    }

    private Map<String, String> loadCodeToInn(List<OperationDto> operations) {
        Set<String> codes = new HashSet<>();
        for (OperationDto dto : operations) {
            if (dto.getCompanyId() != null && !dto.getCompanyId().isBlank()) {
                codes.add(dto.getCompanyId().trim());
            }
        }
        Map<String, String> map = new HashMap<>();
        if (!codes.isEmpty()) {
            for (Company company : companyRepository.findByCompanyCodeIn(codes)) {
                map.put(company.getCompanyCode(), company.getInn());
            }
        }
        return map;
    }

}
