package com.example.demo.exception;

public class CompanyAlreadyExistsException extends RuntimeException {
    public CompanyAlreadyExistsException(String inn) {
        super("Компания с ИНН " + inn + " уже есть в системе: привяжите её через PUT /api/users/me/inns");
    }
}
