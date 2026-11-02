package com.example.assistant.web;

/** A user-facing error: the status code and message are returned to the client as {"detail": message}. */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}
