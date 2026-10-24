package com.example.demo.controller;

import com.example.demo.client.MlServiceClient;
import com.example.demo.dto.ml.InferenceRequest;
import com.example.demo.dto.ml.InferenceResponse;
import com.example.demo.dto.ml.MlForecastResultResponse;
import com.example.demo.service.CompanyAccessService;
import com.example.demo.service.MlForecastService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ml")
public class MlController {

    private final MlServiceClient mlServiceClient;
    private final RestTemplate restTemplate;
    private final String mlServiceUrl;

    private final MlForecastService mlForecastService;
    private final CompanyAccessService companyAccessService;

    public MlController(MlServiceClient mlServiceClient,
                        RestTemplate restTemplate,
                        @Value("${ml.service.url}") String mlServiceUrl,
                        MlForecastService mlForecastService,
                        CompanyAccessService companyAccessService) {
        this.mlServiceClient = mlServiceClient;
        this.restTemplate = restTemplate;
        this.mlServiceUrl = mlServiceUrl;
        this.mlForecastService = mlForecastService;
        this.companyAccessService = companyAccessService;
    }

    @GetMapping("/companies/{inn}/forecast")
    @Operation(summary = "ML cash-flow forecast and cash gaps for a company",
            description = "Aggregates daily history from operations and calls ml-service POST /forecast. "
                    + "gradient_boosting needs >= 90 days of history, dynamical >= 30.")
    public ResponseEntity<MlForecastResultResponse> forecast(
            @PathVariable String inn,
            @RequestParam(defaultValue = "30") int horizonDays,
            @RequestParam(defaultValue = MlForecastService.MODEL_GRADIENT_BOOSTING) String model,
            Authentication authentication) {
        companyAccessService.ensureOwnedByUser(authentication.getName(), inn);
        return ResponseEntity.ok(mlForecastService.forecast(inn, horizonDays, model));
    }

    @PostMapping("/inference")
    public ResponseEntity<InferenceResponse> inference(@Valid @RequestBody InferenceRequest request) {
        InferenceResponse response = mlServiceClient.inference(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/inference/simple")
    public ResponseEntity<InferenceResponse> simpleInference(
            @RequestParam @NotEmpty @Size(min = 1, max = 100) List<Double> features,
            @RequestParam(defaultValue = "default_nn") String modelName) {
        InferenceRequest request = InferenceRequest.of(features, modelName);
        InferenceResponse response = mlServiceClient.inference(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/inference/array")
    public ResponseEntity<InferenceResponse> arrayInference(
            @RequestBody double[] features,
            @RequestParam(defaultValue = "default_nn") String modelName) {
        InferenceResponse response = mlServiceClient.inference(features, modelName);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/models")
    public ResponseEntity<?> listModels() {
        try {
            var response = restTemplate.getForEntity(mlServiceUrl + "/models", Object.class);
            return ResponseEntity.ok(response.getBody());
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", "ML service unavailable", "message", e.getMessage()));
        }
    }

    @GetMapping("/health")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, String>> mlHealth() {
        try {
            var response = restTemplate.getForEntity(mlServiceUrl + "/health", Map.class);
            return ResponseEntity.ok(response.getBody());
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("status", "DOWN", "error", e.getMessage()));
        }
    }
}