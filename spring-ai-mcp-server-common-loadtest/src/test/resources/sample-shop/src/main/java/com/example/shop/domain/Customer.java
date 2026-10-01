package com.example.shop.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "customers")
public class Customer extends BaseEntity {
    @Column(name = "first_name")
    private String firstName;
    private String lastName;
    @Column(unique = true)
    private String email;
    private String passwordHash;
    private String city;
}
