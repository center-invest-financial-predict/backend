package com.example.demo.controller;

import com.example.demo.assistant.AssistantService;
import com.example.demo.dto.auth.RegisterRequest;
import com.example.demo.dto.operation.OperationDto;
import com.example.demo.entity.Direction;
import com.example.demo.entity.Operation;
import com.example.demo.forecast.ForecastService;
import com.example.demo.forecast.SyntheticCompany;
import com.example.demo.repository.CashEventRepository;
import com.example.demo.repository.CompanySourceRepository;
import com.example.demo.repository.ForecastSettingsRepository;
import com.example.demo.repository.NotificationReadRepository;
import com.example.demo.repository.OperationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The endpoints added to close the frontend stubs: inline forecast, import confirm, sources, presets, bell, chat, profile. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FrontGapsIntegrationTest {

    private static final String INN = SyntheticCompany.INN;
    private static final String BASE = "/api/companies/" + INN;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OperationRepository operationRepository;
    @Autowired
    private CashEventRepository cashEventRepository;
    @Autowired
    private CompanySourceRepository companySourceRepository;
    @Autowired
    private NotificationReadRepository notificationReadRepository;
    @Autowired
    private ForecastSettingsRepository forecastSettingsRepository;
    @Autowired
    private ForecastService forecastService;
    @Autowired
    private ChatMemory chatMemory;
    @Autowired
    private AssistantService assistantService;

    @Value("${app.integration.api-key}")
    private String integrationApiKey;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private String ownerEmail;
    private String colleagueEmail;
    private String ownerToken;
    private String colleagueToken;
    private String outsiderToken;

    @BeforeEach
    void setUp() throws Exception {
        notificationReadRepository.deleteAll();
        companySourceRepository.deleteAll();
        forecastSettingsRepository.deleteAll();
        operationRepository.deleteAll();
        cashEventRepository.deleteAll();
        forecastService.invalidate(INN);
        ownerEmail = "owner-" + UUID.randomUUID() + "@example.com";
        colleagueEmail = "colleague-" + UUID.randomUUID() + "@example.com";
        ownerToken = register(ownerEmail);
        colleagueToken = register(colleagueEmail);
        outsiderToken = register("outsider-" + UUID.randomUUID() + "@example.com");
        link(ownerToken);
        link(colleagueToken);
    }

    // ---------------------------------------------------------------- POST /forecast

    @Test
    void forecastAcceptsConditionsAndHorizonInTheBody() throws Exception {
        pushHistory();
        JsonNode base = getJson(BASE + "/forecast?horizonDays=30", ownerToken);

        JsonNode delayed = postJson(BASE + "/forecast", ownerToken, Map.of("horizonDays", 14,
                "conditions", List.of(Map.of("type", "delay_payment", "days", 10))));
        assertThat(delayed.get("points")).hasSize(14);
        assertThat(delayed.toString()).contains("10 дн.");

        // skip_payment: a regular expense inside the horizon does not happen -> the minimum is higher
        JsonNode regular = getJson(BASE + "/events/regular", ownerToken);
        JsonNode expense = null;
        for (JsonNode r : regular) {
            LocalDate next = LocalDate.parse(r.get("nextDate").asText());
            if (r.get("direction").asText().toUpperCase().contains("EXPENSE")
                    && !next.isAfter(LocalDate.parse("2026-10-25")) && next.isAfter(LocalDate.parse("2026-09-30"))) {
                expense = r;
                break;
            }
        }
        assertThat(expense).as("a regular expense within 30 days").isNotNull();
        JsonNode skipped = postJson(BASE + "/forecast", ownerToken, Map.of("horizonDays", 30,
                "conditions", List.of(Map.of("type", "skip_payment", "match", expense.get("title").asText()))));
        assertThat(skipped.get("summary").get("minBalance").decimalValue())
                .isGreaterThan(base.get("summary").get("minBalance").decimalValue());

        mockMvc.perform(auth(post(BASE + "/forecast"), ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("conditions", List.of(Map.of("type", "skip_payment"))))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(post(BASE + "/forecast"), outsiderToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- events import: preview + confirm

    @Test
    void importPreviewSavesNothingAndConfirmSavesTheChosenRows() throws Exception {
        pushHistory();
        String csv = "date,title,direction,amount,category\n"
                + "2026-10-10,Аренда склада,EXPENSE,50000,Аренда\n"
                + "2026-10-12,Плохая строка,EXPENSE,-5,\n";
        MockMultipartFile file = new MockMultipartFile("file", "events.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        MvcResult preview = mockMvc.perform(multipart(BASE + "/events/import/preview").file(file)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andReturn();
        JsonNode p = json.readTree(preview.getResponse().getContentAsString());
        assertThat(p.get("validCount").asInt()).isEqualTo(1);
        assertThat(p.get("invalidCount").asInt()).isEqualTo(1);
        assertThat(p.get("rows").get(0).get("valid").asBoolean()).isTrue();
        assertThat(p.get("rows").get(1).get("error").asText()).isNotBlank();
        assertThat(getJson(BASE + "/events", ownerToken)).isEmpty();

        JsonNode confirmed = postJson(BASE + "/events/import/confirm", ownerToken, Map.of("rows", List.of(
                Map.of("eventDate", "2026-10-10", "title", "Аренда склада", "direction", "EXPENSE",
                        "amount", 50000, "category", "Аренда"),
                Map.of("eventDate", "2026-10-12", "title", "Нулевой", "direction", "EXPENSE", "amount", 0))));
        assertThat(confirmed.get("created")).hasSize(1);
        assertThat(confirmed.get("rejected")).hasSize(1);
        assertThat(confirmed.get("rejected").get(0).get("index").asInt()).isEqualTo(1);
        assertThat(getJson(BASE + "/events", ownerToken)).hasSize(1);
        assertThat(getJson(BASE + "/recalc-journal", ownerToken).get(0).get("message").asText())
                .contains("Подтверждён импорт");

        mockMvc.perform(multipart(BASE + "/events/import/preview").file(file)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(auth(post(BASE + "/events/import/confirm"), outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void importPreviewAcceptsExcelStyleSemicolonFileWithRussianDatesAndOperationColumns() throws Exception {
        pushHistory();
        String csv = "operation_date;payment_purpose;direction;amount\r\n"
                + "10.10.2026;Аренда склада;Списание;50 000,50\r\n"
                + ";;;\r\n";
        MockMultipartFile file = new MockMultipartFile("file", "events.csv", "text/csv",
                csv.getBytes(java.nio.charset.Charset.forName("windows-1251")));

        MvcResult preview = mockMvc.perform(multipart(BASE + "/events/import/preview").file(file)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andReturn();
        JsonNode p = json.readTree(preview.getResponse().getContentAsString());
        assertThat(p.get("validCount").asInt()).isEqualTo(1);
        assertThat(p.get("invalidCount").asInt()).isZero();
        assertThat(p.get("rows").get(0).get("eventDate").asText()).isEqualTo("2026-10-10");
        assertThat(p.get("rows").get(0).get("title").asText()).isEqualTo("Аренда склада");
        assertThat(p.get("rows").get(0).get("amount").decimalValue()).isEqualByComparingTo("50000.50");
    }

    // ---------------------------------------------------------------- sources toggle

    @Test
    void disablingASourceRemovesItFromTheForecast() throws Exception {
        pushHistory();
        BigDecimal before = getJson(BASE + "/dashboard", ownerToken).get("currentBalance").decimalValue();

        pushOperation("GS-1", "Gos_service", new BigDecimal("700000"), Direction.EXPENSE);
        forecastService.invalidate(INN);
        BigDecimal withExtra = getJson(BASE + "/dashboard", ownerToken).get("currentBalance").decimalValue();
        assertThat(withExtra).isEqualByComparingTo(before.subtract(new BigDecimal("700000")));

        JsonNode sources = getJson(BASE + "/sources", ownerToken);
        assertThat(find(sources.get("sources"), "code", "Gos_service").get("enabled").asBoolean()).isTrue();

        JsonNode off = putJson(BASE + "/sources/Gos_service", ownerToken, Map.of("enabled", false));
        assertThat(off.get("enabled").asBoolean()).isFalse();
        assertThat(off.get("operationCount").asLong()).isEqualTo(1);
        assertThat(find(getJson(BASE + "/sources", ownerToken).get("sources"), "code", "Gos_service")
                .get("enabled").asBoolean()).isFalse();
        BigDecimal without = getJson(BASE + "/dashboard", ownerToken).get("currentBalance").decimalValue();
        assertThat(without).isEqualByComparingTo(before);
        assertThat(getJson(BASE + "/recalc-journal", ownerToken).get(0).get("message").asText())
                .contains("Источник отключён");

        putJson(BASE + "/sources/Gos_service", ownerToken, Map.of("enabled", true));
        assertThat(getJson(BASE + "/dashboard", ownerToken).get("currentBalance").decimalValue())
                .isEqualByComparingTo(withExtra);

        mockMvc.perform(auth(put(BASE + "/sources/nope"), ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(put(BASE + "/sources/Gos_service"), outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void chosenSourcesAreTheOnlyOnesUsedAndSyncAsksOnlyThem() throws Exception {
        pushHistory();
        BigDecimal before = getJson(BASE + "/dashboard", ownerToken).get("currentBalance").decimalValue();
        pushOperation("GS-2", "Gos_service", new BigDecimal("700000"), Direction.EXPENSE);
        forecastService.invalidate(INN);
        assertThat(getJson(BASE + "/dashboard", ownerToken).get("currentBalance").decimalValue())
                .isEqualByComparingTo(before.subtract(new BigDecimal("700000")));
        assertThat(listedSources(getJson(BASE + "/operations?size=500", ownerToken))).contains("Gos_service");

        // "take data from 1C and csv only"
        JsonNode chosen = putJson(BASE + "/sources", ownerToken, Map.of("sources", List.of("1C", "csv")));
        assertThat(find(chosen.get("sources"), "code", "1C").get("enabled").asBoolean()).isTrue();
        assertThat(find(chosen.get("sources"), "code", "csv").get("enabled").asBoolean()).isTrue();
        assertThat(find(chosen.get("sources"), "code", "Gos_service").get("enabled").asBoolean()).isFalse();
        assertThat(getJson(BASE + "/dashboard", ownerToken).get("currentBalance").decimalValue())
                .isEqualByComparingTo(before);
        assertThat(getJson(BASE + "/recalc-journal", ownerToken).get(0).get("message").asText())
                .contains("Выбраны источники данных");
        // the operations list shows what the forecast is built on; an explicit source filter still shows the rest
        assertThat(listedSources(getJson(BASE + "/operations?size=500", ownerToken))).doesNotContain("Gos_service");
        assertThat(listedSources(getJson(BASE + "/operations?size=500&source=Gos_service", ownerToken)))
                .containsOnly("Gos_service");
        assertThat(getJson(BASE + "/search?q=" + "%D0%A8%D1%82%D1%80%D0%B0%D1%84", ownerToken)
                .get("operations")).isEmpty();

        // sync does not ask a switched-off source
        JsonNode sync = postJson(BASE + "/sync", ownerToken, Map.of());
        assertThat(find(sync.get("sources"), "code", "Gos_service").get("status").asText()).isEqualTo("disabled");
        assertThat(find(sync.get("sources"), "code", "1C").get("status").asText())
                .isIn("unavailable", "not_configured", "synced");
        mockMvc.perform(auth(post(BASE + "/sync?source=1C"), ownerToken)).andExpect(status().isOk());
        mockMvc.perform(auth(post(BASE + "/sync?source=csv"), ownerToken)).andExpect(status().isBadRequest());

        // at least one source must stay; unknown codes are rejected
        mockMvc.perform(auth(put(BASE + "/sources"), ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sources\":[]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(put(BASE + "/sources"), ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sources\":[\"nope\"]}"))
                .andExpect(status().isBadRequest());
        putJson(BASE + "/sources/csv", ownerToken, Map.of("enabled", false));
        mockMvc.perform(auth(put(BASE + "/sources/1C"), ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(put(BASE + "/sources"), outsiderToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sources\":[\"1C\"]}"))
                .andExpect(status().isForbidden());

        putJson(BASE + "/sources", ownerToken, Map.of("sources", List.of("1C", "csv", "Gos_service")));
        assertThat(getJson(BASE + "/dashboard", ownerToken).get("currentBalance").decimalValue())
                .isEqualByComparingTo(before.subtract(new BigDecimal("700000")));
    }

    @Test
    void newCompanyCanWorkOnlyFromAUploadedCsv() throws Exception {
        String inn = "77" + (10_000_000 + new java.util.Random().nextInt(89_999_999));
        String token = register("csv-only-" + UUID.randomUUID() + "@example.com");

        // a bad choice creates nothing
        mockMvc.perform(auth(post("/api/companies"), token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("inn", inn, "name", "ООО Новая", "sources", List.of("nope")))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(get("/api/companies/" + inn), token)).andExpect(status().isBadRequest()); // unknown INN

        JsonNode created = postJson("/api/companies", token,
                Map.of("inn", inn, "name", "ООО Новая", "industry", "Услуги", "sources", List.of("csv")));
        assertThat(created.get("company").get("inn").asText()).isEqualTo(inn);
        assertThat(created.get("syncStarted").asBoolean()).isFalse(); // nothing to pull: csv is uploaded
        JsonNode state = created.get("sources").get("sources");
        assertThat(find(state, "code", "csv").get("enabled").asBoolean()).isTrue();
        assertThat(find(state, "code", "1C").get("enabled").asBoolean()).isFalse();
        assertThat(find(state, "code", "Gos_service").get("enabled").asBoolean()).isFalse();
        assertThat(getJson("/api/companies", token).toString()).contains(inn);
        mockMvc.perform(auth(get("/api/companies/" + inn + "/dashboard"), token))
                .andExpect(status().isUnprocessableEntity()); // no data yet

        StringBuilder csv = new StringBuilder("operation_id;operation_date;amount;direction;payment_purpose\n");
        LocalDate day = LocalDate.parse("2026-08-01");
        for (int i = 0; i < 60; i++) {
            // the statement format uses ';' as the delimiter
            csv.append("I-").append(i).append(';').append(day.plusDays(i)).append(";100000;INCOME;Оплата по счёту\n");
            csv.append("E-").append(i).append(';').append(day.plusDays(i)).append(";60000;EXPENSE;Закупка\n");
        }
        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
        MvcResult upload = mockMvc.perform(multipart("/api/companies/" + inn + "/operations/csv").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(upload.getResponse().getContentAsString()).get("inserted").asInt()).isEqualTo(120);

        BigDecimal fromCsv = getJson("/api/companies/" + inn + "/dashboard", token).get("currentBalance").decimalValue();
        assertThat(fromCsv).isEqualByComparingTo("2400000");

        // data of another source does not take part: only the chosen csv is used
        mockMvc.perform(post("/api/integrations/operations")
                        .header("X-Integration-Key", integrationApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("source", "Gos_service", "inn", inn, "operations",
                                List.of(new OperationDto(null, null, "GS-X", LocalDate.parse("2026-09-29"),
                                        new BigDecimal("500000"), Direction.EXPENSE, "7700000001", "Госуслуги",
                                        "Штраф", null))))))
                .andExpect(status().isOk());
        forecastService.invalidate(inn);
        assertThat(getJson("/api/companies/" + inn + "/dashboard", token).get("currentBalance").decimalValue())
                .isEqualByComparingTo(fromCsv);
        JsonNode sync = postJson("/api/companies/" + inn + "/sync", token, Map.of());
        assertThat(sync.get("sources").toString()).contains("disabled");

        // the INN is taken now: it is linked, not created again
        mockMvc.perform(auth(post("/api/companies"), outsiderToken).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("inn", inn, "name", "Чужая", "sources", List.of("csv")))))
                .andExpect(status().isConflict());
        mockMvc.perform(auth(get("/api/companies/" + inn + "/dashboard"), outsiderToken))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- presets

    @Test
    void presetsComeFromTheSameEngineAndFollowCautionLevel() throws Exception {
        pushHistory();
        JsonNode r = getJson(BASE + "/forecast-presets", ownerToken);
        assertThat(r.get("activePreset").asText()).isEqualTo("base");
        JsonNode cons = find(r.get("presets"), "code", "cons");
        JsonNode base = find(r.get("presets"), "code", "base");
        JsonNode opt = find(r.get("presets"), "code", "opt");
        JsonNode own = find(r.get("presets"), "code", "own");
        assertThat(base.get("active").asBoolean()).isTrue();
        assertThat(base.get("impact").get("minBalanceDelta").decimalValue()).isEqualByComparingTo("0");
        assertThat(cons.get("conditions")).hasSize(2);
        assertThat(cons.get("minBalance").decimalValue()).isLessThan(base.get("minBalance").decimalValue());
        assertThat(cons.get("impact").get("minBalanceDelta").decimalValue().signum()).isNegative();
        assertThat(cons.get("gapProbability").decimalValue())
                .isGreaterThanOrEqualTo(base.get("gapProbability").decimalValue());
        assertThat(opt.get("minBalance").decimalValue()).isGreaterThanOrEqualTo(base.get("minBalance").decimalValue());
        assertThat(own.get("configured").asBoolean()).isFalse();

        // the base preset is the dashboard forecast
        assertThat(base.get("minBalance").decimalValue())
                .isEqualByComparingTo(getJson(BASE + "/dashboard", ownerToken).get("minBalance30d").decimalValue());

        // the user's own preset + caution level
        putJson(BASE + "/forecast-settings", ownerToken, Map.of("cautionLevel", "own", "presets", Map.of("own", Map.of(
                "name", "Ремонт", "conditions", List.of(Map.of("type", "extra_expense", "amount", 300000, "dayOffset", 3))))));
        JsonNode r2 = getJson(BASE + "/forecast-presets", ownerToken);
        assertThat(r2.get("activePreset").asText()).isEqualTo("own");
        JsonNode own2 = find(r2.get("presets"), "code", "own");
        assertThat(own2.get("name").asText()).isEqualTo("Ремонт");
        assertThat(own2.get("configured").asBoolean()).isTrue();
        assertThat(own2.get("active").asBoolean()).isTrue();
        assertThat(own2.get("impact").get("minBalanceDelta").decimalValue().signum()).isNegative();

        putJson(BASE + "/forecast-settings", ownerToken, Map.of("cautionLevel", "conservative"));
        assertThat(getJson(BASE + "/forecast-presets", ownerToken).get("activePreset").asText()).isEqualTo("cons");

        mockMvc.perform(auth(put(BASE + "/forecast-settings"), ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cautionLevel\":\"yolo\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(put(BASE + "/forecast-settings"), ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"presets\":{\"own\":{\"conditions\":[{\"type\":\"teleport\"}]}}}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(get(BASE + "/forecast-presets"), outsiderToken)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- notifications

    @Test
    void notificationsHaveStableIdsAndPerUserReadState() throws Exception {
        pushHistory();
        BigDecimal balance = getJson(BASE + "/dashboard", ownerToken).get("currentBalance").decimalValue();
        postJson(BASE + "/events", ownerToken, Map.of("eventDate", "2026-10-05", "title", "Закупка оборудования",
                "direction", "EXPENSE", "amount", balance.add(new BigDecimal("500000")), "category", "Прочее"));

        JsonNode first = getJson(BASE + "/notifications", ownerToken);
        JsonNode second = getJson(BASE + "/notifications", ownerToken);
        assertThat(ids(first)).isEqualTo(ids(second));
        JsonNode gap = find(first.get("items"), "type", "GAP");
        assertThat(gap).isNotNull();
        assertThat(gap.get("id").asText()).startsWith("alert:gap:");
        assertThat(gap.get("read").asBoolean()).isFalse();
        long unread = first.get("unread").asLong();
        assertThat(unread).isEqualTo(first.get("items").size());
        assertThat(find(first.get("items"), "kind", "JOURNAL")).isNotNull();

        JsonNode afterRead = postJsonTemplate(BASE + "/notifications/{id}/read", ownerToken, gap.get("id").asText());
        assertThat(afterRead.get("unread").asLong()).isEqualTo(unread - 1);
        assertThat(find(afterRead.get("items"), "id", gap.get("id").asText()).get("read").asBoolean()).isTrue();
        // reading twice is harmless
        assertThat(postJsonTemplate(BASE + "/notifications/{id}/read", ownerToken, gap.get("id").asText())
                .get("unread").asLong()).isEqualTo(unread - 1);

        // the colleague has their own state
        assertThat(getJson(BASE + "/notifications", colleagueToken).get("unread").asLong()).isEqualTo(unread);

        mockMvc.perform(auth(post(BASE + "/notifications/{id}/read", "alert:nope"), ownerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(auth(get(BASE + "/notifications"), outsiderToken)).andExpect(status().isForbidden());
        mockMvc.perform(auth(post(BASE + "/notifications/read-all"), outsiderToken)).andExpect(status().isForbidden());

        JsonNode all = postJson(BASE + "/notifications/read-all", ownerToken, Map.of());
        assertThat(all.get("unread").asLong()).isZero();
        assertThat(getJson(BASE + "/notifications", colleagueToken).get("unread").asLong()).isEqualTo(unread);
    }

    // ---------------------------------------------------------------- assistant history

    @Test
    void assistantHistoryIsIsolatedByUserAndCompanyAndCanBeCleared() throws Exception {
        pushHistory();
        String key = assistantService.conversationKey(ownerEmail, INN, "chat-1");
        chatMemory.add(key, List.of(new UserMessage("Почему разрыв?"), new AssistantMessage("<think>x</think>Из-за налога.")));

        JsonNode h = getJson(BASE + "/assistant/history?conversationId=chat-1", ownerToken);
        assertThat(h.get("conversationId").asText()).isEqualTo("chat-1");
        assertThat(h.get("messages")).hasSize(2);
        assertThat(h.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(h.get("messages").get(0).get("content").asText()).isEqualTo("Почему разрыв?");
        assertThat(h.get("messages").get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(h.get("messages").get(1).get("content").asText()).isEqualTo("Из-за налога.");

        // another user, another chat id: nothing leaks
        assertThat(getJson(BASE + "/assistant/history?conversationId=chat-1", colleagueToken).get("messages")).isEmpty();
        assertThat(getJson(BASE + "/assistant/history?conversationId=chat-2", ownerToken).get("messages")).isEmpty();
        assertThat(getJson(BASE + "/assistant/history", ownerToken).get("conversationId").asText()).isEqualTo("default");
        mockMvc.perform(auth(get(BASE + "/assistant/history"), outsiderToken)).andExpect(status().isForbidden());
        mockMvc.perform(auth(delete(BASE + "/assistant/history"), outsiderToken)).andExpect(status().isForbidden());

        // clearing someone else's chat id does not touch mine
        mockMvc.perform(auth(delete(BASE + "/assistant/history?conversationId=chat-1"), colleagueToken))
                .andExpect(status().isNoContent());
        assertThat(getJson(BASE + "/assistant/history?conversationId=chat-1", ownerToken).get("messages")).hasSize(2);

        mockMvc.perform(auth(delete(BASE + "/assistant/history?conversationId=chat-1"), ownerToken))
                .andExpect(status().isNoContent());
        assertThat(getJson(BASE + "/assistant/history?conversationId=chat-1", ownerToken).get("messages")).isEmpty();
    }

    // ---------------------------------------------------------------- company profile

    @Test
    void profileCanBeEditedByLinkedUsersAndMembersAreListed() throws Exception {
        JsonNode updated = patchJson(BASE, ownerToken,
                Map.of("name", "ООО Ромашка", "legalForm", "ООО", "industry", "Торговля"));
        assertThat(updated.get("name").asText()).isEqualTo("ООО Ромашка");
        assertThat(updated.get("legalForm").asText()).isEqualTo("ООО");

        // partial update keeps the rest; an empty value clears the field
        JsonNode partial = patchJson(BASE, colleagueToken, Map.of("industry", ""));
        assertThat(partial.get("name").asText()).isEqualTo("ООО Ромашка");
        assertThat(partial.get("legalForm").asText()).isEqualTo("ООО");
        assertThat(partial.get("industry").isNull()).isTrue();
        assertThat(getJson(BASE, ownerToken).get("name").asText()).isEqualTo("ООО Ромашка");

        mockMvc.perform(auth(patch(BASE), ownerToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(auth(patch(BASE), outsiderToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Чужая\"}"))
                .andExpect(status().isForbidden());

        JsonNode members = getJson(BASE + "/members", ownerToken);
        // other tests of the shared context may have linked more users to the same demo INN
        assertThat(members.size()).isGreaterThanOrEqualTo(2);
        assertThat(members.toString()).doesNotContain("outsider-");
        JsonNode me = find(members, "email", ownerEmail);
        assertThat(me.get("current").asBoolean()).isTrue();
        assertThat(me.get("fullName").asText()).isEqualTo("Test User Middle");
        assertThat(find(members, "email", colleagueEmail).get("current").asBoolean()).isFalse();
        assertThat(members.toString()).doesNotContain("password");
        mockMvc.perform(auth(get(BASE + "/members"), outsiderToken)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private void pushHistory() throws Exception {
        List<OperationDto> dtos = new ArrayList<>();
        for (Operation o : SyntheticCompany.operations(240, 1_000_000)) {
            dtos.add(new OperationDto(null, null, o.getOperationId(), o.getOperationDate(), o.getAmount(),
                    o.getDirection(), o.getCounterpartyInn(), o.getCounterpartyName(), o.getPaymentPurpose(),
                    o.getBalanceEod()));
        }
        push("1C", dtos);
    }

    private void pushOperation(String id, String source, BigDecimal amount, Direction direction) throws Exception {
        push(source, List.of(new OperationDto(null, null, id, LocalDate.parse("2026-09-30"), amount, direction,
                "7700000001", "Госуслуги", "Штраф", null)));
    }

    private void push(String source, List<OperationDto> operations) throws Exception {
        mockMvc.perform(post("/api/integrations/operations")
                        .header("X-Integration-Key", integrationApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("source", source, "inn", INN, "operations", operations))))
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
        return send(post(url), token, body);
    }

    private JsonNode putJson(String url, String token, Object body) throws Exception {
        return send(put(url), token, body);
    }

    private JsonNode patchJson(String url, String token, Object body) throws Exception {
        return send(patch(url), token, body);
    }

    private JsonNode postJsonTemplate(String template, String token, String id) throws Exception {
        MvcResult r = mockMvc.perform(auth(post(template, id), token)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode send(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        MvcResult r = mockMvc.perform(auth(b, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private static List<String> listedSources(JsonNode page) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : page.get("content")) {
            out.add(n.get("source").asText());
        }
        return out;
    }

    private static List<String> ids(JsonNode notifications) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : notifications.get("items")) {
            out.add(n.get("id").asText());
        }
        return out;
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
