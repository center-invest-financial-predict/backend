package com.example.demo.service;

import com.example.demo.dto.forecast.CompanyDto;
import com.example.demo.dto.forecast.CompanyOnboardingDto;
import com.example.demo.dto.forecast.DataSourcesResponse;
import com.example.demo.entity.Company;
import com.example.demo.entity.OperationSource;
import com.example.demo.entity.User;
import com.example.demo.exception.CompanyAlreadyExistsException;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.security.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Onboarding of a company the system has not seen yet: the user names it, picks the data sources
 * (for example only "csv") and then uploads the file. Only the chosen sources are used and asked.
 */
@Service
@RequiredArgsConstructor
public class CompanyOnboardingService {

    private final CompanyRepository companyRepository;
    private final CompanyAccessService companyAccessService;
    private final CustomUserDetailsService userDetailsService;
    private final CashForecastService cashForecastService;
    private final IntegrationSyncService integrationSyncService;

    @Transactional
    public CompanyOnboardingDto.Response create(String email, CompanyOnboardingDto.CreateRequest request) {
        String inn = request.inn().trim();
        if (request.name().isBlank()) {
            throw new IllegalArgumentException("Company name cannot be blank");
        }
        if (companyRepository.existsById(inn)) {
            // an existing company is linked through PUT /users/me/inns; its settings are not ours to change
            throw new CompanyAlreadyExistsException(inn);
        }
        // validate the choice before anything is written
        List<String> sources = request.sources();
        boolean chose = sources != null && !sources.isEmpty();
        Set<OperationSource> chosen = new LinkedHashSet<>();
        if (chose) {
            sources.forEach(code -> chosen.add(OperationSource.from(code)));
        } else if (sources != null) {
            throw new IllegalArgumentException("Выберите хотя бы один источник данных");
        }

        Company created = new Company(inn, companyAccessService.nextCompanyCode(),
                request.name().trim(), blankToNull(request.legalForm()), blankToNull(request.industry()));
        created.setUserCreated(true);
        Company company = companyRepository.save(created);

        User user = (User) userDetailsService.loadUserByUsername(email);
        Set<String> inns = new LinkedHashSet<>(user.getInns() == null ? Set.of() : user.getInns());
        inns.add(inn);
        userDetailsService.replaceInns(email, inns);

        DataSourcesResponse state = chose
                ? cashForecastService.selectSources(inn, sources)
                : cashForecastService.sources(inn);

        // pull only from the chosen external services (csv is uploaded by the user, not pulled)
        boolean pull = chosen.isEmpty() || chosen.stream().anyMatch(OperationSource::isIntegration);
        if (pull) {
            integrationSyncService.syncInnsAsync(List.of(inn));
        }
        return new CompanyOnboardingDto.Response(CompanyDto.from(company), state, pull);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
