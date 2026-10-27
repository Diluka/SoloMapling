package org.gms.soloacceptance;

import org.springframework.http.HttpStatus;

/** Fixed machine-readable reasons; request contents and internal exception messages stay private. */
final class ObservationException extends RuntimeException {
    private final HttpStatus status;
    private final String reason;

    ObservationException(HttpStatus status, String reason) {
        super(reason);
        this.status = status;
        this.reason = reason;
    }

    HttpStatus status() { return status; }
    String reason() { return reason; }
}
