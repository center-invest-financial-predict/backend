package com.example.demo.service;

import com.example.demo.entity.Company;
import com.example.demo.entity.User;
import com.example.demo.exception.ForbiddenInnException;
import com.example.demo.exception.UnknownInnException;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.OperationRepository;
import com.example.demo.security.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CompanyAccessService {

    private final CompanyRepository companyRepository;
    private final OperationRepository operationRepository;
    private final CustomUserDetailsService userDetailsService;

    @Transactional
    public void ensureCompaniesExist(Collection<String> inns) {
        Set<String> requested = new LinkedHashSet<>();
        for (String inn : inns) {
            if (inn != null && !inn.isBlank()) {
                requested.add(inn.trim());
            }
        }

        List<String> malformed = new ArrayList<>();
        for (String inn : requested) {
            if (!inn.matches("\\d{5,20}")) {
                malformed.add(inn);
            }
        }
        if (!malformed.isEmpty()) {
            throw new IllegalArgumentException("INN must consist of digits only: " + String.join(", ", malformed));
        }

        for (String inn : requested) {
            if (!ensureKnownCompany(inn)) {
                // a company the system has not seen yet: register it so the user can upload a CSV and work with it
                companyRepository.save(new Company(inn, externalCompanyCode(inn), "Компания " + inn, null, null));
            }
        }
    }

    /**
     * INN is known if it is already a company, or appears in operations
     * (as company_inn or counterparty_inn). In the latter case a company stub is created
     * so user_inns FK and ownership checks keep working.
     */
    @Transactional
    public boolean ensureKnownCompany(String inn) {
        String normalized = inn == null ? null : inn.trim();
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        if (companyRepository.existsById(normalized)) {
            return true;
        }
        if (!operationRepository.existsMentioningInn(normalized)) {
            return false;
        }

        String name = operationRepository
                .findFirstByCounterpartyInnAndCounterpartyNameNotNullOrderByIdAsc(normalized)
                .map(op -> op.getCounterpartyName())
                .filter(n -> n != null && !n.isBlank())
                .orElse("Компания " + normalized);

        Company stub = new Company(
                normalized,
                externalCompanyCode(normalized),
                name,
                null,
                null
        );
        companyRepository.save(stub);
        return true;
    }

    @Transactional(readOnly = true)
    public void ensureOwnedByUser(String email, String inn) {
        String normalized = inn == null ? null : inn.trim();
        if (normalized == null || !companyRepository.existsById(normalized)) {
            // try resolve from operations without creating in read-only path for listing
            // ownership still requires prior successful PUT /inns which creates the stub
            throw new UnknownInnException(List.of(inn == null ? "" : inn));
        }
        User user = (User) userDetailsService.loadUserByUsername(email);
        if (user.getInns() == null || !user.getInns().contains(normalized)) {
            throw new ForbiddenInnException(normalized);
        }
    }

    public static String externalCompanyCode(String inn) {
        String digits = inn.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            digits = Integer.toUnsignedString(inn.hashCode());
        }
        if (digits.length() >= 9) {
            return "E" + digits.substring(digits.length() - 9);
        }
        return "E" + ("000000000" + digits).substring(digits.length());
    }
}
