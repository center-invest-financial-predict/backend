package com.example.demo.controller;

import com.example.demo.dto.operation.ImportResult;
import com.example.demo.dto.operation.IntegrationPushRequest;
import com.example.demo.forecast.ForecastService;
import com.example.demo.service.OperationImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final OperationImportService operationImportService;
    private final ForecastService forecastService;

    @PostMapping("/operations")
    public ResponseEntity<ImportResult> pushOperations(@Valid @RequestBody IntegrationPushRequest request) {
        if (request.getSource() == null || !request.getSource().isIntegration()) {
            throw new IllegalArgumentException("Source must be 1C or Gos_service");
        }
        ImportResult result = operationImportService.upsertBatch(
                request.getInn(),
                request.getSource(),
                request.getOperations()
        );
        if (result.getInserted() > 0) {
            forecastService.onDataChanged(request.getInn(),
                    "Получены операции от " + request.getSource().getCode() + ": +" + result.getInserted());
        }
        return ResponseEntity.ok(result);
    }
}
