package io.github.rainyinsaigon.fraudlinker.service.engine;

/** The linking state could not be loaded from the database. Mapped to HTTP 503. */
public class EngineUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EngineUnavailableException(Throwable cause) {
        super("Linking state is unavailable", cause);
    }
}
