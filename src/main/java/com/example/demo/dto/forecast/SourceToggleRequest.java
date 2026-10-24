package com.example.demo.dto.forecast;

import jakarta.validation.constraints.NotNull;

public record SourceToggleRequest(@NotNull Boolean enabled) {
}
