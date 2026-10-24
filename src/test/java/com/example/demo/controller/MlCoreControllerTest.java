package com.example.demo.controller;

import com.example.demo.client.MlCoreClient;
import com.example.demo.dto.auth.RegisterRequest;
import com.example.demo.dto.mlcore.MlCoreApi;
import com.example.demo.exception.MlCoreException;
import com.example.demo.forecast.ForecastService;
import com.example.demo.forecast.SyntheticCompany;
import com.example.demo.repository.CashEventRepository;
import com.example.demo.repository.OperationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ML Core endpoints through HTTP with the ML Core HTTP client mocked (no network). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MlCoreControllerTest {

    private static final String INN = SyntheticCompany.INN;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OperationRepository operationRepository;
    @Autowired
    private CashEventRepository cashEventRepository;
    @Autowired
    private ForecastService forecastService;

    @MockitoBean
    private MlCoreClient mlCoreClient;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private String ownerToken;
    private String outsiderToken;

    @BeforeEach
    void setUp() throws Exception {
        operationRepository.deleteAll();
        cashEventRepository.deleteAll();
        forecastService.invalidate(INN);
        ownerToken = register("owner-" + UUID.randomUUID() + "@example.com");
        outsiderToken = register("outsider-" + UUID.randomUUID() + "@example.com");
        mockMvc.perform(auth(put("/api/users/me/inns"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("inns", List.of(INN)))))
                .andExpect(status().isOk());
    }

    @Test
    void healthNeverFailsEvenWhenDisabledOrDown() throws Exception {
        when(mlCoreClient.isEnabled()).thenReturn(false);
        mockMvc.perform(auth(get("/api/ml/core/health"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        when(mlCoreClient.isEnabled()).thenReturn(true);
        when(mlCoreClient.live()).thenThrow(new MlCoreException("down", 0, null, null));
        mockMvc.perform(auth(get("/api/ml/core/health"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWN"));

        mockMvc.perform(get("/api/ml/core/health")).andExpect(status().is4xxClientError());
    }

    @Test
    void companyEndpointsKeepOwnershipChecks() throws Exception {
        mockMvc.perform(auth(get("/api/ml/core/companies/" + INN + "/forecast"), outsiderToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(auth(multipart("/api/companies/" + INN + "/operations/import/preview")
                        .file(new MockMultipartFile("file", "s.csv", "text/csv", "a".getBytes())), outsiderToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(auth(post("/api/companies/" + INN + "/operations/import/confirm"), outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operations\":[{\"operation_date\":\"2026-09-01\",\"amount\":1,\"direction\":\"Списание\"}]}"))
                .andExpect(status().isForbidden());
        // forecast of a company without history -> 422 like every other forecast screen
        mockMvc.perform(auth(get("/api/ml/core/companies/" + INN + "/forecast"), ownerToken))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void previewAndConfirmFlowSavesReviewedRowsOnce() throws Exception {
        MlCoreApi.ImportRow row = new MlCoreApi.ImportRow(2, Map.of(
                "operation_date", "2026-09-01", "amount", "15000.50", "direction", "Поступление",
                "counterparty_name", "ООО Ромашка", "payment_purpose", "Оплата по счету 12"),
                List.of(), List.of(), true);
        when(mlCoreClient.importTable(eq("s.csv"), any(), any(), any(), any()))
                .thenReturn(new MlCoreApi.TableImportResponse(List.of(row), Map.of("amount", "Сумма"), List.of("w")));

        MockMultipartFile file = new MockMultipartFile("file", "s.csv", "text/csv",
                "Дата;Сумма\n2026-09-01;15000,50\n".getBytes(StandardCharsets.UTF_8));
        MvcResult preview = mockMvc.perform(auth(multipart("/api/companies/" + INN + "/operations/import/preview")
                        .file(file), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("ml-core"))
                .andExpect(jsonPath("$.rows[0].operation.amount").value("15000.50"))
                .andExpect(jsonPath("$.rows[0].readyForReview").value(true))
                .andExpect(jsonPath("$.columnMapping.amount").value("Сумма"))
                .andReturn();
        assertThat(operationRepository.count()).isZero(); // preview saves nothing

        // the rows of the preview are posted back as they are (snake_case keys, no operation_id)
        JsonNode rows = json.readTree(preview.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("rows");
        String body = json.writeValueAsString(Map.of("operations", List.of(rows.get(0).get("operation"))));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(auth(post("/api/companies/" + INN + "/operations/import/confirm"), ownerToken)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.inserted").value(attempt == 0 ? 1 : 0))
                    .andExpect(jsonPath("$.duplicates").value(attempt == 0 ? 0 : 1));
        }
        assertThat(operationRepository.count()).isEqualTo(1);
    }

    @Test
    void mlCoreFailuresAreMappedNotThrown() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "r.png", "image/png", new byte[]{1, 2, 3});
        when(mlCoreClient.importReceipt(any(), any(), any(), any()))
                .thenThrow(new MlCoreException("down", 0, null, null));
        mockMvc.perform(auth(multipart("/api/companies/" + INN + "/operations/import/receipt").file(file), ownerToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ML_CORE_UNAVAILABLE"));

        doThrow(new MlCoreException("rejected", 422, "{\"detail\":\"model response did not match\"}", null))
                .when(mlCoreClient).importReceipt(any(), any(), any(), any());
        mockMvc.perform(auth(multipart("/api/companies/" + INN + "/operations/import/receipt").file(file), ownerToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ML_CORE_REJECTED"));

        mockMvc.perform(auth(multipart("/api/companies/" + INN + "/operations/import/receipt")
                        .file(new MockMultipartFile("file", "e.png", "image/png", new byte[0])), ownerToken))
                .andExpect(status().isBadRequest());
    }

    private static <B extends AbstractMockHttpServletRequestBuilder<B>> B auth(B builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
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
