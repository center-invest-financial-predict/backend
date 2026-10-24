package com.example.demo.exception;

public class ForbiddenInnException extends RuntimeException {

    public ForbiddenInnException(String inn) {
        super("INN is not linked to the current user: " + inn);
    }
}
