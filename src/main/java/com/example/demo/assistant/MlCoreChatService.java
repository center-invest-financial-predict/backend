package com.example.demo.assistant;

import com.example.demo.assistant.AssistantContextBuilder.Context;
import com.example.demo.client.MlCoreClient;
import com.example.demo.dto.mlcore.MlCoreApi;
import com.example.demo.entity.Direction;
import com.example.demo.exception.MlCoreException;
import com.example.demo.forecast.Condition;
import com.example.demo.forecast.ForecastTypes.DayItem;
import com.example.demo.forecast.ForecastTypes.DayPoint;
import com.example.demo.forecast.ForecastTypes.Run;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The assistant on the "Cashflow ML Core" server ({@code POST /v1/chat}).
 * <p>
 * What goes to the server: the question, the chat history from <b>our</b> memory (namespaced by user + company) and a
 * snapshot built from our own forecast {@link Run}. What comes back and is used: the text and the structured scenarios
 * the model understood. The server's own numbers are ignored: every scenario is mapped to a {@link Condition} and
 * recalculated by our engine, so amounts, dates and probabilities in API responses always come from one calculation.
 */
@Component
public class MlCoreChatService {

    private static final Logger log = LoggerFactory.getLogger(MlCoreChatService.class);

    static final int MAX_HISTORY = 40;
    static final int MAX_CONTENT = 12_000;

    private final MlCoreClient client;
    private final ChatMemory chatMemory;

    public MlCoreChatService(MlCoreClient client, ChatMemory chatMemory) {
        this.client = client;
        this.chatMemory = chatMemory;
    }

    public boolean available() {
        return client != null && client.isEnabled();
    }

    /** A scenario the server model returned, in our terms. {@code condition == null} means it cannot be calculated. */
    public record Applied(String id, String kind, String description, Map<String, Object> condition,
                          boolean supported, String reason) {
    }

    public record Reply(String answer, boolean clarificationNeeded, List<Applied> applied,
                        List<String> removedIds, List<Condition> conditions, boolean changed) {
    }

    /**
     * @param conversation key built by {@link AssistantService#conversationKey} (user + company + chat id)
     * @param ctx          the calculation the answer is about ({@code ctx.focus()} is the snapshot)
     * @param conditions   scenario conditions that are active now
     */
    public Reply chat(String conversation, String question, Context ctx, List<Condition> conditions) {
        Map<String, Condition> sent = new LinkedHashMap<>();
        for (int i = 0; i < conditions.size(); i++) {
            sent.put("c" + (i + 1), conditions.get(i));
        }
        List<MlCoreApi.ChatMessage> history = history(conversation);
        Run run = ctx.focus();
        Map<String, MlCoreApi.SnapshotEvent> events = events(run);

        MlCoreApi.ChatResponse response;
        try {
            response = client.chat(new MlCoreApi.ChatRequest(question, history,
                    snapshot(run, events, scenarios(sent, events, run))));
        } catch (MlCoreException e) {
            if (e.getUpstreamStatus() != 422) {
                throw e;
            }
            // The server rejected the structure (it is picky about snapshots / scenarios): one lean retry.
            log.warn("ML Core /v1/chat answered 422, retrying with a lean snapshot");
            response = client.chat(new MlCoreApi.ChatRequest(question,
                    history.size() > 10 ? history.subList(history.size() - 10, history.size()) : history,
                    snapshot(run, events, List.of())));
        }

        String answer = AssistantService.clean(response.answer());
        if (answer.isBlank()) {
            throw new MlCoreException("ML Core returned an empty chat answer", 0, null, null);
        }

        List<String> removed = response.removedScenarioIds() == null ? List.of() : response.removedScenarioIds();
        List<Applied> applied = new ArrayList<>();
        Map<String, Condition> result = new LinkedHashMap<>(sent);
        removed.forEach(result::remove);
        if (response.appliedScenarios() != null) {
            for (MlCoreApi.Scenario s : response.appliedScenarios()) {
                Applied a = map(s, events, run.asOf());
                applied.add(a);
                if (a.supported()) {
                    result.put(s.id() == null ? "n" + result.size() : s.id(), Condition.parse(a.condition()).get(0));
                }
            }
        }
        List<Condition> resulting = new ArrayList<>(result.values());
        boolean changed = !Condition.toMaps(resulting).equals(Condition.toMaps(conditions));

        remember(conversation, question, answer);
        return new Reply(answer, Boolean.TRUE.equals(response.clarificationNeeded()), applied, removed, resulting,
                changed);
    }

    // ---------------------------------------------------------------- memory

    private List<MlCoreApi.ChatMessage> history(String conversation) {
        List<MlCoreApi.ChatMessage> out = new ArrayList<>();
        if (chatMemory == null) {
            return out;
        }
        for (Message m : chatMemory.get(conversation)) {
            String role = m.getMessageType() == MessageType.USER ? "user"
                    : m.getMessageType() == MessageType.ASSISTANT ? "assistant" : null;
            String text = m.getText();
            if (role == null || text == null || text.isBlank()) {
                continue;
            }
            out.add(new MlCoreApi.ChatMessage(role,
                    text.length() > MAX_CONTENT ? text.substring(0, MAX_CONTENT) : text));
        }
        return out.size() > MAX_HISTORY ? new ArrayList<>(out.subList(out.size() - MAX_HISTORY, out.size())) : out;
    }

    private void remember(String conversation, String question, String answer) {
        if (chatMemory != null) {
            chatMemory.add(conversation, List.of(new UserMessage(question), new AssistantMessage(answer)));
        }
    }

    // ---------------------------------------------------------------- snapshot (our Run -> server schema)

    static Map<String, MlCoreApi.SnapshotEvent> events(Run run) {
        Map<String, MlCoreApi.SnapshotEvent> out = new LinkedHashMap<>();
        for (DayPoint p : run.points()) {
            int idx = 0;
            for (DayItem it : p.items()) {
                String id = it.eventId() != null ? "event-" + it.eventId() : "reg-" + p.date() + "-" + idx;
                idx++;
                Direction d = "INCOME".equals(it.direction()) ? Direction.INCOME : Direction.EXPENSE;
                out.put(id, new MlCoreApi.SnapshotEvent(id, p.date(), it.amount(), d.getLabel(), null, it.title(),
                        it.basis(), it.eventId() == null ? null : String.valueOf(it.eventId())));
            }
        }
        return out;
    }

    static MlCoreApi.Snapshot snapshot(Run run, Map<String, MlCoreApi.SnapshotEvent> events,
                                       List<MlCoreApi.Scenario> scenarios) {
        List<MlCoreApi.DailyBalance> days = run.points().stream()
                .map(p -> new MlCoreApi.DailyBalance(p.date(), p.inflow(), p.outflow(), p.balance()))
                .toList();
        List<MlCoreApi.SnapshotEvent> list = new ArrayList<>(events.values());
        BigDecimal maxGap = run.deepestGap() == null ? BigDecimal.ZERO : run.deepestGap().deficit();
        return new MlCoreApi.Snapshot(false, run.probability(), run.asOf(), run.asOf().plusDays(1), days,
                run.firstGap() == null ? null : run.firstGap().date(), maxGap, list, list, scenarios,
                run.startBalance(), run.runId(), run.warnings());
    }

    static List<MlCoreApi.Scenario> scenarios(Map<String, Condition> sent, Map<String, MlCoreApi.SnapshotEvent> events,
                                              Run run) {
        List<MlCoreApi.Scenario> out = new ArrayList<>();
        for (Map.Entry<String, Condition> e : sent.entrySet()) {
            Condition c = e.getValue();
            String id = e.getKey();
            String description = c.describe();
            description = description.length() > 500 ? description.substring(0, 500) : description;
            switch (c.type()) {
                case DELAY_PAYMENT -> out.add(new MlCoreApi.Scenario(id, "delay", targets(c.match(), true, events),
                        c.days(), null, null, null, null, description));
                case SHIFT_PAYMENT -> out.add(new MlCoreApi.Scenario(id, "shift_payment",
                        targets(c.match(), null, events), c.newDate() == null ? c.days() : null, null, null,
                        c.newDate(), null, description));
                case SKIP_PAYMENT -> out.add(new MlCoreApi.Scenario(id, "skip_payment",
                        targets(c.match(), null, events), null, null, null, null, null, description));
                case REVENUE_DROP -> out.add(new MlCoreApi.Scenario(id, "revenue_drop", List.of(), null,
                        plain(c.pct()), null, null, null, description));
                case FIXED_COST_GROWTH -> out.add(new MlCoreApi.Scenario(id, "fixed_cost_growth", List.of(), null,
                        plain(c.pct()), null, null, null, description));
                case EXTRA_EXPENSE, EXTRA_INCOME -> {
                    LocalDate date = c.date() != null ? c.date()
                            : run.asOf().plusDays(c.dayOffset() == null ? Condition.DEFAULT_OFFSET_DAYS : c.dayOffset());
                    Direction d = c.type() == Condition.Type.EXTRA_INCOME ? Direction.INCOME : Direction.EXPENSE;
                    out.add(new MlCoreApi.Scenario(id, c.type().code(), List.of(), null, null,
                            c.amount().toPlainString(), date, d.getLabel(), description));
                }
            }
        }
        return out;
    }

    private static List<String> targets(String match, Boolean income, Map<String, MlCoreApi.SnapshotEvent> events) {
        List<String> ids = new ArrayList<>();
        String needle = match == null ? null : match.toLowerCase(Locale.ROOT);
        for (MlCoreApi.SnapshotEvent e : events.values()) {
            boolean isIncome = Direction.INCOME.getLabel().equals(e.direction());
            if (income != null && income != isIncome) {
                continue;
            }
            if (needle == null || (e.counterpartyName() != null && e.counterpartyName().toLowerCase(Locale.ROOT).contains(needle))) {
                ids.add(e.eventId());
            }
        }
        return ids;
    }

    // ---------------------------------------------------------------- server scenario -> our Condition

    /**
     * Scenario kinds are a free string on the server and only {@code delay} has been confirmed, so a few spellings are
     * accepted; anything else (or anything our validation rejects) is reported as not calculated, never guessed.
     */
    static Applied map(MlCoreApi.Scenario s, Map<String, MlCoreApi.SnapshotEvent> events, LocalDate asOf) {
        String kind = s.kind() == null ? "" : s.kind().trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        String description = s.description() == null || s.description().isBlank() ? s.kind() : s.description();
        Map<String, Object> m = new LinkedHashMap<>();
        BigDecimal amount = decimal(s.amount());
        BigDecimal percent = decimal(s.percent());
        String match = commonTitle(s.targetEventIds(), events);
        try {
            switch (kind) {
                case "delay", "delay_payment", "delay_payments", "payment_delay" -> {
                    m.put("type", "delay_payment");
                    m.put("days", s.days() == null ? 0 : s.days());
                    putIfNotNull(m, "match", match);
                }
                case "revenue_drop", "revenue_decrease", "revenue_decline", "income_drop", "income_decrease" -> {
                    m.put("type", "revenue_drop");
                    m.put("pct", percent == null ? 0 : percent.doubleValue());
                }
                case "fixed_cost_growth", "fixed_costs_growth", "fixed_cost_increase", "fixed_costs_increase" -> {
                    m.put("type", "fixed_cost_growth");
                    m.put("pct", percent == null ? 0 : percent.doubleValue());
                }
                case "skip_payment", "skip", "cancel_payment" -> {
                    m.put("type", "skip_payment");
                    putIfNotNull(m, "match", match);
                }
                case "shift_payment", "shift", "move_payment", "reschedule" -> {
                    m.put("type", "shift_payment");
                    putIfNotNull(m, "match", match);
                    if (s.date() != null) {
                        m.put("newDate", s.date().toString());
                    } else {
                        m.put("days", s.days() == null ? 0 : s.days());
                    }
                }
                case "extra_expense", "add_expense", "one_time_expense", "unplanned_expense", "expense",
                     "extra_income", "add_income", "one_time_income", "income", "add_event", "add_operation",
                     "extra_payment" -> {
                    boolean income = kind.contains("income")
                            || (kind.startsWith("add_") || kind.equals("extra_payment"))
                            && Direction.INCOME.getLabel().equals(s.direction());
                    m.put("type", income ? "extra_income" : "extra_expense");
                    m.put("amount", amount == null ? 0 : amount.doubleValue());
                    if (s.date() != null) {
                        m.put("date", s.date().toString());
                    }
                }
                default -> {
                    return new Applied(s.id(), s.kind(), description, null, false,
                            "Тип сценария «" + s.kind() + "» пока не поддерживается расчётом");
                }
            }
            Condition parsed = Condition.parse(m).get(0);
            return new Applied(s.id(), s.kind(), parsed.describe(), parsed.toMap(), true, null);
        } catch (IllegalArgumentException e) {
            return new Applied(s.id(), s.kind(), description, null, false, "Не удалось применить: " + e.getMessage());
        }
    }

    private static String commonTitle(List<String> ids, Map<String, MlCoreApi.SnapshotEvent> events) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        Set<String> titles = new LinkedHashSet<>();
        for (String id : ids) {
            MlCoreApi.SnapshotEvent e = events.get(id);
            if (e == null || e.counterpartyName() == null) {
                return null; // unknown target: apply to everything of that kind rather than to a wrong payment
            }
            titles.add(e.counterpartyName());
        }
        return titles.size() == 1 ? titles.iterator().next() : null;
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim().replace("%", "").replace(',', '.').replace(" ", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String plain(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static void putIfNotNull(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
