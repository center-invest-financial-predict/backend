package com.example.demo.service;

import com.example.demo.dto.forecast.ForecastSettingsDto;
import com.example.demo.entity.ForecastSettings;
import com.example.demo.forecast.Condition;
import com.example.demo.repository.ForecastSettingsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ForecastSettingsService {

    private final ForecastSettingsRepository forecastSettingsRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ForecastSettingsDto get(String inn) {
        return forecastSettingsRepository.findById(inn)
                .map(this::toDto)
                .orElseGet(() -> defaults(inn));
    }

    @Transactional
    public ForecastSettingsDto upsert(String inn, ForecastSettingsDto.UpdateRequest request) {
        ForecastSettings s = forecastSettingsRepository.findById(inn).orElseGet(() -> {
            ForecastSettings created = new ForecastSettings();
            created.setCompanyInn(inn);
            return created;
        });
        if (request.minBalance() != null) {
            s.setMinBalance(request.minBalance());
        }
        if (request.salaryDay() != null) {
            s.setSalaryDay(request.salaryDay());
        }
        if (request.cautionLevel() != null) {
            s.setCautionLevel(normalizeCaution(request.cautionLevel()));
        }
        if (request.presets() != null) {
            validatePresets(request.presets());
            s.setPresetsJson(writeJson(request.presets()));
        }
        if (request.notifyRules() != null) {
            s.setNotifyRulesJson(writeJson(request.notifyRules()));
        }
        return toDto(forecastSettingsRepository.save(s));
    }

    private static String normalizeCaution(String raw) {
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "conservative", "balanced", "optimistic", "own" -> v;
            default -> throw new IllegalArgumentException(
                    "cautionLevel must be one of: conservative, balanced, optimistic, own");
        };
    }

    /** The user's own preset must hold valid scenario conditions: fail on save, not on the forecast screen. */
    private static void validatePresets(Object presets) {
        if (!(presets instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("presets must be an object like {\"own\": {\"conditions\": [...]}}");
        }
        Object own = map.get("own");
        if (own == null) {
            return;
        }
        if (!(own instanceof Map<?, ?> ownMap)) {
            throw new IllegalArgumentException("presets.own must be an object");
        }
        Condition.parse(ownMap.get("conditions"));
    }

    private ForecastSettingsDto defaults(String inn) {
        return new ForecastSettingsDto(
                inn,
                BigDecimal.valueOf(100_000),
                5,
                "balanced",
                Map.of("belowMinBalance", true, "gapRisk", true),
                Map.of(),
                null
        );
    }

    private ForecastSettingsDto toDto(ForecastSettings s) {
        return new ForecastSettingsDto(
                s.getCompanyInn(),
                s.getMinBalance(),
                s.getSalaryDay(),
                s.getCautionLevel(),
                readJson(s.getNotifyRulesJson()),
                readJson(s.getPresetsJson()),
                s.getUpdatedAt()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid notifyRules JSON", e);
        }
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
