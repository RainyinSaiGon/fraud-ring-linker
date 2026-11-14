package io.github.rainyinsaigon.fraudlinker.service.app;

/** The request contradicts existing state, e.g. a duplicate account. Mapped to HTTP 409. */
public class ConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
