package org.gms.soloacceptance;

import org.springframework.http.HttpStatus;

/** Fixed machine-readable reasons; request contents and internal exception messages stay private. */
public final class ObservationException extends RuntimeException {
    private final HttpStatus status;
    private final String reason;

    public ObservationException(HttpStatus status, String reason) {
        super(reason);
        this.status = status;
        this.reason = reason;
    }

    public HttpStatus status() { return status; }
    public String reason() { return reason; }
}
