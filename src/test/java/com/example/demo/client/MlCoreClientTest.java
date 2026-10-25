package com.example.demo.client;

import com.example.demo.dto.mlcore.MlCoreApi;
import com.example.demo.exception.MlCoreException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MlCoreClientTest {

    private static final String BASE = "http://ml-core.test";

    private static final String FORECAST_JSON = """
            {"is_stub": true, "cash_gap_probability": null, "as_of_date": "2026-09-30", "start_date": "2026-10-01",
             "days": [{"date": "2026-10-01", "income": "1032", "expenses": "0", "balance": "51032"},
                      {"date": "2026-10-02", "income": "0", "expenses": "1033.50", "balance": "49998.50"}],
             "first_cash_gap_date": null, "maximum_cash_gap": "0",
             "events": [{"event_id": "x", "operation_date": "2026-10-01", "amount": "1", "direction": "Списание"}],
             "base_events": [], "active_scenarios": [], "baseline_balance": "50000",
             "snapshot_hash": "abc", "warnings": ["forecast_model_stub: demo replay of the latest 28 calendar days"]}
            """;

    private MockRestServiceServer server;
    private MockRestServiceServer slowServer;

    private MlCoreClient client(boolean enabled) {
        RestClient.Builder fast = RestClient.builder().baseUrl(BASE);
        RestClient.Builder slow = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(fast).build();
        slowServer = MockRestServiceServer.bindTo(slow).build();
        return new MlCoreClient(enabled, BASE + "/", fast.build(), slow.build());
    }

    @Test
    void forecastSendsRussianDirectionAndParsesStringNumbers() {
        MlCoreClient client = client(true);
        server.expect(requestTo(BASE + "/v1/forecasts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.operations[0].direction").value("Поступление"))
                .andExpect(jsonPath("$.operations[0].company_id").value("C001"))
                .andExpect(jsonPath("$.operations[0].operation_date").value("2026-09-30"))
                .andExpect(jsonPath("$.operations[0].amount").value(1000.5))
                .andExpect(jsonPath("$.as_of_date").value("2026-09-30"))
                .andExpect(jsonPath("$.opening_balance").value(50000))
                .andExpect(jsonPath("$.industry").value("Торговля"))
                .andExpect(jsonPath("$.legal_form").value("ООО"))
                .andRespond(withSuccess(FORECAST_JSON, MediaType.APPLICATION_JSON));

        MlCoreApi.ForecastResult r = client.forecast(new MlCoreApi.ForecastRequest(
                List.of(new MlCoreApi.Operation("C001", "o1", LocalDate.of(2026, 9, 30), new BigDecimal("1000.50"),
                        "Поступление", null, "ООО Тест", "Оплата", null)),
                LocalDate.of(2026, 9, 30), new BigDecimal("50000"), "Торговля", "ООО"));

        server.verify();
        assertThat(r.isStub()).isTrue();
        assertThat(r.cashGapProbability()).isNull();
        assertThat(r.days()).hasSize(2);
        assertThat(r.days().get(1).expenses()).isEqualByComparingTo("1033.50");
        assertThat(r.days().get(1).balance()).isEqualByComparingTo("49998.50");
        assertThat(r.warnings()).hasSize(1);
        assertThat(r.snapshotHash()).isEqualTo("abc");
    }

    @Test
    void serverErrorBecomesUnavailableAndValidationErrorBecomesRejected() {
        MlCoreClient client = client(true);
        server.expect(requestTo(BASE + "/health/ready")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(BASE + "/v1/forecasts")).andRespond(withStatus(HttpStatusCode.valueOf(422))
                .contentType(MediaType.APPLICATION_JSON).body("{\"detail\":\"bad\"}"));

        assertThatThrownBy(client::ready).isInstanceOfSatisfying(MlCoreException.class, e -> {
            assertThat(e.getUpstreamStatus()).isEqualTo(502);
            assertThat(e.isRejected()).isFalse();
        });
        assertThatThrownBy(() -> client.forecast(new MlCoreApi.ForecastRequest(List.of(), null, null)))
                .isInstanceOfSatisfying(MlCoreException.class, e -> {
                    assertThat(e.getUpstreamStatus()).isEqualTo(422);
                    assertThat(e.isRejected()).isTrue();
                    assertThat(e.getUpstreamBody()).contains("bad");
                });
    }

    @Test
    void disabledClientNeverCallsTheServer() {
        MlCoreClient client = client(false);
        assertThat(client.isEnabled()).isFalse();
        assertThatThrownBy(client::live).isInstanceOf(MlCoreException.class).hasMessageContaining("disabled");
        server.verify(); // no expectations, no requests
    }

    @Test
    void unreachableServerFailsFastWithMlCoreException() {
        MlCoreClient client = new MlCoreClient(true, "http://127.0.0.1:1", 300, 300, 300);
        long start = System.currentTimeMillis();
        assertThatThrownBy(client::ready).isInstanceOfSatisfying(MlCoreException.class,
                e -> assertThat(e.getUpstreamStatus()).isZero());
        assertThatThrownBy(() -> client.importReceipt("r.png", new byte[]{1}, "image/png", "C001"))
                .isInstanceOf(MlCoreException.class);
        assertThat(System.currentTimeMillis() - start).isLessThan(5_000);
    }

    @Test
    void tableImportIsSentAsMultipartWithCompanyAndSheet() {
        MlCoreClient client = client(true);
        slowServer.expect(requestTo(BASE + "/v1/imports/table"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", org.hamcrest.Matchers.startsWith("multipart/form-data")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"company_id\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("C001")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("filename=\"s.csv\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"sheet_name\"")))
                .andRespond(withSuccess("""
                        {"rows": [{"row_number": 2, "operation": {"operation_date": "2026-09-01", "amount": "15000.50",
                                   "direction": "Поступление", "operation_id": null},
                                   "missing_fields": [], "warnings": [], "ready_for_review": true}],
                         "column_mapping": {"amount": "Сумма", "balance_eod": null}, "warnings": ["w"]}
                        """, MediaType.APPLICATION_JSON));

        MlCoreApi.TableImportResponse r = client.importTable("s.csv",
                "a;b\n1;2\n".getBytes(StandardCharsets.UTF_8), "text/csv", "C001", "Лист1");

        slowServer.verify();
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).operation()).containsEntry("amount", "15000.50");
        assertThat(r.rows().get(0).readyForReview()).isTrue();
        assertThat(r.columnMapping()).containsEntry("amount", "Сумма").containsKey("balance_eod");
    }
}
