package com.example.demo.exception;

/**
 * The ML Core server is disabled, unreachable, timed out or answered with an error.
 * {@code upstreamStatus} is 0 when there was no HTTP answer at all.
 */
public class MlCoreException extends RuntimeException {

    private final int upstreamStatus;
    private final String upstreamBody;

    public MlCoreException(String message, int upstreamStatus, String upstreamBody, Throwable cause) {
        super(message, cause);
        this.upstreamStatus = upstreamStatus;
        this.upstreamBody = upstreamBody;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }

    public String getUpstreamBody() {
        return upstreamBody;
    }

    /** The server understood us but refused the payload (validation, unreadable receipt, ...). */
    public boolean isRejected() {
        return upstreamStatus >= 400 && upstreamStatus < 500;
    }
}
