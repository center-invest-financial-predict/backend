package com.example.demo.repository;

import com.example.demo.entity.ForecastSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ForecastSettingsRepository extends JpaRepository<ForecastSettings, String> {
}
