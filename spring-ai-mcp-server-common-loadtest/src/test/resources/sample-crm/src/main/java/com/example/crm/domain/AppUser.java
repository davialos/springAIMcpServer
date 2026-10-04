package com.example.crm.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class AppUser extends Auditable {
    @Column(nullable = false, length = 30, unique = true)
    private String username;
    private String passwordHash;
}
