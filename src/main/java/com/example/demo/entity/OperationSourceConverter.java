package com.example.demo.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class OperationSourceConverter implements AttributeConverter<OperationSource, String> {

    @Override
    public String convertToDatabaseColumn(OperationSource attribute) {
        return attribute == null ? null : attribute.getCode();
    }

    @Override
    public OperationSource convertToEntityAttribute(String dbData) {
        return dbData == null ? null : OperationSource.from(dbData);
    }
}
