package com.grid07.assignment.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "bots")
public class Bot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "persona_description", columnDefinition = "TEXT")
    private String personaDescription;

    public Bot() {}

    public Bot(Long id, String name, String personaDescription) {
        this.id = id;
        this.name = name;
        this.personaDescription = personaDescription;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPersonaDescription() { return personaDescription; }
    public void setPersonaDescription(String personaDescription) { this.personaDescription = personaDescription; }
}
