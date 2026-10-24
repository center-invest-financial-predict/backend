package com.example.demo.controller;

import com.example.demo.dto.auth.RegisterRequest;
import com.example.demo.dto.operation.OperationDto;
import com.example.demo.entity.Operation;
import com.example.demo.forecast.ForecastService;
import com.example.demo.forecast.SyntheticCompany;
import com.example.demo.repository.CashEventRepository;
import com.example.demo.repository.OperationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole minimum chain through HTTP:
 * data -> categories/regularity -> 30-day forecast -> alert -> new event/scenario -> recalculation -> assistant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ForecastFlowIntegrationTest {

    private static final String INN = SyntheticCompany.INN;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OperationRepository operationRepository;
    @Autowired
    private CashEventRepository cashEventRepository;
    @Autowired
    private ForecastService forecastService;

    @Value("${app.integration.api-key}")
    private String integrationApiKey;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private String ownerToken;
    private String colleagueToken;
    private String outsiderToken;

    @BeforeEach
    void setUp() throws Exception {
        operationRepository.deleteAll();
        cashEventRepository.deleteAll();
        forecastService.invalidate(INN);
        ownerToken = register("owner-" + UUID.randomUUID() + "@example.com");
        colleagueToken = register("colleague-" + UUID.randomUUID() + "@example.com");
        outsiderToken = register("outsider-" + UUID.randomUUID() + "@example.com");
        link(ownerToken);
        link(colleagueToken);
    }

    @Test
    void forecastNeedsHistory() throws Exception {
        mockMvc.perform(auth(get("/api/companies/" + INN + "/dashboard"), ownerToken))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void fullChainIsConsistentAndRecalculates() throws Exception {
        pushHistory();

        // --- one consistent calculation behind every screen
        JsonNode dash = getJson("/api/companies/" + INN + "/dashboard", ownerToken);
        JsonNode series = getJson("/api/companies/" + INN + "/forecast?horizonDays=30", ownerToken);
        assertThat(dash.get("dataAsOf").asText()).isEqualTo("2026-09-30");
        assertThat(series.get("points")).hasSize(30);
        assertThat(series.get("runId").asText()).isEqualTo(dash.get("runId").asText());
        assertThat(dash.get("source").asText()).isEqualTo("builtin"); // forecast.source=builtin in tests
        assertThat(series.get("source").asText()).isEqualTo("builtin");
        assertThat(series.get("summary").get("minBalance").decimalValue())
                .isEqualByComparingTo(dash.get("minBalance30d").decimalValue());
        assertThat(series.get("history").get(series.get("history").size() - 1).get("balance").decimalValue())
                .isEqualByComparingTo(dash.get("currentBalance").decimalValue());
        assertThat(dash.get("gapDate").isNull()).isTrue();

        // --- categories + regularity
        JsonNode categories = getJson("/api/companies/" + INN + "/cashflow/by-category", ownerToken);
        assertThat(categories.get("categories").toString()).contains("Зарплата").contains("Налоги");
        JsonNode regular = getJson("/api/companies/" + INN + "/events/regular", ownerToken);
        assertThat(regular.toString()).contains("Налоги и взносы").contains("monthly");
        assertThat(getJson("/api/companies/" + INN + "/operations?size=5", ownerToken).get("content").get(0).has("category")).isTrue();

        // --- new event: a payment bigger than the balance in five days
        BigDecimal balance = dash.get("currentBalance").decimalValue();
        String eventDate = "2026-10-05";
        mockMvc.perform(auth(post("/api/companies/" + INN + "/events"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "eventDate", eventDate, "title", "Закупка оборудования", "direction", "EXPENSE",
                                "amount", balance.add(new BigDecimal("500000")), "category", "Прочее"))))
                .andExpect(status().isOk());

        // --- the same recalculated numbers appear in dashboard, forecast, alerts, journal, day drill-down
        JsonNode dash2 = getJson("/api/companies/" + INN + "/dashboard", ownerToken);
        JsonNode series2 = getJson("/api/companies/" + INN + "/forecast", ownerToken);
        assertThat(dash2.get("runId").asText()).isNotEqualTo(dash.get("runId").asText());
        assertThat(dash2.get("gapDate").isNull()).isFalse();
        assertThat(dash2.get("gapAmount").decimalValue().signum()).isPositive();
        assertThat(series2.get("summary").get("gapDate").asText()).isEqualTo(dash2.get("gapDate").asText());
        assertThat(series2.get("summary").get("gapAmount").decimalValue())
                .isEqualByComparingTo(dash2.get("gapAmount").decimalValue());

        JsonNode alerts = getJson("/api/companies/" + INN + "/alerts", ownerToken);
        JsonNode gapAlert = find(alerts, "type", "GAP");
        assertThat(gapAlert).as("GAP alert").isNotNull();
        assertThat(gapAlert.get("date").asText()).isEqualTo(dash2.get("gapDate").asText());
        assertThat(gapAlert.get("amount").decimalValue()).isEqualByComparingTo(dash2.get("gapAmount").decimalValue());

        JsonNode journal = getJson("/api/companies/" + INN + "/recalc-journal", ownerToken);
        assertThat(journal.get(0).get("message").asText()).contains("Добавлено событие").contains("→");

        JsonNode day = getJson("/api/companies/" + INN + "/forecast/day?date=" + eventDate, ownerToken);
        assertThat(day.get("events").toString()).contains("Закупка оборудования");
        assertThat(day.get("endBalance").decimalValue().signum()).isNegative();

        JsonNode upcoming = getJson("/api/companies/" + INN + "/payments/upcoming", ownerToken);
        assertThat(find(upcoming, "title", "Закупка оборудования").get("origin").asText()).isEqualTo("event");

        JsonNode recommendations = getJson("/api/companies/" + INN + "/recommendations", ownerToken);
        for (JsonNode r : recommendations) {
            assertThat(r.get("afterGap").decimalValue()).isLessThan(r.get("beforeGap").decimalValue());
        }

        // --- scenario: preview == saved scenario applied to the forecast; scenarios are per user
        Map<String, Object> delay = Map.of("type", "delay_payment", "days", 10);
        JsonNode preview = postJson("/api/companies/" + INN + "/scenarios/preview", ownerToken, Map.of("conditions", List.of(delay)));
        JsonNode saved = postJson("/api/companies/" + INN + "/scenarios", ownerToken,
                Map.of("name", "Задержка оплат", "conditions", List.of(delay)));
        long scenarioId = saved.get("id").asLong();
        JsonNode withScenario = getJson("/api/companies/" + INN + "/forecast?scenarioId=" + scenarioId, ownerToken);
        assertThat(withScenario.get("summary").get("minBalance").decimalValue())
                .isEqualByComparingTo(preview.get("summary").get("minBalance").decimalValue());
        assertThat(withScenario.get("summary").get("minBalance").decimalValue())
                .isLessThan(series2.get("summary").get("minBalance").decimalValue());
        assertThat(saved.get("conditionsText").toString()).contains("10 дн.");
        mockMvc.perform(auth(get("/api/companies/" + INN + "/forecast?scenarioId=" + scenarioId), colleagueToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(auth(post("/api/companies/" + INN + "/scenarios/preview"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("conditions", List.of(Map.of("type", "teleport"))))))
                .andExpect(status().isBadRequest());

        // --- assistant explains exactly this calculation (LLM is not reachable in tests -> deterministic fallback)
        JsonNode answer = postJson("/api/companies/" + INN + "/assistant/ask", ownerToken,
                Map.of("question", "Почему возникает разрыв?", "scenarioId", scenarioId, "conversationId", "chat-1"));
        assertThat(answer.get("answer").asText()).isNotBlank();
        assertThat(answer.get("runId").asText()).isEqualTo(dash2.get("runId").asText());
        assertThat(answer.get("figures").get("minBalance").decimalValue())
                .isEqualByComparingTo(withScenario.get("summary").get("minBalance").decimalValue());
        assertThat(answer.get("scenario").toString()).contains("10 дн.");
        JsonNode context = getJson("/api/companies/" + INN + "/assistant/context?scenarioId=" + scenarioId, ownerToken);
        assertThat(context.get("facts").asText()).contains("СЦЕНАРИЙ").contains("Закупка оборудования")
                .contains(dash2.get("gapDate").asText());

        // --- access: the company is not visible to a user who is not linked to it
        mockMvc.perform(auth(post("/api/companies/" + INN + "/assistant/ask"), outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("question", "Что у них с деньгами?"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(auth(get("/api/companies/" + INN + "/dashboard"), outsiderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void eventsListFiltersByEffectiveOverdueStatus() throws Exception {
        pushHistory();
        postJson("/api/companies/" + INN + "/events", ownerToken, Map.of(
                "eventDate", "2026-09-20", "title", "Старое поступление", "direction", "INCOME", "amount", 100000));
        postJson("/api/companies/" + INN + "/events", ownerToken, Map.of(
                "eventDate", "2026-10-20", "title", "Будущий платёж", "direction", "EXPENSE", "amount", 10000));
        JsonNode overdue = getJson("/api/companies/" + INN + "/events?status=OVERDUE", ownerToken);
        assertThat(overdue).hasSize(1);
        assertThat(overdue.get(0).get("title").asText()).isEqualTo("Старое поступление");
        mockMvc.perform(auth(post("/api/companies/" + INN + "/events"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "eventDate", "2026-10-20", "title", "Нулевой", "direction", "EXPENSE", "amount", 0))))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- helpers

    private void pushHistory() throws Exception {
        List<OperationDto> dtos = new ArrayList<>();
        for (Operation o : SyntheticCompany.operations(240, 1_000_000)) {
            dtos.add(new OperationDto(null, null, o.getOperationId(), o.getOperationDate(), o.getAmount(),
                    o.getDirection(), o.getCounterpartyInn(), o.getCounterpartyName(), o.getPaymentPurpose(),
                    o.getBalanceEod()));
        }
        mockMvc.perform(post("/api/integrations/operations")
                        .header("X-Integration-Key", integrationApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("source", "1C", "inn", INN, "operations", dtos))))
                .andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    private JsonNode getJson(String url, String token) throws Exception {
        MvcResult r = mockMvc.perform(auth(get(url), token)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode postJson(String url, String token, Object body) throws Exception {
        MvcResult r = mockMvc.perform(auth(post(url), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (n.has(field) && value.equals(n.get(field).asText())) {
                return n;
            }
        }
        return null;
    }

    private void link(String token) throws Exception {
        mockMvc.perform(auth(put("/api/users/me/inns"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("inns", List.of(INN)))))
                .andExpect(status().isOk());
    }

    private String register(String email) throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(email);
        request.setPassword("Password1!");
        request.setLastName("Test");
        request.setFirstName("User");
        request.setMiddleName("Middle");
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
