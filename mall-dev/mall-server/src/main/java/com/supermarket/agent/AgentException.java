package com.supermarket.agent;

/** Errors on the service boundary must keep an actual HTTP status. */
public final class AgentException extends RuntimeException {
    private final int status;

    public AgentException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}
