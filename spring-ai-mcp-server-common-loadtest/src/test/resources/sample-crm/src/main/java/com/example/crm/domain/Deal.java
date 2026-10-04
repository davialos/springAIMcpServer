package com.example.crm.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "deals")
public class Deal extends Auditable {
    @Column(nullable = false, length = 100)
    private String title;
    @Column(precision = 12, scale = 2)
    private BigDecimal amount;
    @ManyToOne
    @JoinColumn(name = "contact_id", nullable = false)
    private Contact contact;
    @ManyToOne
    @JoinColumn(name = "owner_user_id")
    private AppUser owner;
}
