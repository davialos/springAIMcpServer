package com.example.shop.web;

import com.example.shop.domain.OrderStatus;

public record OrderFilter(OrderStatus status, Long customerId) {
}
