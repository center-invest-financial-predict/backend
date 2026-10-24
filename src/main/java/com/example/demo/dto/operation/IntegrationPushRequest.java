package com.example.demo.dto.operation;

import com.example.demo.entity.OperationSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
public class IntegrationPushRequest {

    @NotNull
    private OperationSource source;

    @NotBlank
    @Size(min = 5, max = 20, message = "INN must be 5 to 20 characters")
    private String inn;

    @NotEmpty
    private List<@Valid OperationDto> operations;
}
