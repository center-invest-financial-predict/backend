package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "companies")
@Getter
@Setter
@NoArgsConstructor
public class Company {

    @Id
    @Column(name = "inn", length = 20, nullable = false)
    private String inn;

    @Column(name = "company_code", length = 10, nullable = false, unique = true)
    private String companyCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "legal_form", length = 50)
    private String legalForm;

    @Column(name = "industry")
    private String industry;

    public Company(String inn, String companyCode, String name, String legalForm, String industry) {
        this.inn = inn;
        this.companyCode = companyCode;
        this.name = name;
        this.legalForm = legalForm;
        this.industry = industry;
    }
}
