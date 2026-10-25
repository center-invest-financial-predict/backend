package com.example.demo.dto.operation;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ImportResult {

    private int inserted;
    private int duplicates;
    private int invalid;
    /** Rows skipped because INN is not linked to the current user (or cannot be resolved). */
    private int skipped;
    /** Human-readable reasons for invalid / skipped rows (a few examples), empty when everything went fine. */
    private List<String> errors = new ArrayList<>();

    public ImportResult(int inserted, int duplicates, int invalid, int skipped) {
        this.inserted = inserted;
        this.duplicates = duplicates;
        this.invalid = invalid;
        this.skipped = skipped;
    }

    public ImportResult(int inserted, int duplicates, int invalid, int skipped, List<String> errors) {
        this(inserted, duplicates, invalid, skipped);
        this.errors = errors == null ? new ArrayList<>() : new ArrayList<>(errors);
    }

    public static ImportResult empty() {
        return new ImportResult(0, 0, 0, 0);
    }

    public ImportResult merge(ImportResult other) {
        List<String> merged = new ArrayList<>(this.errors);
        merged.addAll(other.errors);
        return new ImportResult(
                this.inserted + other.inserted,
                this.duplicates + other.duplicates,
                this.invalid + other.invalid,
                this.skipped + other.skipped,
                merged
        );
    }
}
