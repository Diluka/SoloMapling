package org.gms.soloacceptance;

import jakarta.servlet.http.HttpServletResponse;
import org.gms.model.dto.ResultBody;
import org.gms.model.dto.SubmitBody;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** Native admin authentication is required independently of actor control being enabled. */
@RestController
@RequestMapping("/solo/acceptance/v1/scene")
@ConditionalOnProperty(prefix = "solomapling.acceptance", name = "enabled", havingValue = "true", matchIfMissing = false)
public final class SceneCaptureController {
    private final SceneCaptureService captures;

    public SceneCaptureController(SceneCaptureService captures) { this.captures = captures; }

    @PostMapping("/snapshot")
    public ResultBody<Map<String, Object>> snapshot(
            @RequestBody(required = false) SubmitBody<SceneCaptureRequest.Snapshot> request) {
        AcceptanceAccess.requireAdmin();
        requireData(request);
        return ResultBody.success(request, captures.snapshot(request.getData().sceneRef()));
    }

    @PostMapping(value = "/record", produces = "application/x-ndjson")
    public void record(@RequestBody(required = false) SubmitBody<SceneCaptureRequest.Record> request,
                       HttpServletResponse response) throws IOException {
        String owner = AcceptanceAccess.requireAdmin();
        requireData(request);
        response.setContentType("application/x-ndjson");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        captures.record(owner, request.getData(), response.getOutputStream());
    }

    @GetMapping("/record/{id}")
    public ResultBody<Map<String, Object>> status(@PathVariable("id") String id) {
        return ResultBody.success(captures.status(AcceptanceAccess.requireAdmin(), id));
    }

    @PostMapping("/record/{id}/cancel")
    public ResultBody<Map<String, Object>> cancel(@PathVariable("id") String id) {
        return ResultBody.success(captures.cancel(AcceptanceAccess.requireAdmin(), id));
    }

    @ExceptionHandler(ObservationException.class)
    public ResponseEntity<ResultBody<Map<String, String>>> failure(ObservationException failure) {
        return errorResponse(failure.status(), failure.reason());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ResultBody<Map<String, String>>> malformed(HttpMessageNotReadableException ignored) {
        return errorResponse(HttpStatus.BAD_REQUEST, "malformed_request");
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
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
