package org.example.gos_service.repository;

import org.example.gos_service.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, String> {

    Optional<Company> findByInn(String inn);
}
