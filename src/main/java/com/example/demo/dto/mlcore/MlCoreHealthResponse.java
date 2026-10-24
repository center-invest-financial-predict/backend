package com.example.demo.dto.mlcore;

/**
 * Availability of the ML Core server. {@code status}: UP, DEGRADED (alive but not ready),
 * DOWN (unreachable / error) or DISABLED (ml.core.enabled=false). Never an error response.
 */
public record MlCoreHealthResponse(
        boolean enabled,
        String url,
        String status,
        boolean live,
        boolean ready,
        String llmMode,
        String message
) {
}
