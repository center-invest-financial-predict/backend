package com.example.demo.exception;

public class InsufficientHistoryException extends RuntimeException {

    private final int requiredDays;
    private final int actualDays;

    public InsufficientHistoryException(String model, int requiredDays, int actualDays) {
        super("Model '" + model + "' requires at least " + requiredDays
                + " days of history, got " + actualDays);
        this.requiredDays = requiredDays;
        this.actualDays = actualDays;
    }

    public InsufficientHistoryException(String message) {
        super(message);
        this.requiredDays = 0;
        this.actualDays = 0;
    }

    public int getRequiredDays() {
        return requiredDays;
    }

    public int getActualDays() {
        return actualDays;
    }
}
