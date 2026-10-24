package com.example.demo.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

class PageableSanitizerTest {

    @Test
    void replacesSwaggerExampleSortWithDefault() {
        Pageable dirty = PageRequest.of(0, 1, Sort.by("[\"string\"]"));
        Pageable clean = PageableSanitizer.sanitize(dirty);

        assertThat(clean.getPageNumber()).isZero();
        assertThat(clean.getPageSize()).isEqualTo(1);
        assertThat(clean.getSort().getOrderFor("operationDate")).isNotNull();
        assertThat(clean.getSort().getOrderFor("operationDate").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void keepsValidSort() {
        Pageable pageable = PageRequest.of(1, 10, Sort.by(Sort.Direction.ASC, "amount"));
        Pageable clean = PageableSanitizer.sanitize(pageable);

        assertThat(clean.getPageNumber()).isEqualTo(1);
        assertThat(clean.getSort().getOrderFor("amount").getDirection()).isEqualTo(Sort.Direction.ASC);
    }
}
