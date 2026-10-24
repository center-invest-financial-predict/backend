package com.example.demo.exception;

import java.util.Collection;
import java.util.List;

public class UnknownInnException extends RuntimeException {

    private final List<String> unknownInns;

    public UnknownInnException(Collection<String> unknownInns) {
        super("Unknown INN(s): " + String.join(", ", unknownInns));
        this.unknownInns = List.copyOf(unknownInns);
    }

    public List<String> getUnknownInns() {
        return unknownInns;
    }
}
