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

        Map<String, List<OperationDto>> byInn = new LinkedHashMap<>();
        int skipped = 0;
        int unresolved = 0;

        for (OperationDto dto : parsed.operations()) {
            String resolvedInn = resolveInn(dto, codeToInn, forceInn);
            if (resolvedInn == null) {
                unresolved++;
                continue;
            }
            if (forceInn != null && !forceInn.equals(resolvedInn)) {
                skipped++;
                continue;
            }
            if (!userInns.contains(resolvedInn)) {
                skipped++;
                continue;
            }
            byInn.computeIfAbsent(resolvedInn, key -> new ArrayList<>()).add(dto);
        }

        ImportResult total = new ImportResult(0, 0, parsed.invalid() + unresolved, skipped);
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

    private String resolveInn(OperationDto dto, Map<String, String> codeToInn, String forceInn) {
        if (dto.getCompanyInn() != null && !dto.getCompanyInn().isBlank()) {
            return dto.getCompanyInn().trim();
        }
        if (dto.getCompanyId() != null && !dto.getCompanyId().isBlank()) {
            String fromCode = codeToInn.get(dto.getCompanyId().trim());
            if (fromCode != null) {
                return fromCode;
            }
        }
        // Single-company statement without resolvable id: fall back to path INN
        return forceInn;
    }
}
