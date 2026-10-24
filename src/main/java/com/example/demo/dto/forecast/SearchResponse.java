package com.example.demo.dto.forecast;

import com.example.demo.dto.operation.OperationResponse;

import java.util.List;

public record SearchResponse(
        String query,
        List<OperationResponse> operations,
        List<CashEventDto> events
) {
}
