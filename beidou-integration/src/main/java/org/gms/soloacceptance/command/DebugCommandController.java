package org.gms.soloacceptance.command;

import org.gms.model.dto.ResultBody;
import org.gms.model.dto.SubmitBody;
import org.gms.soloacceptance.AcceptanceAccess;
import org.gms.soloacceptance.ObservationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** A management surface independent of the role operation transport. */
@RestController
@RequestMapping("/solo/acceptance/v1/command")
@ConditionalOnProperty(prefix = "solomapling.acceptance",
        name = {"enabled", "actors-enabled", "debug-commands-enabled"}, havingValue = "true", matchIfMissing = false)
public final class DebugCommandController {
    private final DebugCommandService commands;

    public DebugCommandController(DebugCommandService commands) {
        this.commands = commands;
    }

    @PostMapping("/execute")
    public ResultBody<Map<String, Object>> execute(@RequestBody(required = false) DebugCommandRequest.Submission request) {
        String owner = AcceptanceAccess.requireAdmin();
        if (request == null || request.data() == null) {
            throw new ObservationException(HttpStatus.BAD_REQUEST, "malformed_request");
        }
        return ResultBody.success(new SubmitBody<>(request.requestId(), request.data()),
                commands.execute(owner, request.requestId(), request.data()));
    }

    @ExceptionHandler(ObservationException.class)
    public ResponseEntity<ResultBody<Map<String, String>>> commandFailure(ObservationException failure) {
        return failure(failure.status(), failure.reason());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ResultBody<Map<String, String>>> malformedRequest(Exception ignored) {
        return failure(HttpStatus.BAD_REQUEST, "malformed_request");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ResultBody<Map<String, String>>> unexpectedFailure(RuntimeException ignored) {
        return failure(HttpStatus.INTERNAL_SERVER_ERROR, "debug_command_error");
    }

    private static ResponseEntity<ResultBody<Map<String, String>>> failure(HttpStatus status, String reason) {
        ResultBody<Map<String, String>> body = new ResultBody<>();
        body.setCode(status.value());
        body.setMessage(reason);
        body.setResponseId(UUID.randomUUID().toString());
        body.setData(Map.of("reason", reason));
        return ResponseEntity.status(status).body(body);
    }
}
