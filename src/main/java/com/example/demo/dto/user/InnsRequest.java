package com.example.demo.dto.user;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InnsRequest {

    @NotEmpty(message = "At least one INN is required")
    private List<@Size(min = 5, max = 20, message = "INN must be 5 to 20 characters") String> inns;
}
