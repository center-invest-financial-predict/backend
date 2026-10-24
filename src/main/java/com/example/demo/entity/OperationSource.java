package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum OperationSource {
    CSV("csv"),
    ONE_C("1C"),
    GOS_SERVICE("Gos_service");

    private final String code;

    OperationSource(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static OperationSource from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Source is required");
        }
        String normalized = value.trim();
        for (OperationSource source : values()) {
            if (source.code.equalsIgnoreCase(normalized) || source.name().equalsIgnoreCase(normalized)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown source: " + value);
    }

    public boolean isIntegration() {
        return this == ONE_C || this == GOS_SERVICE;
    }
}
