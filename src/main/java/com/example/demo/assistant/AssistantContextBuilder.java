package com.example.demo.assistant;

import com.example.demo.assistant.AssistantDto.Figures;
import com.example.demo.dto.forecast.AlertDto;
import com.example.demo.dto.forecast.RecalcJournalDto;
import com.example.demo.dto.forecast.RecommendationDto;
import com.example.demo.forecast.Condition;
import com.example.demo.forecast.ForecastService;
import com.example.demo.forecast.ForecastTypes.DayItem;
import com.example.demo.forecast.ForecastTypes.DayPoint;
import com.example.demo.forecast.ForecastTypes.Gap;
import com.example.demo.forecast.ForecastTypes.Run;
import com.example.demo.forecast.Money;
import com.example.demo.forecast.RegularItem;
import com.example.demo.service.CashForecastService;
import com.example.demo.service.RecalcJournalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the "facts" block for the LLM from the very same cached forecast run that feeds the screens.
 * The model explains these numbers; it is never asked to produce them.
 */
@Component
@RequiredArgsConstructor
public class AssistantContextBuilder {

    private static final int TOP_ITEMS = 8;

    private final ForecastService forecastService;
    private final CashForecastService cashForecastService;
    private final RecalcJournalService journalService;

    public record Context(String facts, Figures figures, Run base, Run focus) {
    }

    public Context build(String inn, List<Condition> conditions, LocalDate date) {
        Run base = forecastService.baseline(inn);
        boolean hasScenario = conditions != null && !conditions.isEmpty();
        Run scenario = hasScenario ? forecastService.run(inn, base.horizon(), conditions) : null;
        Run focus = scenario != null ? scenario : base;
        Run stress = forecastService.stress(inn, base.horizon());

        StringBuilder sb = new StringBuilder();
        sb.append("ДАННЫЕ РАСЧЁТА (единственный источник чисел; расчёт id ").append(base.runId()).append(")\n");
        sb.append("Компания ИНН ").append(inn).append(". Операции учтены до ").append(base.asOf())
                .append(" (история ").append(base.historyDays()).append(" дн.). Прогноз на ")
                .append(base.horizon()).append(" дн.: ").append(base.asOf().plusDays(1)).append(" – ")
                .append(base.asOf().plusDays(base.horizon())).append(". Рассчитано ").append(base.calculatedAt()).append(".\n");
        sb.append("Остаток на ").append(base.asOf()).append(": ").append(Money.rub(base.startBalance())).append(".\n\n");

        sb.append("БАЗОВЫЙ ПРОГНОЗ\n");
        appendRun(sb, base);

        if (scenario != null) {
            sb.append("\nСЦЕНАРИЙ (пересчитан тем же расчётом). Условия: ")
                    .append(Condition.describeAll(conditions)).append("\n");
            appendRun(sb, scenario);
            sb.append("Разница со сценарием: остаток на конец периода ")
                    .append(Money.rub(scenario.endBalance().subtract(base.endBalance())))
                    .append("; минимальный остаток ")
                    .append(Money.rub(scenario.minBalance().subtract(base.minBalance()))).append("; вероятность разрыва ")
                    .append(Money.pct(base.probability())).append(" → ").append(Money.pct(scenario.probability())).append(".\n");
        }

        sb.append("\nСТРЕСС-ТЕСТ (все поступления на ").append(Condition.STRESS_DELAY_DAYS).append(" дн. позже)\n");
        sb.append("Минимальный остаток ").append(Money.rub(stress.minBalance())).append(" (").append(stress.minBalanceDate()).append("); ")
                .append(stress.firstGap() == null ? "разрыва нет" : gapText(stress.firstGap()))
                .append("; вероятность разрыва ").append(Money.pct(stress.probability())).append(".\n");

        List<RegularItem> regulars = forecastService.regulars(inn).stream()
                .sorted(Comparator.comparing(RegularItem::amount).reversed()).limit(TOP_ITEMS).toList();
        if (!regulars.isEmpty()) {
            sb.append("\nРЕГУЛЯРНЫЕ ПЛАТЕЖИ (найдены по истории)\n");
            for (RegularItem r : regulars) {
                sb.append("- ").append(r.direction() == com.example.demo.entity.Direction.INCOME ? "поступление" : "платёж")
                        .append(" «").append(r.title()).append("» (").append(r.category()).append("), ")
                        .append(r.cadence().label().toLowerCase()).append(", около ").append(Money.rub(r.amount()))
                        .append(", ближайший ").append(r.nextDate(base.asOf())).append("\n");
            }
        }

        List<Object[]> soon = new ArrayList<>();
        for (DayPoint p : focus.points()) {
            if (ChronoUnit.DAYS.between(focus.asOf(), p.date()) > 14) {
                break;
            }
            for (DayItem it : p.items()) {
                soon.add(new Object[]{p.date(), it});
            }
        }
        if (!soon.isEmpty()) {
            sb.append("\nЗАПЛАНИРОВАННЫЕ ОПЕРАЦИИ БЛИЖАЙШИХ 14 ДНЕЙ (крупнейшие)\n");
            soon.stream()
                    .sorted(Comparator.comparing((Object[] o) -> ((DayItem) o[1]).amount()).reversed())
                    .limit(TOP_ITEMS)
                    .sorted(Comparator.comparing((Object[] o) -> (LocalDate) o[0]))
                    .forEach(o -> {
                        DayItem it = (DayItem) o[1];
                        sb.append("- ").append(o[0]).append(": ").append("INCOME".equals(it.direction()) ? "+" : "-")
                                .append(Money.rub(it.amount())).append(" ").append(it.title()).append(" (")
                                .append(it.category()).append(", ").append(sourceText(it.source())).append(")\n");
                    });
        }

        if (date != null) {
            appendDay(sb, focus, date);
        }

        List<AlertDto> alerts = cashForecastService.alerts(inn);
        if (!alerts.isEmpty()) {
            sb.append("\nПРЕДУПРЕЖДЕНИЯ СИСТЕМЫ\n");
            alerts.stream().limit(5).forEach(a -> sb.append("- ").append(a.title()).append(": ").append(a.description()).append("\n"));
        }
        List<RecommendationDto> recs = cashForecastService.recommendations(inn);
        if (!recs.isEmpty()) {
            sb.append("\nПРОВЕРЕННЫЕ РЕКОМЕНДАЦИИ (эффект рассчитан пересчётом)\n");
            recs.stream().limit(3).forEach(r -> sb.append("- ").append(r.title()).append(": дефицит ")
                    .append(Money.rub(r.beforeGap())).append(" → ").append(Money.rub(r.afterGap())).append("\n"));
        }
        List<RecalcJournalDto> journal = journalService.list(inn, 3);
        if (!journal.isEmpty()) {
            sb.append("\nПОСЛЕДНИЕ ПЕРЕСЧЁТЫ\n");
            journal.forEach(j -> sb.append("- ").append(j.occurredAt().withNano(0)).append(": ").append(j.message()).append("\n"));
        }
        if (!base.warnings().isEmpty()) {
            sb.append("\nОГРАНИЧЕНИЯ ТОЧНОСТИ\n");
            base.warnings().forEach(w -> sb.append("- ").append(w).append("\n"));
        }
        if (base.fromMlCore()) {
            sb.append("\nВероятность разрыва и баланс по дням рассчитаны сервером ML Core; это расчёт системы.\n");
        } else {
            sb.append("\nВероятность разрыва — доля случайных прогонов модели (")
                    .append(base.simulations()).append(" прогонов), в которых баланс уходит ниже нуля; это расчёт системы.\n");
        }

        return new Context(sb.toString(), figures(focus), base, focus);
    }

    public static Figures figures(Run run) {
        Gap g = run.firstGap();
        Gap r = run.p10Gap();
        return new Figures(run.asOf(), run.startBalance(), run.endBalance(), run.minBalance(), run.minBalanceDate(),
                g == null ? null : g.date(), g == null ? null : g.deficit(),
                java.math.BigDecimal.valueOf(run.probability()),
                r == null ? null : r.date(), r == null ? null : r.deficit());
    }

    /** Deterministic answer assembled from the same facts; used when the LLM is unavailable. */
    public String fallbackAnswer(Context ctx, boolean scenario) {
        return summary(ctx, scenario, true);
    }

    /** The same summary without the "assistant unavailable" remark: appended to model text as the verified numbers. */
    public String calculationSummary(Context ctx, boolean scenario) {
        return "Расчёт системы. " + summary(ctx, scenario, false);
    }

    private String summary(Context ctx, boolean scenario, boolean unavailableRemark) {
        Run run = ctx.focus();
        StringBuilder sb = new StringBuilder();
        sb.append(scenario ? "Результат сценария: " : "Прогноз на ").append(scenario ? "" : run.horizon() + " дн.: ");
        sb.append("остаток сейчас ").append(Money.rub(run.startBalance())).append(", на конец периода ")
                .append(Money.rub(run.endBalance())).append(". Минимум — ").append(Money.rub(run.minBalance()))
                .append(" (").append(run.minBalanceDate()).append("). ");
        if (run.firstGap() != null) {
            sb.append("Кассовый разрыв: ").append(gapText(run.firstGap())).append(". ");
        } else {
            sb.append("Кассового разрыва в расчёте нет. ");
        }
        sb.append("Вероятность разрыва по модели — ").append(Money.pct(run.probability())).append(". ");
        if (run.p10Gap() != null && run.firstGap() == null) {
            sb.append("При неблагоприятном развитии с ").append(run.p10Gap().date()).append(" может не хватить до ")
                    .append(Money.rub(run.p10Gap().deficit())).append(". ");
        }
        if (unavailableRemark) {
            sb.append("(Текстовый помощник сейчас недоступен — это краткая сводка того же расчёта.)");
        }
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- parts

    private void appendRun(StringBuilder sb, Run run) {
        sb.append("Поступления за период ").append(Money.rub(run.totalInflow())).append(", платежи ")
                .append(Money.rub(run.totalOutflow())).append(", остаток на конец ").append(Money.rub(run.endBalance()))
                .append(". Минимальный остаток ").append(Money.rub(run.minBalance())).append(" (").append(run.minBalanceDate()).append(").\n");
        sb.append(run.firstGap() == null
                ? "Кассового разрыва в этом расчёте нет."
                : "Кассовый разрыв: " + gapText(run.firstGap())
                + (run.deepestGap() != null && !run.deepestGap().date().equals(run.firstGap().date())
                ? "; максимальный дефицит " + Money.rub(run.deepestGap().deficit()) + " " + run.deepestGap().date() : "")
                + ".").append("\n");
        sb.append("Вероятность разрыва: ").append(Money.pct(run.probability()));
        if (run.p10Gap() != null) {
            sb.append("; в неблагоприятном случае (10-й перцентиль) баланс отрицателен с ").append(run.p10Gap().date())
                    .append(", дефицит до ").append(Money.rub(run.p10Gap().deficit()));
        }
        sb.append(".\n");
    }

    private void appendDay(StringBuilder sb, Run run, LocalDate date) {
        int idx = (int) ChronoUnit.DAYS.between(run.asOf(), date) - 1;
        if (idx < 0 || idx >= run.points().size()) {
            sb.append("\nДЕНЬ ").append(date).append(": вне горизонта прогноза.\n");
            return;
        }
        DayPoint p = run.points().get(idx);
        sb.append("\nДЕНЬ ").append(date).append(" (выбран пользователем)\n");
        sb.append("Остаток на конец дня ").append(Money.rub(p.balance())).append(" (диапазон ").append(Money.rub(p.p10()))
                .append(" … ").append(Money.rub(p.p90())).append("); поступления ").append(Money.rub(p.inflow()))
                .append(", платежи ").append(Money.rub(p.outflow())).append(".\n");
        for (DayItem it : p.items()) {
            sb.append("- ").append("INCOME".equals(it.direction()) ? "+" : "-").append(Money.rub(it.amount())).append(" ")
                    .append(it.title()).append(" (").append(it.basis()).append(")\n");
        }
        sb.append("- статистически ожидаемые прочие поступления ").append(Money.rub(p.otherInflow()))
                .append(", прочие платежи ").append(Money.rub(p.otherOutflow())).append(" (среднее по дню недели за последние недели)\n");
    }

    private static String gapText(Gap g) {
        return g.date() + " не хватит " + Money.rub(g.deficit());
    }

    private static String sourceText(String source) {
        return switch (source) {
            case "regular" -> "регулярный платёж";
            case "event" -> "ожидаемое событие";
            case "scenario" -> "условие сценария";
            default -> source;
        };
    }
}
