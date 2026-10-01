package com.example.shop.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record CreateCustomerRequest(
        @NotBlank @Size(max = 40) String firstName,
        @NotBlank @Size(max = 40) String lastName,
        @NotNull @Email String email,
        @Schema(example = "+14155550100") String phone,
        @Past LocalDate birthDate,
        @NotBlank @Size(min = 8, max = 64) String password,
        @Valid Address address,
        @JsonProperty("marketing_opt_in") boolean marketingOptIn,
        @Size(max = 5) List<String> tags) {
}
