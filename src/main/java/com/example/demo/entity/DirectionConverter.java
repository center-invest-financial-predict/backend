package com.example.demo.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DirectionConverter implements AttributeConverter<Direction, String> {

    @Override
    public String convertToDatabaseColumn(Direction attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public Direction convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Direction.from(dbData);
    }
}
