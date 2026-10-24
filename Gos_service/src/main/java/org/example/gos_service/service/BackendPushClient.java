package org.example.gos_service.service;

import org.example.gos_service.dto.OperationDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class BackendPushClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String source;

    public BackendPushClient(
            @Value("${backend.url}") String backendUrl,
            @Value("${backend.api-key}") String apiKey,
            @Value("${integration.source}") String source
    ) {
        String base = backendUrl.endsWith("/") ? backendUrl.substring(0, backendUrl.length() - 1) : backendUrl;
        this.restClient = RestClient.builder().baseUrl(base).build();
        this.apiKey = apiKey;
        this.source = source;
    }

    public Map<?, ?> push(String inn, List<OperationDto> operations) {
        return restClient.post()
                .uri("/api/integrations/operations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Integration-Key", apiKey)
                .body(Map.of(
                        "source", source,
                        "inn", inn,
                        "operations", operations
                ))
                .retrieve()
                .body(Map.class);
    }
}
