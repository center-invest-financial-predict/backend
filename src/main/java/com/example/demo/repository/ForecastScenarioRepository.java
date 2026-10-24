package com.example.demo.repository;

import com.example.demo.entity.ForecastScenario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ForecastScenarioRepository extends JpaRepository<ForecastScenario, Long> {

    List<ForecastScenario> findByCompanyInnAndUserIdOrderByUpdatedAtDesc(String companyInn, Long userId);

    Optional<ForecastScenario> findByIdAndCompanyInnAndUserId(Long id, String companyInn, Long userId);
}
