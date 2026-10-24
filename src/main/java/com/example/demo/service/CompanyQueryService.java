package com.example.demo.service;

import com.example.demo.dto.forecast.CompanyDto;
import com.example.demo.entity.User;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.security.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CompanyQueryService {

    private final CompanyRepository companyRepository;
    private final CustomUserDetailsService userDetailsService;
    private final CompanyAccessService companyAccessService;

    @Transactional(readOnly = true)
    public List<CompanyDto> listForUser(String email) {
        User user = (User) userDetailsService.loadUserByUsername(email);
        Set<String> inns = user.getInns() == null ? Set.of() : user.getInns();
        if (inns.isEmpty()) {
            return List.of();
        }
        return companyRepository.findByInnIn(inns).stream().map(CompanyDto::from).toList();
    }

    @Transactional(readOnly = true)
    public CompanyDto getOwned(String email, String inn) {
        companyAccessService.ensureOwnedByUser(email, inn);
        return companyRepository.findById(inn.trim())
                .map(CompanyDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found: " + inn));
    }

    /**
     * Onboarding lookup: known company from DB, otherwise stub suggestion (no persist).
     */
    @Transactional(readOnly = true)
    public CompanyDto lookup(String inn) {
        String normalized = inn == null ? "" : inn.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("INN is required");
        }
        return companyRepository.findById(normalized)
                .map(CompanyDto::from)
                .orElseGet(() -> new CompanyDto(
                        normalized,
                        "NEW",
                        "Компания " + normalized,
                        null,
                        null
                ));
    }
}
