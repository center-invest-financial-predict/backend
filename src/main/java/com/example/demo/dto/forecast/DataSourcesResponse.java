package com.example.demo.dto.forecast;

import java.time.LocalDate;
import java.util.List;

public record DataSourcesResponse(
        String companyInn,
        List<SourceStatus> sources
) {
    public record SourceStatus(
            String code,
            String name,
            boolean connected,
            LocalDate lastDataDate,
            long operationCount
    ) {
    }
}
