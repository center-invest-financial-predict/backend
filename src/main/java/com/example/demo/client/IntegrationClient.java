package com.example.demo.client;

import com.example.demo.dto.operation.OperationDto;
import com.example.demo.entity.OperationSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.List;

@Component
@Slf4j
public class IntegrationClient {

    private final RestClient.Builder restClientBuilder;
    private final String onecUrl;
    private final String gosUrl;

    public IntegrationClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.integrations.onec.url:}") String onecUrl,
            @Value("${app.integrations.gos.url:}") String gosUrl
    ) {
        this.restClientBuilder = restClientBuilder;
        this.onecUrl = trimTrailingSlash(onecUrl);
        this.gosUrl = trimTrailingSlash(gosUrl);
    }

    public List<OperationDto> fetchOperations(OperationSource source, String inn) {
        String baseUrl = switch (source) {
            case ONE_C -> onecUrl;
            case GOS_SERVICE -> gosUrl;
            default -> throw new IllegalArgumentException("Unsupported pull source: " + source);
        };
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("Integration URL for {} is not configured, skipping pull for INN {}", source, inn);
            return Collections.emptyList();
        }

        try {
            List<OperationDto> body = restClientBuilder.build()
                    .get()
                    .uri(baseUrl + "/api/operations?inn={inn}", inn)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return body != null ? body : Collections.emptyList();
        } catch (RestClientException ex) {
            log.warn("Failed to pull operations from {} for INN {}: {}", source, inn, ex.getMessage());
            return Collections.emptyList();
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
