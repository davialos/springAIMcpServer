package com.example.shop.web;

import com.example.shop.domain.OrderStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record CreateOrderRequest(
        @NotNull Long customerId,
        @NotEmpty List<OrderLine> lines,
        @Pattern(regexp = "^[A-Z]{3}-\\d{4}$") String couponCode,
        OrderStatus status,
        String deliveryNotes) {
}
