package com.example.crm.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateDealRequest(@NotBlank String title, @NotNull @Positive BigDecimal amount, @NotNull Long contactId,
                                Long ownerId) {
}
