package com.example.demo.repository;

import com.example.demo.entity.CompanySource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanySourceRepository extends JpaRepository<CompanySource, Long> {

    List<CompanySource> findByCompanyInnAndEnabledFalse(String companyInn);

    Optional<CompanySource> findByCompanyInnAndSourceCode(String companyInn, String sourceCode);
}
