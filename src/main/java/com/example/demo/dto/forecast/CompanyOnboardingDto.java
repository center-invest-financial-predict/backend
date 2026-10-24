package com.example.demo.dto.forecast;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class CompanyOnboardingDto {

    private CompanyOnboardingDto() {
    }

    /**
     * New company that is not known to the system yet (no data from 1C / Gos_service, only the user's own file).
     * {@code sources}: where the data comes from, e.g. ["csv"]; omitted = all sources.
     */
    public record CreateRequest(
            @NotBlank @Size(min = 5, max = 20) String inn,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 50) String legalForm,
            @Size(max = 255) String industry,
            List<String> sources
    ) {
    }

    /** {@code syncStarted}: a background pull from the chosen external sources (1C / Gos_service) was started. */
    public record Response(CompanyDto company, DataSourcesResponse sources, boolean syncStarted) {
    }
}
