package com.github.lystran.mochat.common.session;

public final class SessionResolutionException extends RuntimeException {
    public SessionResolutionException(String message) {
        super(message);
    }

    public SessionResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
