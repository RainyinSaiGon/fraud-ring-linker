package io.github.rainyinsaigon.fraudlinker;

/** Any input or invariant error that should stop the linker with a clear message. */
public class LinkerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LinkerException(String message) {
        super(message);
    }

    public LinkerException(String message, Throwable cause) {
        super(message, cause);
    }
}
