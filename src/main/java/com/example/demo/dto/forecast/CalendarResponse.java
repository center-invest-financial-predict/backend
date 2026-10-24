package com.example.demo.dto.forecast;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

public record CalendarResponse(
        YearMonth month,
        Map<String, List<CashEventDto>> days
) {
}
