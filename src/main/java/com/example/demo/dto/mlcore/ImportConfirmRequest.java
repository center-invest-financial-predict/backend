package com.example.demo.dto.mlcore;

import com.example.demo.dto.operation.OperationDto;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Rows reviewed by the user (as returned by the import preview, possibly edited).
 * {@code operation_id} may be empty - the backend derives a stable one from the row content.
 */
public record ImportConfirmRequest(
        @NotEmpty @Size(max = 1000) List<OperationDto> operations
) {
}
