package com.example.demo.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PageableSanitizer {

    private static final Set<String> ALLOWED = Set.of(
            "id",
            "operationId",
            "operationDate",
            "amount",
            "direction",
            "counterpartyInn",
            "counterpartyName",
            "balanceEod",
            "source",
            "createdAt"
    );

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "operationDate");

    private PageableSanitizer() {
    }

    public static Pageable sanitize(Pageable pageable) {
        int page = Math.max(pageable.getPageNumber(), 0);
        int size = pageable.getPageSize() <= 0 ? 50 : Math.min(pageable.getPageSize(), 500);

        if (pageable.getSort() == null || pageable.getSort().isUnsorted()) {
            return PageRequest.of(page, size, DEFAULT_SORT);
        }

        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            String property = normalizeProperty(order.getProperty());
            if (property != null && ALLOWED.contains(property)) {
                orders.add(new Sort.Order(order.getDirection(), property));
            }
        }

        Sort sort = orders.isEmpty() ? DEFAULT_SORT : Sort.by(orders);
        return PageRequest.of(page, size, sort);
    }

    private static String normalizeProperty(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String property = raw.trim();
        // Swagger/OpenAPI often sends example values like ["string"] or "string"
        if (property.startsWith("[") && property.endsWith("]")) {
            property = property.substring(1, property.length() - 1).trim();
            if (property.startsWith("\"") && property.endsWith("\"") && property.length() >= 2) {
                property = property.substring(1, property.length() - 1);
            }
        }
        if ("string".equalsIgnoreCase(property)) {
            return null;
        }
        // allow snake_case from clients
        if (property.contains("_")) {
            property = toCamelCase(property);
        }
        return property;
    }

    private static String toCamelCase(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
