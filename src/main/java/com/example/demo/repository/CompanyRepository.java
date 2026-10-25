package com.example.demo.repository;

import com.example.demo.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface CompanyRepository extends JpaRepository<Company, String> {

    List<Company> findByInnIn(Collection<String> inns);

    List<Company> findByCompanyCodeIn(Collection<String> companyCodes);

    Optional<Company> findByCompanyCode(String companyCode);

    /** Codes of the C001, C002, ... family: used to hand out the next free one. */
    @Query("select c.companyCode from Company c where c.companyCode like 'C%'")
    List<String> findCodesStartingWithC();

    default Set<String> findExistingInns(Collection<String> inns) {
        return findByInnIn(inns).stream()
                .map(Company::getInn)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
