package com.example.demo.dto.auth;

import com.example.demo.dto.user.InnsRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void acceptsValidRegistrationWithoutMiddleName() {
        RegisterRequest request = new RegisterRequest("Иванов", "Иван", null, "user@example.com", "password123");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsBlankNamesInvalidEmailAndShortPassword() {
        RegisterRequest request = new RegisterRequest("", " ", null, "bad", "123");

        Set<String> invalidFields = validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(invalidFields).containsExactlyInAnyOrder("lastName", "firstName", "email", "password");
    }

    @Test
    void acceptsInnsFrom5To20Characters() {
        InnsRequest request = new InnsRequest(List.of("12345", "0098524470", "500100732259", "A".repeat(20)));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsEmptyInnList() {
        assertThat(validator.validate(new InnsRequest(List.of()))).hasSize(1);
        assertThat(validator.validate(new InnsRequest(null))).hasSize(1);
    }

    @Test
    void rejectsInnOutsideLengthBounds() {
        assertThat(validator.validate(new InnsRequest(List.of("1234", "A".repeat(21))))).hasSize(2);
    }
}
