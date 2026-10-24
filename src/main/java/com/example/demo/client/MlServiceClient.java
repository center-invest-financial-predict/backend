package com.example.demo.client;

import com.example.demo.dto.ml.InferenceRequest;
import com.example.demo.dto.ml.InferenceResponse;
import com.example.demo.dto.ml.MlForecastRequest;
import com.example.demo.dto.ml.MlForecastResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Component
public class MlServiceClient {

    private static final Logger log = LoggerFactory.getLogger(MlServiceClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public MlServiceClient(RestTemplate restTemplate,
                           @Value("${ml.service.url}") String baseUrl,
                           @Value("${ml.service.timeout}") int timeout,
                           @Value("${ml.service.connect-timeout}") int connectTimeout) {
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
        // Note: RestTemplate timeouts are set via factory, but for simplicity we use defaults here
    }

    public InferenceResponse inference(InferenceRequest request) {
        String url = baseUrl + "/inference";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<InferenceRequest> entity = new HttpEntity<>(request, headers);

        try {
            log.debug("Calling ML service at {} with request: {}", url, request);
            ResponseEntity<InferenceResponse> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, InferenceResponse.class);
            log.debug("ML service response: {}", response.getBody());
            return response.getBody();
        } catch (RestClientException e) {
            log.error("ML service call failed: {}", e.getMessage());
            throw new MlServiceException("Failed to call ML service", e);
        }
    }

    /**
     * Cash-flow forecast: {@code POST {ml.service.url}/forecast}.
     * Contract: docs/ml-forecast-openapi.yaml.
     */
    public MlForecastResponse forecast(MlForecastRequest request) {
        String url = baseUrl + "/forecast";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<MlForecastRequest> entity = new HttpEntity<>(request, headers);

        try {
            log.debug("Calling ML forecast at {} (inn={}, model={}, horizon={}, historyDays={})",
                    url, request.companyInn(), request.model(), request.horizonDays(),
                    request.history() == null ? 0 : request.history().size());
            ResponseEntity<MlForecastResponse> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, MlForecastResponse.class);
            MlForecastResponse body = response.getBody();
            if (body == null || body.points() == null) {
                throw new MlServiceException("ML service returned an empty forecast", null);
            }
            return body;
        } catch (RestClientException e) {
            log.error("ML forecast call failed: {}", e.getMessage());
            throw new MlServiceException("Failed to call ML service", e);
        }
    }

    public InferenceResponse inference(double[] features, String modelName) {
        InferenceRequest request = new InferenceRequest();
        request.setFeatures(java.util.Arrays.stream(features).boxed().toList());
        request.setModelName(modelName);
        return inference(request);
    }

    public static class MlServiceException extends RuntimeException {
        public MlServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}