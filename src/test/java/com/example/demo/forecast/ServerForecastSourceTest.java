package com.example.demo.forecast;

import com.example.demo.client.MlCoreClient;
import com.example.demo.entity.Company;
import com.example.demo.forecast.ForecastTypes.DayPoint;
import com.example.demo.forecast.ForecastTypes.Run;
import com.example.demo.repository.CompanyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ServerForecastSourceTest {

    private static final String BASE = "http://ml-core.test";
    private static final String INN = ForecastFixture.INN;
    private static final ObjectMapper JSON = new ObjectMapper();

    private MockRestServiceServer server;

    private ForecastFixture fixture(String mode) {
        RestClient.Builder fast = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(fast).build();
        MlCoreClient client = new MlCoreClient(true, BASE, fast.build(), RestClient.builder().baseUrl(BASE).build());
        CompanyRepository companies = Mockito.mock(CompanyRepository.class);
        Mockito.when(companies.findById(INN)).thenReturn(
                Optional.of(new Company(INN, "C001", "ООО Тест", "ООО", "Торговля")));
        return new ForecastFixture(ops -> new ServerForecastSource(client, ops, companies, mode, 365));
    }

    /** What the server would answer: the days of the built-in run shifted by {@code shift}, with a probability. */
    private static String serverAnswer(Run like, boolean stub, Double probability, BigDecimal shift, int dropDays) {
        List<Map<String, Object>> days = new ArrayList<>();
        for (DayPoint p : like.points()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("date", p.date().toString());
            d.put("income", p.inflow().toPlainString());
            d.put("expenses", p.outflow().toPlainString());
            d.put("balance", p.balance().add(shift).toPlainString());
            days.add(d);
        }
        for (int i = 0; i < dropDays; i++) {
            days.remove(days.size() - 1);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("is_stub", stub);
        body.put("cash_gap_probability", probability);
        body.put("as_of_date", like.asOf().toString());
        body.put("start_date", like.asOf().plusDays(1).toString());
        body.put("days", days);
        body.put("first_cash_gap_date", null);
        body.put("maximum_cash_gap", "0");
        body.put("events", List.of());
        body.put("base_events", List.of());
        body.put("active_scenarios", List.of());
        body.put("baseline_balance", like.startBalance().toPlainString());
        body.put("snapshot_hash", "h");
        body.put("warnings", List.of("from-server"));
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Run builtin() {
        return new ForecastFixture(ops -> ServerForecastSource.disabled()).service.baseline(INN);
    }

    @Test
    void realServerForecastIsAdoptedAndUsedByEveryRead() {
        Run reference = builtin();
        ForecastFixture f = fixture("auto");
        BigDecimal shift = reference.minBalance().add(BigDecimal.valueOf(10_000)).negate(); // pushes the minimum to -10 000
        server.expect(once(), requestTo(BASE + "/v1/forecasts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.as_of_date").value(reference.asOf().toString()))
                .andExpect(jsonPath("$.industry").value("Торговля"))
                .andExpect(jsonPath("$.legal_form").value("ООО"))
                .andExpect(jsonPath("$.operations[0].direction").exists())
                .andRespond(withSuccess(serverAnswer(reference, false, 0.37, shift, 0), MediaType.APPLICATION_JSON));

        Run run = f.service.baseline(INN);
        Run again = f.service.baseline(INN);

        server.verify();
        assertThat(run.source()).isEqualTo(Run.SOURCE_ML_CORE);
        assertThat(run.probability()).isEqualTo(0.37);
        assertThat(run.minBalance()).isEqualByComparingTo("-10000");
        assertThat(run.firstGap()).isNotNull();
        assertThat(run.firstGap().deficit().signum()).isPositive();
        assertThat(run.points().get(5).balance()).isEqualByComparingTo(reference.points().get(5).balance().add(shift));
        assertThat(run.warnings()).contains("from-server");
        assertThat(run.note()).contains("ML Core").contains("p10/p90");
        // the band and day items are the built-in ones, nothing is invented
        assertThat(run.points().get(5).p10()).isEqualByComparingTo(reference.points().get(5).p10());
        assertThat(run.points().get(5).items()).hasSameSizeAs(reference.points().get(5).items());
        assertThat(again).isSameAs(run); // one calculation for every screen
    }

    @Test
    void stubAnswerFallsBackToBuiltinWithExplanation() {
        Run reference = builtin();
        ForecastFixture f = fixture("auto");
        server.expect(once(), requestTo(BASE + "/v1/forecasts"))
                .andRespond(withSuccess(serverAnswer(reference, true, null, BigDecimal.ZERO, 0), MediaType.APPLICATION_JSON));

        Run run = f.service.baseline(INN);

        assertThat(run.source()).isEqualTo(Run.SOURCE_BUILTIN);
        assertThat(run.probability()).isEqualTo(reference.probability());
        assertThat(run.note()).contains("заглушк");
    }

    @Test
    void realFlagWithoutProbabilityIsStillNotUsed() {
        Run reference = builtin();
        ForecastFixture f = fixture("auto");
        server.expect(once(), requestTo(BASE + "/v1/forecasts"))
                .andRespond(withSuccess(serverAnswer(reference, false, null, BigDecimal.ZERO, 0), MediaType.APPLICATION_JSON));

        Run run = f.service.baseline(INN);

        assertThat(run.source()).isEqualTo(Run.SOURCE_BUILTIN);
        assertThat(run.note()).contains("вероятность");
    }

    @Test
    void serverErrorNeverBreaksTheForecastAndIsAskedOncePerCalculation() {
        ForecastFixture f = fixture("mlcore");
        server.expect(once(), requestTo(BASE + "/v1/forecasts")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        Run run = f.service.baseline(INN);
        Run week = f.service.baseline(INN, 7); // another horizon: no second server call (the failure is remembered)

        server.verify();
        assertThat(run.source()).isEqualTo(Run.SOURCE_BUILTIN);
        assertThat(run.points()).hasSize(30);
        assertThat(run.note()).contains("недоступен");
        assertThat(week.points()).hasSize(7);
        assertThat(week.source()).isEqualTo(Run.SOURCE_BUILTIN);
    }

    @Test
    void mismatchingDaysFallBackToBuiltin() {
        Run reference = builtin();
        ForecastFixture f = fixture("auto");
        server.expect(once(), requestTo(BASE + "/v1/forecasts"))
                .andRespond(withSuccess(serverAnswer(reference, false, 0.2, BigDecimal.ZERO, 3), MediaType.APPLICATION_JSON));

        Run run = f.service.baseline(INN);

        assertThat(run.source()).isEqualTo(Run.SOURCE_BUILTIN);
        assertThat(run.note()).contains("не совпадают");
    }

    @Test
    void scenariosAreCalculatedByBuiltinEngineAndSaySo() {
        Run reference = builtin();
        ForecastFixture f = fixture("auto");
        server.expect(once(), requestTo(BASE + "/v1/forecasts"))
                .andRespond(withSuccess(serverAnswer(reference, false, 0.2, BigDecimal.ZERO, 0), MediaType.APPLICATION_JSON));

        Run base = f.service.baseline(INN);
        Run scenario = f.service.run(INN, 30, List.of(Condition.delayAllIncome(7)));

        server.verify(); // the scenario did not trigger a second request
        assertThat(base.source()).isEqualTo(Run.SOURCE_ML_CORE);
        assertThat(scenario.source()).isEqualTo(Run.SOURCE_BUILTIN);
        assertThat(scenario.note()).contains("встроенным");
        assertThat(scenario.conditions()).hasSize(1);
    }

    @Test
    void builtinModeNeverCallsTheServer() {
        ForecastFixture f = fixture("builtin");

        Run run = f.service.baseline(INN); // MockRestServiceServer without expectations fails on any request

        assertThat(run.source()).isEqualTo(Run.SOURCE_BUILTIN);
        assertThat(run.note()).isNull();
        server.verify();
    }
}
