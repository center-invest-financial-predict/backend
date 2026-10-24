package com.example.demo.service;

import com.example.demo.dto.forecast.CompanyDto;
import com.example.demo.dto.forecast.CompanyProfileDto;
import com.example.demo.entity.Company;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
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
    private final UserRepository userRepository;

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

    @Transactional
    public CompanyDto update(String email, String inn, CompanyProfileDto.UpdateRequest request) {
        companyAccessService.ensureOwnedByUser(email, inn);
        Company company = companyRepository.findById(inn.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Company not found: " + inn));
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new IllegalArgumentException("Company name cannot be blank");
            }
            company.setName(request.name().trim());
        }
        if (request.legalForm() != null) {
            company.setLegalForm(blankToNull(request.legalForm()));
        }
        if (request.industry() != null) {
            company.setIndustry(blankToNull(request.industry()));
        }
        return CompanyDto.from(companyRepository.save(company));
    }

    @Transactional(readOnly = true)
    public List<CompanyProfileDto.Member> members(String email, String inn) {
        companyAccessService.ensureOwnedByUser(email, inn);
        return userRepository.findByLinkedInn(inn.trim()).stream()
                .map(u -> new CompanyProfileDto.Member(u.getId(), u.getEmail(), fullName(u),
                        u.getRole().name(), u.getEmail().equalsIgnoreCase(email)))
                .toList();
    }

    private static String fullName(User u) {
        return java.util.stream.Stream.of(u.getLastName(), u.getFirstName(), u.getMiddleName())
                .filter(s -> s != null && !s.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
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
