package com.example.crm.domain;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;

import java.util.List;

@Entity
@Table(name = "companies")
public class Company extends Auditable {
    @Column(nullable = false, length = 60)
    private String name;
    @Column(name = "registration_no", unique = true, length = 20)
    private String registrationNo;
    private String website;
    @OneToMany(mappedBy = "company")
    @JsonManagedReference
    private List<Contact> contacts;
}
