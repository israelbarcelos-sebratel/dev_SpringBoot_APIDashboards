package com.sebratel.dashboards.common.auth;

/** Rejected /ext request; {@link #status} is the HTTP status returned to the extension. */
public class AuthException extends RuntimeException {

    private final int status;

    public AuthException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
