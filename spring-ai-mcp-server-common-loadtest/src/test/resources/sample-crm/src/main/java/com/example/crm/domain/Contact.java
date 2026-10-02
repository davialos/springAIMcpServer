package com.example.crm.domain;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;

@Entity
@Table(name = "contacts")
public class Contact extends Auditable {
    @Column(name = "first_name", nullable = false, length = 40)
    private String firstName;
    @Column(length = 40)
    private String lastName;
    @Column(length = 80, unique = true)
    private String email;
    @ManyToOne(optional = false)
    @JoinColumn(name = "company_id")
    private Company company;
}
