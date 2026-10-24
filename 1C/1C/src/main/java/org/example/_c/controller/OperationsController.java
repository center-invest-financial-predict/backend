package org.example._c.controller;

import org.example._c.dto.OperationDto;
import org.example._c.service.BackendPushClient;
import org.example._c.service.OperationDataService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OperationsController {

    private final OperationDataService operationDataService;
    private final BackendPushClient backendPushClient;

    public OperationsController(OperationDataService operationDataService, BackendPushClient backendPushClient) {
        this.operationDataService = operationDataService;
        this.backendPushClient = backendPushClient;
    }

    @GetMapping("/operations")
    public List<OperationDto> getOperations(@RequestParam String inn) {
        return operationDataService.findByInn(inn);
    }

    @PostMapping("/push")
    public ResponseEntity<Map<?, ?>> push(
            @RequestParam String inn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate
    ) {
        List<OperationDto> operations = operationDataService.findByInnAfter(inn, fromDate);
        Map<?, ?> result = backendPushClient.push(inn, operations);
        return ResponseEntity.ok(result);
    }
}
