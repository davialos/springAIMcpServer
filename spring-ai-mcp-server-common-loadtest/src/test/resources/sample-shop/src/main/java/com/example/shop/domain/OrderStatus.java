package com.example.shop.domain;

public enum OrderStatus {
    NEW, PAID, SHIPPED, CANCELLED;

    public boolean isOpen() {
        return this == NEW || this == PAID;
    }
}
