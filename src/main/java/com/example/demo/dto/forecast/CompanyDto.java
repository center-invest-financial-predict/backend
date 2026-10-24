package com.example.demo.dto.forecast;

import com.example.demo.entity.Company;

public record CompanyDto(
        String inn,
        String companyCode,
        String name,
        String legalForm,
        String industry
) {
    public static CompanyDto from(Company c) {
        return new CompanyDto(c.getInn(), c.getCompanyCode(), c.getName(), c.getLegalForm(), c.getIndustry());
    }
}
