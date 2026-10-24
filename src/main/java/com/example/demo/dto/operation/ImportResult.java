package com.example.demo.dto.operation;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ImportResult {

    private int inserted;
    private int duplicates;
    private int invalid;
    /** Rows skipped because INN is not linked to the current user (or cannot be resolved). */
    private int skipped;

    public static ImportResult empty() {
        return new ImportResult(0, 0, 0, 0);
    }

    public ImportResult merge(ImportResult other) {
        return new ImportResult(
                this.inserted + other.inserted,
                this.duplicates + other.duplicates,
                this.invalid + other.invalid,
                this.skipped + other.skipped
        );
    }
}
