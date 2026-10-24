package com.example.demo.dto.mlcore;

import java.util.List;
import java.util.Map;

/**
 * Normalized statement rows proposed by ML Core. Nothing is saved: the client reviews the rows and sends
 * them to {@code POST /api/companies/{inn}/operations/import/confirm}.
 *
 * @param rows          rows in operation format (operation_date, amount, direction, counterparty_*, payment_purpose ...)
 * @param columnMapping operation field -> source column found in the file (null if not found); empty for receipts
 */
public record ImportPreviewResponse(
        String source,
        String companyInn,
        List<Row> rows,
        Map<String, String> columnMapping,
        List<String> warnings
) {

    public record Row(
            Integer rowNumber,
            Map<String, Object> operation,
            List<String> missingFields,
            List<String> warnings,
            boolean readyForReview
    ) {
    }
}
