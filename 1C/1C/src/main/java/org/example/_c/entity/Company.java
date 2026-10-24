package org.example._c.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "companies")
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

    public Company() {
    }

    public Company(String inn, String companyCode, String name, String legalForm, String industry) {
        this.inn = inn;
        this.companyCode = companyCode;
        this.name = name;
        this.legalForm = legalForm;
        this.industry = industry;
    }

    public String getInn() {
        return inn;
    }

    public void setInn(String inn) {
        this.inn = inn;
    }

    public String getCompanyCode() {
        return companyCode;
    }

    public void setCompanyCode(String companyCode) {
        this.companyCode = companyCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLegalForm() {
        return legalForm;
    }

    public void setLegalForm(String legalForm) {
        this.legalForm = legalForm;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }
}
