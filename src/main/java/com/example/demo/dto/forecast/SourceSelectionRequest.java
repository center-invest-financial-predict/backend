package com.example.demo.dto.forecast;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** {@code sources}: codes as in GET /sources, e.g. ["1C", "csv"]. */
public record SourceSelectionRequest(@NotEmpty List<String> sources) {
}
