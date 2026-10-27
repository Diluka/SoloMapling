package org.gms.soloacceptance;

import org.gms.model.dto.ResultBody;
import org.gms.model.dto.SubmitBody;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/solo/acceptance/v1")
@ConditionalOnProperty(prefix = "solomapling.acceptance", name = "enabled", havingValue = "true", matchIfMissing = false)
public final class AcceptanceObservationController {
    private final AcceptanceObservationService observations;

    public AcceptanceObservationController(AcceptanceObservationService observations) {
        this.observations = observations;
    }

    @GetMapping("/capabilities")
    public ResultBody<Map<String, Object>> capabilities() {
        requireAdmin();
        return ResultBody.success(observations.capabilities());
    }

    @PostMapping("/observe")
    public ResultBody<Map<String, Object>> observe(@RequestBody SubmitBody<ObservationRequest> request) {
        requireAdmin();
        return ResultBody.success(request, observations.observe(request == null ? null : request.getData()));
    }

    // The native global advice converts RuntimeException to HTTP 200. Keep this API's status
    // contract local, with fixed reasons and no request bodies or exception internals.
    @ExceptionHandler(ObservationException.class)
    public ResponseEntity<ResultBody<Map<String, String>>> observationFailure(ObservationException failure) {
        return errorResponse(failure.status(), failure.reason());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ResultBody<Map<String, String>>> malformedRequest(HttpMessageNotReadableException ignored) {
        return errorResponse(HttpStatus.BAD_REQUEST, "malformed_request");
    }

    private static ResponseEntity<ResultBody<Map<String, String>>> errorResponse(HttpStatus status, String reason) {
        ResultBody<Map<String, String>> body = new ResultBody<>();
        body.setCode(status.value());
        body.setMessage(reason);
        body.setResponseId(UUID.randomUUID().toString());
        body.setData(Map.of("reason", reason));
        return ResponseEntity.status(status).body(body);
    }

    private static void requireAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ObservationException(HttpStatus.UNAUTHORIZED, "authentication_required");
        }
        if (authentication.getAuthorities().stream().noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
            throw new ObservationException(HttpStatus.FORBIDDEN, "administrator_required");
        }
    }
}
