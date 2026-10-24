package com.example.demo.dto.forecast;

import jakarta.validation.constraints.Size;

public final class CompanyProfileDto {

    private CompanyProfileDto() {
    }

    /** Only the given fields change; an empty legalForm/industry clears the value, the name cannot be blank. */
    public record UpdateRequest(
            @Size(max = 255) String name,
            @Size(max = 50) String legalForm,
            @Size(max = 255) String industry
    ) {
    }

    /** A user who has the company linked. {@code current} marks the requesting user. */
    public record Member(Long id, String email, String fullName, String role, boolean current) {
    }
}
