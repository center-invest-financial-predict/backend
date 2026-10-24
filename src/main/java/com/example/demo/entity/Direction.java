package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum Direction {
    INCOME("Поступление"),
    EXPENSE("Списание");

    private final String label;

    Direction(String label) {
        this.label = label;
    }

    @JsonValue
    public String getLabel() {
        return label;
    }

    @JsonCreator
    public static Direction from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Direction is required");
        }
        String normalized = value.trim();
        for (Direction direction : values()) {
            if (direction.name().equalsIgnoreCase(normalized)
                    || direction.label.equalsIgnoreCase(normalized)) {
                return direction;
            }
        }
        // Accept English aliases used in JSON APIs
        return switch (normalized.toUpperCase(Locale.ROOT)) {
            case "INCOME", "CREDIT" -> INCOME;
            case "EXPENSE", "DEBIT" -> EXPENSE;
            default -> throw new IllegalArgumentException("Unknown direction: " + value);
        };
    }
}
