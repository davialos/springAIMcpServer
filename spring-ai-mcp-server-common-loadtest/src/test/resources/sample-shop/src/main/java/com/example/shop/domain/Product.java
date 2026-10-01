package com.example.shop.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Product {
    @Id
    private String sku;
    private String name;
    private java.math.BigDecimal price;
}
