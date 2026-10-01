package com.example.shop.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record Address(@NotBlank String street, @NotBlank String city, @Size(min = 5, max = 5) String zipCode,
                      String countryCode) {
}
