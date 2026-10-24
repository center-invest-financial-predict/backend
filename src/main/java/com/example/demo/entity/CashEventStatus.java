package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum CashEventStatus {
    EXPECTED,
    OVERDUE,
    DONE;

    @JsonValue
    public String json() {
        return name();
    }

    @JsonCreator
    public static CashEventStatus from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Status is required");
        }
        return CashEventStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
