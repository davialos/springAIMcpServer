package com.example.crm.domain;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
public class Task {
    @Id
    @GeneratedValue
    private Long id;
    @Column(nullable = false, length = 120)
    private String summary;
    private LocalDate dueDate;
    @ManyToOne
    private Deal deal;
}
