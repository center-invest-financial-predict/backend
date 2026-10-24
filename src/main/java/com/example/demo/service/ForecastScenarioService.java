package com.example.demo.service;

import com.example.demo.dto.forecast.ForecastSeriesResponse;
import com.example.demo.dto.forecast.ScenarioDto;
import com.example.demo.entity.ForecastScenario;
import com.example.demo.entity.User;
import com.example.demo.exception.InsufficientHistoryException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.forecast.Condition;
import com.example.demo.forecast.ForecastService;
import com.example.demo.forecast.ForecastTypes.Run;
import com.example.demo.repository.ForecastScenarioRepository;
import com.example.demo.security.CustomUserDetailsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scenarios are saved lists of typed {@link Condition}s. Applying one really recalculates the forecast:
 * the conditions are fed to the same engine that produces the base forecast.
 */
@Service
@RequiredArgsConstructor
public class ForecastScenarioService {

    private final ForecastScenarioRepository forecastScenarioRepository;
    private final CustomUserDetailsService userDetailsService;
    private final ForecastService forecastService;
    private final CashForecastService cashForecastService;
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

    /** Conditions of a saved scenario of this user (404 if it is not theirs). */
    @Transactional(readOnly = true)
    public List<Condition> conditions(String email, String inn, Long id) {
        return parseStored(require(email, inn, id).getConditionsJson());
    }

    @Transactional
    public ScenarioDto create(String email, String inn, ScenarioDto.CreateRequest request) {
        User user = user(email);
        ForecastScenario s = new ForecastScenario();
        s.setCompanyInn(inn);
        s.setUserId(user.getId());
        s.setName(request.name());
        s.setConditionsJson(writeJson(Condition.toMaps(Condition.parse(request.conditions()))));
        return toDto(forecastScenarioRepository.save(s));
    }

    @Transactional
    public ScenarioDto update(String email, String inn, Long id, ScenarioDto.UpdateRequest request) {
        ForecastScenario s = require(email, inn, id);
        if (request.name() != null && !request.name().isBlank()) {
            s.setName(request.name());
        }
        if (request.conditions() != null) {
            s.setConditionsJson(writeJson(Condition.toMaps(Condition.parse(request.conditions()))));
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

    /** Recalculates the forecast with the given conditions without saving anything. */
    @Transactional(readOnly = true)
    public ForecastSeriesResponse preview(String inn, ScenarioDto.PreviewRequest request) {
        List<Condition> conditions = Condition.parse(request == null ? null : request.conditions());
        int horizon = request == null || request.horizonDays() == null
                ? ForecastService.DEFAULT_HORIZON : request.horizonDays();
        return cashForecastService.forecast(inn, horizon, conditions, null);
    }

    /** Ready-made what-if examples with real results on the current data. */
    @Transactional(readOnly = true)
    public List<ScenarioDto.ExampleDto> examples(String inn) {
        List<ScenarioDto.ExampleDto> out = new ArrayList<>();
        out.add(example(inn, "delay-14", "Клиенты платят на 2 недели позже",
                "Все поступления задерживаются на 14 дней",
                List.of(Map.of("type", "delay_payment", "days", 14))));
        out.add(example(inn, "revenue-20", "Выручка ниже на 20%",
                "Регулярные и прочие поступления снижаются на 20%",
                List.of(Map.of("type", "revenue_drop", "pct", 20))));
        out.add(example(inn, "costs-15", "Постоянные расходы выросли на 15%",
                "Зарплата, аренда и кредит дороже на 15%",
                List.of(Map.of("type", "fixed_cost_growth", "pct", 15))));
        out.add(example(inn, "extra-500", "Внеплановый платёж 500 000 ₽",
                "Разовый расход через неделю",
                List.of(Map.of("type", "extra_expense", "amount", 500000, "dayOffset", 7))));
        return out;
    }

    private ScenarioDto.ExampleDto example(String inn, String id, String name, String description,
                                           List<Map<String, Object>> raw) {
        List<Condition> conditions = Condition.parse(raw);
        Run run = forecastService.run(inn, ForecastService.DEFAULT_HORIZON, conditions);
        return new ScenarioDto.ExampleDto(id, name, description, Condition.toMaps(conditions),
                run.minBalance(),
                run.firstGap() == null ? null : run.firstGap().date(),
                run.firstGap() == null ? null : run.firstGap().deficit(),
                BigDecimal.valueOf(run.probability()));
    }

    private ForecastScenario require(String email, String inn, Long id) {
        User user = user(email);
        return forecastScenarioRepository.findByIdAndCompanyInnAndUserId(id, inn, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Scenario not found: " + id));
    }

    private User user(String email) {
        return (User) userDetailsService.loadUserByUsername(email);
    }

    private ScenarioDto toDto(ForecastScenario s) {
        List<Condition> conditions = parseStored(s.getConditionsJson());
        ForecastSeriesResponse.Summary summary = null;
        try {
            summary = cashForecastService.summary(
                    forecastService.run(s.getCompanyInn(), ForecastService.DEFAULT_HORIZON, conditions));
        } catch (InsufficientHistoryException ignored) {
            // no data yet: scenario can still be listed
        }
        return new ScenarioDto(
                s.getId(),
                s.getCompanyInn(),
                s.getName(),
                Condition.toMaps(conditions),
                conditions.stream().map(Condition::describe).toList(),
                summary,
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }

    /** Legacy rows may hold free-form JSON the engine cannot understand: they are shown as "no conditions". */
    private List<Condition> parseStored(String json) {
        try {
            return Condition.parse(objectMapper.readValue(json == null ? "[]" : json, Object.class));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid conditions JSON", e);
        }
    }
}
