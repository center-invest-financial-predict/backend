package com.example.demo.service;

import com.example.demo.dto.forecast.ForecastSeriesResponse;
import com.example.demo.dto.forecast.ScenarioDto;
import com.example.demo.entity.ForecastScenario;
import com.example.demo.entity.User;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.entity.Operation;
import com.example.demo.repository.ForecastScenarioRepository;
import com.example.demo.repository.OperationRepository;
import com.example.demo.security.CustomUserDetailsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ForecastScenarioService {

    private final ForecastScenarioRepository forecastScenarioRepository;
    private final OperationRepository operationRepository;
    private final CustomUserDetailsService userDetailsService;
    private final MlForecastStubService stubService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<ScenarioDto> list(String email, String inn) {
        User user = user(email);
        return forecastScenarioRepository.findByCompanyInnAndUserIdOrderByUpdatedAtDesc(inn, user.getId())
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ScenarioDto get(String email, String inn, Long id) {
        return toDto(require(email, inn, id));
    }

    @Transactional
    public ScenarioDto create(String email, String inn, ScenarioDto.CreateRequest request) {
        User user = user(email);
        ForecastScenario s = new ForecastScenario();
        s.setCompanyInn(inn);
        s.setUserId(user.getId());
        s.setName(request.name());
        s.setConditionsJson(writeJson(request.conditions()));
        return toDto(forecastScenarioRepository.save(s));
    }

    @Transactional
    public ScenarioDto update(String email, String inn, Long id, ScenarioDto.UpdateRequest request) {
        ForecastScenario s = require(email, inn, id);
        if (request.name() != null && !request.name().isBlank()) {
            s.setName(request.name());
        }
        if (request.conditions() != null) {
            s.setConditionsJson(writeJson(request.conditions()));
        }
        return toDto(forecastScenarioRepository.save(s));
    }

    @Transactional
    public void delete(String email, String inn, Long id) {
        forecastScenarioRepository.delete(require(email, inn, id));
    }

    @Transactional
    public ScenarioDto copy(String email, String inn, Long id) {
        ForecastScenario src = require(email, inn, id);
        ForecastScenario copy = new ForecastScenario();
        copy.setCompanyInn(inn);
        copy.setUserId(src.getUserId());
        copy.setName(src.getName() + " (копия)");
        copy.setConditionsJson(src.getConditionsJson());
        return toDto(forecastScenarioRepository.save(copy));
    }

    @Transactional(readOnly = true)
    public ForecastSeriesResponse preview(String inn, ScenarioDto.PreviewRequest request) {
        // conditions affect seed offset via hash — stub recalculation
        long offset = request == null || request.conditions() == null
                ? 0
                : Math.abs(writeJson(request.conditions()).hashCode());
        return stubService.forecastSeries(inn, 30, offset, balance(inn));
    }

    @Transactional(readOnly = true)
    public List<ScenarioDto.ExampleDto> examples(String inn) {
        return stubService.scenarioExamples(inn);
    }

    private ForecastScenario require(String email, String inn, Long id) {
        User user = user(email);
        return forecastScenarioRepository.findByIdAndCompanyInnAndUserId(id, inn, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Scenario not found: " + id));
    }

    private User user(String email) {
        return (User) userDetailsService.loadUserByUsername(email);
    }

    private BigDecimal balance(String inn) {
        return operationRepository
                .findFirstByCompanyInnAndBalanceEodIsNotNullOrderByOperationDateDescIdDesc(inn)
                .map(Operation::getBalanceEod)
                .orElse(BigDecimal.valueOf(400_000));
    }

    private ScenarioDto toDto(ForecastScenario s) {
        return new ScenarioDto(
                s.getId(),
                s.getCompanyInn(),
                s.getName(),
                readJson(s.getConditionsJson()),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid conditions JSON", e);
        }
    }

    private Object readJson(String json) {
        try {
            return objectMapper.readValue(json == null ? "[]" : json, Object.class);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
