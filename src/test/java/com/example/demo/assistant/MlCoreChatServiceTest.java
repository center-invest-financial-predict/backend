package com.example.demo.assistant;

import com.example.demo.assistant.AssistantContextBuilder.Context;
import com.example.demo.client.MlCoreClient;
import com.example.demo.dto.mlcore.MlCoreApi;
import com.example.demo.exception.MlCoreException;
import com.example.demo.forecast.Condition;
import com.example.demo.forecast.ForecastFixture;
import com.example.demo.forecast.ForecastTypes.Run;
import com.example.demo.forecast.ServerForecastSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MlCoreChatServiceTest {

    private static final String BASE = "http://ml-core.test";
    private static final String CHAT = BASE + "/v1/chat";
    private static final String ALICE = "u1:c" + ForecastFixture.INN + ":default";
    private static final String BOB = "u2:c" + ForecastFixture.INN + ":default";

    private MockRestServiceServer server;
    private ChatMemory memory;
    private MlCoreChatService chat;
    private Run run;
    private Context ctx;

    @BeforeEach
    void setUp() {
        RestClient.Builder slow = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(slow).build();
        MlCoreClient client = new MlCoreClient(true, BASE, RestClient.builder().baseUrl(BASE).build(), slow.build());
        memory = MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20).build();
        chat = new MlCoreChatService(client, memory);
        run = new ForecastFixture(ops -> ServerForecastSource.disabled()).service.baseline(ForecastFixture.INN);
        ctx = new Context("facts", AssistantContextBuilder.figures(run), run, run);
    }

    private static String answer(String text, String scenariosJson, String removedJson) {
        return "{\"answer\": \"" + text + "\", \"clarification_needed\": false, \"applied_scenarios\": " + scenariosJson
                + ", \"removed_scenario_ids\": " + removedJson + ", \"forecast\": {\"is_stub\": true}}";
    }

    private static final String DELAY_7 = "[{\"id\": \"s1\", \"kind\": \"delay\", \"target_event_ids\": [], \"days\": 7,"
            + " \"description\": \"Задержать поступления на 7 дней\"}]";

    @Test
    void snapshotIsBuiltFromOurRunAndServerScenarioBecomesOurCondition() {
        server.expect(once(), requestTo(CHAT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.message").value("Что если клиенты задержат оплату на неделю?"))
                .andExpect(jsonPath("$.history.length()").value(0))
                .andExpect(jsonPath("$.snapshot.is_stub").value(false))
                .andExpect(jsonPath("$.snapshot.snapshot_hash").value(run.runId()))
                .andExpect(jsonPath("$.snapshot.cash_gap_probability").value(run.probability()))
                .andExpect(jsonPath("$.snapshot.as_of_date").value(run.asOf().toString()))
                .andExpect(jsonPath("$.snapshot.days.length()").value(30))
                .andExpect(jsonPath("$.snapshot.days[0].date").value(run.asOf().plusDays(1).toString()))
                .andExpect(jsonPath("$.snapshot.events").isNotEmpty())
                .andRespond(withSuccess(answer("Поступления сдвинутся на 7 дней.", DELAY_7, "[]"),
                        MediaType.APPLICATION_JSON));

        MlCoreChatService.Reply reply = chat.chat(ALICE, "Что если клиенты задержат оплату на неделю?", ctx, List.of());

        server.verify();
        assertThat(reply.answer()).isEqualTo("Поступления сдвинутся на 7 дней.");
        assertThat(reply.changed()).isTrue();
        assertThat(reply.conditions()).hasSize(1);
        assertThat(reply.conditions().get(0).type()).isEqualTo(Condition.Type.DELAY_PAYMENT);
        assertThat(reply.conditions().get(0).days()).isEqualTo(7);
        assertThat(reply.applied()).singleElement().satisfies(a -> {
            assertThat(a.supported()).isTrue();
            assertThat(a.condition()).containsEntry("type", "delay_payment").containsEntry("days", 7);
        });
    }

    @Test
    void historyComesFromOurMemoryAndIsIsolatedPerUser() {
        // expectations are consumed in order
        server.expect(once(), requestTo(CHAT))
                .andExpect(jsonPath("$.history.length()").value(0))
                .andRespond(withSuccess(answer("Первый ответ.", "[]", "[]"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(CHAT))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[0].role").value("user"))
                .andExpect(jsonPath("$.history[0].content").value("Первый вопрос"))
                .andExpect(jsonPath("$.history[1].role").value("assistant"))
                .andRespond(withSuccess(answer("Второй ответ.", "[]", "[]"), MediaType.APPLICATION_JSON));
        // another user of the same company never sees Alice's history
        server.expect(once(), requestTo(CHAT))
                .andExpect(jsonPath("$.history.length()").value(0))
                .andRespond(withSuccess(answer("Ответ Бобу.", "[]", "[]"), MediaType.APPLICATION_JSON));

        chat.chat(ALICE, "Первый вопрос", ctx, List.of());
        chat.chat(ALICE, "Второй вопрос", ctx, List.of());
        chat.chat(BOB, "Вопрос Боба", ctx, List.of());

        server.verify();
        assertThat(memory.get(ALICE)).hasSize(4);
        assertThat(memory.get(BOB)).hasSize(2);
    }

    @Test
    void unknownScenarioKindIsReportedAndNotCalculated() {
        String json = "[{\"id\": \"s1\", \"kind\": \"magic_boost\", \"percent\": \"50\"}]";
        server.expect(once(), requestTo(CHAT))
                .andRespond(withSuccess(answer("Применил.", json, "[]"), MediaType.APPLICATION_JSON));

        MlCoreChatService.Reply reply = chat.chat(ALICE, "сделай магию", ctx, List.of());

        assertThat(reply.changed()).isFalse();
        assertThat(reply.conditions()).isEmpty();
        assertThat(reply.applied()).singleElement().satisfies(a -> {
            assertThat(a.supported()).isFalse();
            assertThat(a.condition()).isNull();
            assertThat(a.reason()).contains("magic_boost");
        });
    }

    @Test
    void invalidScenarioValuesAreRejectedByOurValidation() {
        String json = "[{\"id\": \"s1\", \"kind\": \"revenue_drop\", \"percent\": \"250\"}]";
        server.expect(once(), requestTo(CHAT))
                .andRespond(withSuccess(answer("Применил.", json, "[]"), MediaType.APPLICATION_JSON));

        MlCoreChatService.Reply reply = chat.chat(ALICE, "выручка -250%", ctx, List.of());

        assertThat(reply.conditions()).isEmpty();
        assertThat(reply.applied().get(0).supported()).isFalse();
        assertThat(reply.applied().get(0).reason()).contains("pct");
    }

    @Test
    void severalKindsAreMappedAndRemovedScenariosDisappear() {
        String json = "[{\"id\": \"s2\", \"kind\": \"revenue_drop\", \"percent\": \"20\"},"
                + "{\"id\": \"s3\", \"kind\": \"extra_expense\", \"amount\": \"500000\", \"date\": \""
                + run.asOf().plusDays(3) + "\", \"direction\": \"Списание\"}]";
        server.expect(once(), requestTo(CHAT))
                // the active scenario we already had goes to the server in its vocabulary
                .andExpect(jsonPath("$.snapshot.active_scenarios[0].id").value("c1"))
                .andExpect(jsonPath("$.snapshot.active_scenarios[0].kind").value("delay"))
                .andExpect(jsonPath("$.snapshot.active_scenarios[0].days").value(14))
                .andRespond(withSuccess(answer("Готово.", json, "[\"c1\"]"), MediaType.APPLICATION_JSON));

        MlCoreChatService.Reply reply = chat.chat(ALICE, "убери задержку, выручка -20% и расход 500 тыс.", ctx,
                List.of(Condition.delayAllIncome(14)));

        assertThat(reply.changed()).isTrue();
        assertThat(reply.conditions()).extracting(Condition::type)
                .containsExactly(Condition.Type.REVENUE_DROP, Condition.Type.EXTRA_EXPENSE);
        assertThat(reply.conditions().get(0).pct()).isEqualTo(20.0);
        assertThat(reply.conditions().get(1).amount()).isEqualByComparingTo("500000");
        assertThat(reply.removedIds()).containsExactly("c1");
    }

    @Test
    void validationErrorIsRetriedOnceWithALeanSnapshot() {
        server.expect(once(), requestTo(CHAT))
                .andExpect(jsonPath("$.snapshot.active_scenarios.length()").value(1))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\": \"model response did not match the required schema\"}"));
        server.expect(once(), requestTo(CHAT))
                .andExpect(jsonPath("$.snapshot.active_scenarios.length()").value(0))
                .andRespond(withSuccess(answer("Со второй попытки.", "[]", "[]"), MediaType.APPLICATION_JSON));

        MlCoreChatService.Reply reply = chat.chat(ALICE, "вопрос", ctx, List.of(Condition.delayAllIncome(3)));

        server.verify();
        assertThat(reply.answer()).isEqualTo("Со второй попытки.");
        assertThat(reply.changed()).isFalse();
    }

    @Test
    void persistent422AndServerErrorsAreReportedForTheFallbackChain() {
        server.expect(once(), requestTo(CHAT)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
        server.expect(once(), requestTo(CHAT)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> chat.chat(ALICE, "q", ctx, List.of())).isInstanceOf(MlCoreException.class);

        server.reset();
        server.expect(once(), requestTo(CHAT)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> chat.chat(ALICE, "q", ctx, List.of()))
                .isInstanceOfSatisfying(MlCoreException.class, e -> assertThat(e.getUpstreamStatus()).isEqualTo(502));

        server.reset();
        server.expect(once(), requestTo(CHAT)).andRespond(withException(new IOException("Read timed out")));
        assertThatThrownBy(() -> chat.chat(ALICE, "q", ctx, List.of())).isInstanceOf(MlCoreException.class);

        assertThat(memory.get(ALICE)).isEmpty(); // failed turns are not remembered
    }

    @Test
    void thinkBlocksAreStrippedFromServerAnswer() {
        server.expect(once(), requestTo(CHAT)).andRespond(withSuccess(
                answer("<think>рассуждаю</think>Итог: хватает.", "[]", "[]"), MediaType.APPLICATION_JSON));
        assertThat(chat.chat(ALICE, "q", ctx, List.of()).answer()).isEqualTo("Итог: хватает.");
    }

    @Test
    void scenarioMappingHandlesPercentWithSignAndTargets() {
        Map<String, MlCoreApi.SnapshotEvent> events = MlCoreChatService.events(run);
        MlCoreApi.Scenario s = new MlCoreApi.Scenario("x", "Revenue-Drop", List.of(), null, "15%", null, null, null, null);
        MlCoreChatService.Applied a = MlCoreChatService.map(s, events, run.asOf());
        assertThat(a.supported()).isTrue();
        assertThat(a.condition()).containsEntry("type", "revenue_drop").containsEntry("pct", 15.0);
    }
}
