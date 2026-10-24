package com.example.demo.controller;

import com.example.demo.dto.auth.RegisterRequest;
import com.example.demo.dto.operation.ImportResult;
import com.example.demo.dto.operation.OperationDto;
import com.example.demo.entity.Direction;
import com.example.demo.entity.OperationSource;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperationsIntegrationTest {

    private static final String KNOWN_INN = "0098524470";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private OperationRepository operationRepository;

    @Value("${app.integration.api-key}")
    private String integrationApiKey;

    private String userAToken;
    private String userBToken;

    @BeforeEach
    void setUp() throws Exception {
        operationRepository.deleteAll();
        userAToken = registerUser("a-" + UUID.randomUUID() + "@example.com");
        userBToken = registerUser("b-" + UUID.randomUUID() + "@example.com");
    }

    @Test
    void rejectsUnknownInnOnPut() throws Exception {
        mockMvc.perform(put("/api/users/me/inns")
                        .header("Authorization", "Bearer " + userAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("inns", List.of("0000000000")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.unknownInns[0]").value("0000000000"));
    }

    @Test
    void acceptsCounterpartyInnPresentInOperations() throws Exception {
        // Seed an operation under known company that mentions counterparty INN
        push(OperationSource.ONE_C, List.of(sampleOperation("C001-CP-1")));
        String counterpartyInn = "0012345678";

        mockMvc.perform(put("/api/users/me/inns")
                        .header("Authorization", "Bearer " + userAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("inns", List.of(counterpartyInn)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inns[0]").value(counterpartyInn));

        MvcResult result = mockMvc.perform(get("/api/companies/" + counterpartyInn + "/operations")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(page.get("totalElements").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void forbidsAccessToUnlinkedInn() throws Exception {
        mockMvc.perform(get("/api/companies/" + KNOWN_INN + "/operations")
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void deduplicatesAcrossSourcesAndSharesBetweenUsers() throws Exception {
        linkInn(userAToken);
        linkInn(userBToken);

        OperationDto op = sampleOperation("C001-TEST-1");

        ImportResult firstPush = push(OperationSource.ONE_C, List.of(op));
        assertThat(firstPush.getInserted()).isEqualTo(1);

        ImportResult secondPush = push(OperationSource.GOS_SERVICE, List.of(op));
        assertThat(secondPush.getInserted()).isEqualTo(0);
        assertThat(secondPush.getDuplicates()).isEqualTo(1);

        String csv = """
                company_id;operation_id;operation_date;amount;direction;counterparty_inn;counterparty_name;payment_purpose;balance_eod
                C001;C001-TEST-1;2024-10-01;100.00;Поступление;0012345678;ООО «Тест»;Оплата;1000.00
                C001;C001-TEST-2;2024-10-02;50.00;Списание;0012345678;ООО «Тест»;Комиссия;950.00
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "C001.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );

        MvcResult csvResult = mockMvc.perform(multipart("/api/companies/" + KNOWN_INN + "/operations/csv")
                        .file(file)
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andReturn();
        ImportResult csvImport = objectMapper.readValue(csvResult.getResponse().getContentAsString(), ImportResult.class);
        assertThat(csvImport.getInserted()).isEqualTo(1);
        assertThat(csvImport.getDuplicates()).isEqualTo(1);

        JsonNode pageA = listOperations(userAToken);
        JsonNode pageB = listOperations(userBToken);
        assertThat(pageA.get("totalElements").asInt()).isEqualTo(2);
        assertThat(pageB.get("totalElements").asInt()).isEqualTo(2);
        assertThat(pageA.get("content").get(0).get("operationId").asText())
                .isEqualTo(pageB.get("content").get(0).get("operationId").asText());
    }

    @Test
    void pushWithoutKeyUnauthorized() throws Exception {
        mockMvc.perform(post("/api/integrations/operations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "source", "1C",
                                "inn", KNOWN_INN,
                                "operations", List.of(sampleOperation("C001-NO-KEY"))
                        ))))
                .andExpect(status().isUnauthorized());
    }

    private void linkInn(String token) throws Exception {
        mockMvc.perform(put("/api/users/me/inns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("inns", List.of(KNOWN_INN)))))
                .andExpect(status().isOk());
    }

    private ImportResult push(OperationSource source, List<OperationDto> operations) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/integrations/operations")
                        .header("X-Integration-Key", integrationApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "source", source.getCode(),
                                "inn", KNOWN_INN,
                                "operations", operations
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ImportResult.class);
    }

    private JsonNode listOperations(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/companies/" + KNOWN_INN + "/operations")
                        .header("Authorization", "Bearer " + token)
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String registerUser(String email) throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(email);
        request.setPassword("Password1!");
        request.setLastName("Test");
        request.setFirstName("User");
        request.setMiddleName("Middle");

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void uploadsMultiCompanyCsvOnlyForUserInns() throws Exception {
        linkInn(userAToken); // only C001 / 0098524470

        String csv = """
                company_id;operation_id;operation_date;amount;direction;counterparty_inn;counterparty_name;payment_purpose;balance_eod
                C001;C001-MULTI-1;2024-10-01;100.00;Поступление;0012345678;ООО «Тест»;Оплата;1000.00
                C002;C002-MULTI-1;2024-10-01;200.00;Поступление;0012345678;ООО «Тест»;Оплата;2000.00
                C001;C001-MULTI-2;2024-10-02;50.00;Списание;0012345678;ООО «Тест»;Комиссия;950.00
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "multi.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)
        );

        MvcResult result = mockMvc.perform(multipart("/api/operations/csv")
                        .file(file)
                        .header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk())
                .andReturn();
        ImportResult imported = objectMapper.readValue(result.getResponse().getContentAsString(), ImportResult.class);
        assertThat(imported.getInserted()).isEqualTo(2);
        assertThat(imported.getSkipped()).isEqualTo(1);

        JsonNode page = listOperations(userAToken);
        assertThat(page.get("totalElements").asInt()).isEqualTo(2);
    }

    private OperationDto sampleOperation(String operationId) {
        return new OperationDto(
                null,
                null,
                operationId,
                LocalDate.of(2024, 10, 1),
                new BigDecimal("100.00"),
                Direction.INCOME,
                "0012345678",
                "ООО «Тест»",
                "Оплата",
                new BigDecimal("1000.00")
        );
    }
}
