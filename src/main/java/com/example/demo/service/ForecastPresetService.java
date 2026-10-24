package com.example.demo.service;

import com.example.demo.dto.forecast.ForecastPresetsResponse;
import com.example.demo.dto.forecast.ForecastPresetsResponse.Impact;
import com.example.demo.dto.forecast.ForecastPresetsResponse.Preset;
import com.example.demo.dto.forecast.ForecastSettingsDto;
import com.example.demo.forecast.Condition;
import com.example.demo.forecast.ForecastService;
import com.example.demo.forecast.ForecastTypes.Run;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ready-made stress/optimism presets over the same forecast engine: conservative, base, optimistic and the user's own.
 * The preset matching {@code cautionLevel} is the "active" one.
 */
@Service
@RequiredArgsConstructor
public class ForecastPresetService {

    public static final String CONS = "cons";
    public static final String BASE = "base";
    public static final String OPT = "opt";
    public static final String OWN = "own";

    static final int CONS_DELAY_DAYS = 7;
    static final double CONS_REVENUE_DROP_PCT = 10;
    static final int OPT_EARLIER_DAYS = 3;

    private final ForecastService forecastService;
    private final ForecastSettingsService forecastSettingsService;

    public ForecastPresetsResponse presets(String inn) {
        ForecastSettingsDto settings = forecastSettingsService.get(inn);
        int horizon = ForecastService.DEFAULT_HORIZON;
        String active = activeCode(settings.cautionLevel());

        Run base = forecastService.run(inn, horizon, List.of());
        Own own = own(settings.presets());

        List<Preset> list = List.of(
                preset(CONS, "Консервативный",
                        "Поступления на " + CONS_DELAY_DAYS + " дн. позже и выручка ниже на "
                                + (int) CONS_REVENUE_DROP_PCT + "%",
                        true, consConditions(), active, horizon, base, settings, inn),
                preset(BASE, "Базовый", "Прогноз без изменений условий",
                        true, List.of(), active, horizon, base, settings, inn),
                preset(OPT, "Оптимистичный", "Поступления приходят на " + OPT_EARLIER_DAYS + " дн. раньше",
                        true, optConditions(), active, horizon, base, settings, inn),
                preset(OWN, own.name(), own.configured()
                                ? "Ваш набор условий"
                                : "Свой набор условий не задан: пока совпадает с базовым",
                        own.configured(), own.conditions(), active, horizon, base, settings, inn));
        return new ForecastPresetsResponse(inn, horizon, settings.minBalance(), settings.cautionLevel(), active, list);
    }

    /** Maps the stored cautionLevel to a preset code. */
    public static String activeCode(String cautionLevel) {
        if (cautionLevel == null) {
            return BASE;
        }
        return switch (cautionLevel.trim().toLowerCase(Locale.ROOT)) {
            case "conservative", CONS -> CONS;
            case "optimistic", OPT -> OPT;
            case OWN -> OWN;
            default -> BASE;
        };
    }

    public static List<Condition> consConditions() {
        return Condition.parse(List.of(
                Map.of("type", "delay_payment", "days", CONS_DELAY_DAYS),
                Map.of("type", "revenue_drop", "pct", CONS_REVENUE_DROP_PCT)));
    }

    public static List<Condition> optConditions() {
        return Condition.parse(List.of(Map.of("type", "delay_payment", "days", -OPT_EARLIER_DAYS)));
    }

    private Preset preset(String code, String name, String description, boolean configured,
                          List<Condition> conditions, String active, int horizon, Run base,
                          ForecastSettingsDto settings, String inn) {
        Run run = conditions.isEmpty() ? base : forecastService.run(inn, horizon, conditions);
        BigDecimal baseGap = base.firstGap() == null ? BigDecimal.ZERO : base.firstGap().deficit();
        BigDecimal gap = run.firstGap() == null ? BigDecimal.ZERO : run.firstGap().deficit();
        BigDecimal probability = BigDecimal.valueOf(run.probability());
        boolean below = settings.minBalance() != null && run.minBalance().compareTo(settings.minBalance()) < 0;
        return new Preset(code, name, description, code.equals(active), configured,
                Condition.toMaps(conditions), run.minBalance(), run.minBalanceDate(), below,
                run.firstGap() == null ? null : run.firstGap().date(),
                run.firstGap() == null ? null : run.firstGap().deficit(),
                probability,
                new Impact(run.minBalance().subtract(base.minBalance()), gap.subtract(baseGap),
                        probability.subtract(BigDecimal.valueOf(base.probability()))));
    }

    private record Own(String name, boolean configured, List<Condition> conditions) {
    }

    private static Own own(Object presets) {
        if (presets instanceof Map<?, ?> map && map.get("own") instanceof Map<?, ?> own) {
            Object name = own.get("name");
            List<Condition> conditions = Condition.parse(own.get("conditions"));
            return new Own(name == null || name.toString().isBlank() ? "Свой" : name.toString().trim(),
                    !conditions.isEmpty(), conditions);
        }
        return new Own("Свой", false, List.of());
    }
}
