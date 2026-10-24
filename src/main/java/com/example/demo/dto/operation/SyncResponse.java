package com.example.demo.dto.operation;

import java.util.List;

/**
 * Result of pulling data from the external services. The totals have the same names as {@link ImportResult};
 * {@code sources} tells what happened with each pull source (so "nothing new" is not confused with "not answering").
 */
public record SyncResponse(
        int inserted,
        int duplicates,
        int invalid,
        int skipped,
        List<SourceResult> sources
) {
    public static final String SYNCED = "synced";
    /** The user has switched the source off: it was not asked. */
    public static final String DISABLED = "disabled";
    public static final String UNAVAILABLE = "unavailable";
    public static final String NOT_CONFIGURED = "not_configured";

    public record SourceResult(String code, String status, int inserted, int duplicates, int invalid, String message) {
    }
}
