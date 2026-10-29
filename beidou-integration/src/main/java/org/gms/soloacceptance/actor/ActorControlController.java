package org.gms.soloacceptance.actor;

import org.gms.model.dto.ResultBody;
import org.gms.model.dto.SubmitBody;
import org.gms.soloacceptance.AcceptanceAccess;
import org.gms.soloacceptance.ObservationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;
import java.util.UUID;

/** Administrator-facing transport only; the service owns sessions and operation outcomes. */
@RestController
@RequestMapping("/solo/acceptance/v1")
@ConditionalOnProperty(prefix = "solomapling.acceptance", name = {"enabled", "actors-enabled"},
        havingValue = "true", matchIfMissing = false)
public final class ActorControlController {
    private final ActorControlService actors;
    private final RequestAttributeSecurityContextRepository streamSecurityContext = new RequestAttributeSecurityContextRepository();

    public ActorControlController(ActorControlService actors) {
        this.actors = actors;
    }

    @PostMapping("/actor/open")
    public ResultBody<Map<String, Object>> open(
            @RequestBody(required = false) SubmitBody<ActorOpenRequest> request) {
        String owner = AcceptanceAccess.requireAdmin();
        requireData(request);
        return ResultBody.success(request, actors.open(owner, request.getRequestId(), request.getData()));
    }

    @PostMapping("/actor/close")
    public ResultBody<Map<String, Object>> close(
            @RequestBody(required = false) SubmitBody<ActorCloseRequest> request) {
        String owner = AcceptanceAccess.requireAdmin();
        requireData(request);
        return ResultBody.success(request, actors.close(owner, request.getRequestId(), request.getData()));
    }

    @PostMapping("/actor/observe")
    public ResultBody<Map<String, Object>> observe(
            @RequestBody(required = false) SubmitBody<ActorObserveRequest> request) {
        String owner = AcceptanceAccess.requireAdmin();
        requireData(request);
        return ResultBody.success(request, actors.observe(owner, request.getData()));
    }

    @PostMapping("/actor/act")
    public ResultBody<Map<String, Object>> act(
            @RequestBody(required = false) SubmitBody<ActorActRequest> request) {
        String owner = AcceptanceAccess.requireAdmin();
        requireData(request);
        return ResultBody.success(request, actors.act(owner, request.getRequestId(), request.getData()));
    }

    @GetMapping("/operation/{id}")
    public ResultBody<Map<String, Object>> operation(
            @PathVariable("id") String operationId,
            @RequestParam(name = "since_cursor", defaultValue = "0") long sinceCursor,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "since_output", defaultValue = "0") long sinceOutput) {
        return ResultBody.success(actors.get(AcceptanceAccess.requireAdmin(), operationId, sinceCursor, limit, sinceOutput));
    }

    @PostMapping("/script/run")
    public ResultBody<Map<String, Object>> scriptRun(
            @RequestBody(required = false) SubmitBody<ActorScriptRequest> request) {
        String owner = AcceptanceAccess.requireAdmin();
        requireData(request);
        return ResultBody.success(request, actors.scriptRun(owner, request.getRequestId(), request.getData()));
    }

    @PostMapping(value = "/script/stream", produces = "application/x-ndjson")
    public WebAsyncTask<Void> scriptStream(
            @RequestBody(required = false) SubmitBody<ActorScriptRequest> request,
            @RequestParam(name = "since_output", defaultValue = "0") long sinceOutput,
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        String owner = AcceptanceAccess.requireAdmin();
        requireData(request);
        StreamingResponseBody stream = actors.scriptStream(owner, request.getRequestId(), request.getData(), sinceOutput);
        // The native JWT filter authenticates only the initial dispatch. Keep that same
        // request's identity available when Spring completes the async streaming response.
        streamSecurityContext.saveContext(SecurityContextHolder.getContext(), servletRequest, servletResponse);
        servletResponse.setContentType("application/x-ndjson");
        servletResponse.setHeader("Cache-Control", "no-store");
        servletResponse.setHeader("X-Accel-Buffering", "no");
        // Spring applies this invocation's timeout before starting Servlet async processing.
        return new WebAsyncTask<>(request.getData().timeoutMs() + 5_000, () -> {
            stream.writeTo(servletResponse.getOutputStream());
            return null;
        });
    }

    @PostMapping("/script/cancel")
    public ResultBody<Map<String, Object>> scriptCancel(
            @RequestBody(required = false) SubmitBody<ActorScriptCancelRequest> request) {
        String owner = AcceptanceAccess.requireAdmin();
        requireData(request);
        return ResultBody.success(request, actors.scriptCancel(owner, request.getRequestId(), request.getData()));
    }

    @PostMapping("/operation/{id}/cancel")
    public ResultBody<Map<String, Object>> cancel(
            @PathVariable("id") String operationId,
            @RequestBody(required = false) SubmitBody<Map<String, Object>> request) {
        String owner = AcceptanceAccess.requireAdmin();
        if (request == null) throw new ObservationException(HttpStatus.BAD_REQUEST, "malformed_request");
        return ResultBody.success(request, actors.cancel(owner, request.getRequestId(), operationId));
    }

    // Native global advice converts RuntimeException to HTTP 200; keep this API's
    // error status and fixed reasons local without echoing request contents.
    @ExceptionHandler(ObservationException.class)
    public ResponseEntity<ResultBody<Map<String, String>>> observationFailure(ObservationException failure) {
        return errorResponse(failure.status(), failure.reason());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ResultBody<Map<String, String>>> malformedRequest(Exception ignored) {
        return errorResponse(HttpStatus.BAD_REQUEST, "malformed_request");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ResultBody<Map<String, String>>> unexpectedFailure(RuntimeException ignored) {
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "actor_control_error");
    }

    private static void requireData(SubmitBody<?> request) {
        if (request == null || request.getData() == null) {
            throw new ObservationException(HttpStatus.BAD_REQUEST, "malformed_request");
        }
    }

    private static ResponseEntity<ResultBody<Map<String, String>>> errorResponse(HttpStatus status, String reason) {
        ResultBody<Map<String, String>> body = new ResultBody<>();
        body.setCode(status.value());
        body.setMessage(reason);
        body.setResponseId(UUID.randomUUID().toString());
        body.setData(Map.of("reason", reason));
        return ResponseEntity.status(status).body(body);
    }
}
