package com.example.demo.assistant;

import com.example.demo.assistant.AssistantContextBuilder.Context;
import com.example.demo.assistant.AssistantDto.AskRequest;
import com.example.demo.assistant.AssistantDto.AskResponse;
import com.example.demo.client.MlCoreClient;
import com.example.demo.entity.User;
import com.example.demo.forecast.ForecastFixture;
import com.example.demo.forecast.ForecastTypes.Run;
import com.example.demo.forecast.ServerForecastSource;
import com.example.demo.security.CustomUserDetailsService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AssistantServiceTest {

    private final CustomUserDetailsService users = Mockito.mock(CustomUserDetailsService.class);
    private final AssistantService service = new AssistantService(null, null, null, users, null, 5, "local");

    private void user(String email, long id) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        Mockito.when(users.loadUserByUsername(email)).thenReturn(u);
    }

    @Test
    void conversationsAreIsolatedByUserAndCompany() {
        user("a@x.ru", 1);
        user("b@x.ru", 2);
        String a = service.conversationKey("a@x.ru", "111", "default");
        assertThat(service.conversationKey("b@x.ru", "111", "default")).isNotEqualTo(a);
        assertThat(service.conversationKey("a@x.ru", "222", "default")).isNotEqualTo(a);
        assertThat(service.conversationKey("a@x.ru", "111", "other")).isNotEqualTo(a);
        assertThat(service.conversationKey("a@x.ru", "111", null)).isEqualTo(a);
    }

    @Test
    void clientCannotForgeAnotherNamespace() {
        user("a@x.ru", 1);
        user("b@x.ru", 2);
        String victim = service.conversationKey("b@x.ru", "111", "default");
        String forged = service.conversationKey("a@x.ru", "111", "u2:c111:default");
        assertThat(forged).isNotEqualTo(victim).startsWith("u1:c111:");
        assertThat(forged.substring("u1:c111:".length())).doesNotContain(":");
    }

    // ---------------------------------------------------------------- provider chain

    private static final String BASE = "http://ml-core.test";

    private MockRestServiceServer server;
    private AssistantContextBuilder builder;
    private ForecastFixture fixture;

    private AssistantService chainService(String provider) {
        RestClient.Builder slow = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(slow).build();
        MlCoreClient client = new MlCoreClient(true, BASE, RestClient.builder().baseUrl(BASE).build(), slow.build());
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
        fixture = new ForecastFixture(ops -> ServerForecastSource.disabled());
        Run base = fixture.service.baseline(ForecastFixture.INN);
        Run stress = fixture.service.stress(ForecastFixture.INN, 30);
        builder = Mockito.mock(AssistantContextBuilder.class);
        Mockito.when(builder.build(Mockito.eq(ForecastFixture.INN), Mockito.argThat(List::isEmpty), Mockito.any()))
                .thenReturn(new Context("facts", AssistantContextBuilder.figures(base), base, base));
        Mockito.when(builder.build(Mockito.eq(ForecastFixture.INN), Mockito.argThat(l -> !l.isEmpty()), Mockito.any()))
                .thenReturn(new Context("facts", AssistantContextBuilder.figures(stress), base, stress));
        Mockito.when(builder.calculationSummary(Mockito.any(), Mockito.anyBoolean())).thenReturn("Расчёт системы. OUR");
        Mockito.when(builder.fallbackAnswer(Mockito.any(), Mockito.anyBoolean())).thenReturn("SUMMARY");
        user("a@x.ru", 1);
        return new AssistantService(null, builder, null, users, new MlCoreChatService(client, memory), 5, provider);
    }

    private static AskRequest ask(String question) {
        return new AskRequest(question, null, null, null, null);
    }

    @Test
    void mlCoreAnswerIsUsedAndScenarioIsRecalculatedByOurEngine() {
        AssistantService s = chainService("ml-core");
        server.expect(once(), requestTo(BASE + "/v1/chat")).andRespond(withSuccess(
                "{\"answer\": \"Сдвинул на 7 дней.\", \"applied_scenarios\": [{\"id\": \"s1\", \"kind\": \"delay\","
                        + " \"days\": 7}], \"removed_scenario_ids\": [], \"forecast\": {}}", MediaType.APPLICATION_JSON));

        AskResponse r = s.ask("a@x.ru", ForecastFixture.INN, ask("а если оплата задержится на неделю?"));

        server.verify();
        assertThat(r.provider()).isEqualTo("ml-core");
        assertThat(r.source()).isEqualTo("llm");
        assertThat(r.answer()).startsWith("Сдвинул на 7 дней.").endsWith("Расчёт системы. OUR");
        assertThat(r.conditions()).singleElement().satisfies(m -> assertThat(m).containsEntry("type", "delay_payment"));
        assertThat(r.appliedScenarios()).singleElement().satisfies(a -> assertThat(a.supported()).isTrue());
        // numbers in the response are from our recalculation (the stress run), not from the server
        assertThat(r.figures().gapProbability().doubleValue())
                .isEqualTo(fixture.service.stress(ForecastFixture.INN, 30).probability());
        assertThat(r.scenario()).hasSize(1);
    }

    @Test
    void mlCoreFailureFallsBackToSummaryAndExplainsWhy() {
        AssistantService s = chainService("ml-core");
        server.expect(once(), requestTo(BASE + "/v1/chat")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        AskResponse r = s.ask("a@x.ru", ForecastFixture.INN, ask("что с деньгами?"));

        assertThat(r.provider()).isEqualTo("none");
        assertThat(r.source()).isEqualTo("fallback");
        assertThat(r.answer()).isEqualTo("SUMMARY");
        assertThat(r.note()).contains("ML Core chat").contains("502").contains("LLM unavailable");
        assertThat(r.appliedScenarios()).isEmpty();
    }

    @Test
    void mlCoreNetworkErrorFallsBackToo() {
        AssistantService s = chainService("auto");
        server.expect(once(), requestTo(BASE + "/v1/chat")).andRespond(withException(new IOException("Read timed out")));

        AskResponse r = s.ask("a@x.ru", ForecastFixture.INN, ask("что с деньгами?"));

        assertThat(r.source()).isEqualTo("fallback");
        assertThat(r.note()).contains("ML Core chat");
    }

    @Test
    void localProviderNeverCallsMlCore() {
        AssistantService s = chainService("local");

        AskResponse r = s.ask("a@x.ru", ForecastFixture.INN, ask("что с деньгами?"));

        server.verify(); // no expectations: any request would have failed the test
        assertThat(r.source()).isEqualTo("fallback");
        assertThat(r.note()).doesNotContain("ML Core");
    }

    @Test
    void stripsReasoningBlocksFromModelAnswer() {
        assertThat(AssistantService.clean("<think>внутренние рассуждения</think>\nИтог: хватает."))
                .isEqualTo("Итог: хватает.");
    }
}
