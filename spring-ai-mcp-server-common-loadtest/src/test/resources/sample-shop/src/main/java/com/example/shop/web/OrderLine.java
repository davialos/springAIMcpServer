package com.example.shop.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public class OrderLine {
    @NotBlank
    private String productSku;
    @Min(1)
    @Max(99)
    private int quantity;
    private static final long serialVersionUID = 1L;
}
