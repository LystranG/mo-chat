package com.github.lystran.mochat.common.session;

public final class SessionRouteWriteException extends RuntimeException {
    private final boolean outcomeUnknown;

    private SessionRouteWriteException(String message, Throwable cause, boolean outcomeUnknown) {
        super(message, cause);
        this.outcomeUnknown = outcomeUnknown;
    }

    public static SessionRouteWriteException definitiveFailure(String message, Throwable cause) {
        return new SessionRouteWriteException(message, cause, false);
    }

    public static SessionRouteWriteException definitiveFailure(String message) {
        return definitiveFailure(message, null);
    }

    public static SessionRouteWriteException outcomeUnknown(String message, Throwable cause) {
        return new SessionRouteWriteException(message, cause, true);
    }

    public static SessionRouteWriteException outcomeUnknown(String message) {
        return outcomeUnknown(message, null);
    }

    public boolean outcomeUnknown() {
        return outcomeUnknown;
    }
}
