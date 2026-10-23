package com.example.demo.controller;

import com.example.demo.client.MlServiceClient;
import com.example.demo.dto.ml.InferenceRequest;
import com.example.demo.dto.ml.InferenceResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
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

    public MlController(MlServiceClient mlServiceClient,
                        RestTemplate restTemplate,
                        @Value("${ml.service.url}") String mlServiceUrl) {
        this.mlServiceClient = mlServiceClient;
        this.restTemplate = restTemplate;
        this.mlServiceUrl = mlServiceUrl;
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