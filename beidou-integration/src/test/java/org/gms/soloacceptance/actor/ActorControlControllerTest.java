package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gms.model.dto.SubmitBody;
import org.gms.soloacceptance.ObservationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import soloMapling.beidou.CharacterTestContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActorControlControllerTest {
    @BeforeAll
    static void legacyContext() {
        CharacterTestContext.install();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void transportAppearsOnlyWhenBothFeatureFlagsAreExplicitlyTrue() {
        for (Map<String, Object> settings : List.<Map<String, Object>>of(
                Map.of(),
                Map.of("solomapling.acceptance.enabled", "true"),
                Map.of("solomapling.acceptance.actors-enabled", "true"),
                Map.of("solomapling.acceptance.enabled", "false", "solomapling.acceptance.actors-enabled", "true"),
                Map.of("solomapling.acceptance.enabled", "true", "solomapling.acceptance.actors-enabled", "false"),
                Map.of("solomapling.acceptance.enabled", "true", "solomapling.acceptance.actors-enabled", "true"))) {
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", settings));
                context.registerBean(ActorControlService.class, () -> mock(ActorControlService.class));
                context.register(ActorControlController.class);
                context.refresh();
                boolean enabled = "true".equals(settings.get("solomapling.acceptance.enabled"))
                        && "true".equals(settings.get("solomapling.acceptance.actors-enabled"));
                assertEquals(enabled, !context.getBeansOfType(ActorControlController.class).isEmpty(), settings.toString());
            }
        }
    }

    @Test
    void allEndpointsAuthenticateBeforeCallingTheService() {
        ActorControlService service = mock(ActorControlService.class);
        ActorControlController controller = new ActorControlController(service);
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ObservationException.class, () -> controller.open(null)).status());
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ObservationException.class, () -> controller.close(null)).status());
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ObservationException.class, () -> controller.operation("op", 0, 100)).status());
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ObservationException.class, () -> controller.cancel("op", null)).status());

        authenticate("reader", "ROLE_USER");
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ObservationException.class, () -> controller.operation("op", 0, 100)).status());
        verifyNoInteractions(service);
    }

    @Test
    void adminIdentityAndRequestIdsReachTheirOwnerScopedServiceCalls() {
        ActorControlService service = mock(ActorControlService.class);
        ActorControlController controller = new ActorControlController(service);
        authenticate("operator-a", "ROLE_ADMIN");

        ActorOpenRequest openData = new ActorOpenRequest("primary", 0, 1);
        when(service.open("operator-a", "open-request", openData)).thenReturn(Map.of("status", "QUEUED"));
        var opened = controller.open(new SubmitBody<>("open-request", openData));
        assertEquals(20000, opened.getCode());
        assertEquals("open-request", opened.getResponseId());
        assertEquals("QUEUED", opened.getData().get("status"));

        ActorCloseRequest closeData = new ActorCloseRequest("session-id", 7L);
        when(service.close("operator-a", "close-request", closeData)).thenReturn(Map.of("status", "QUEUED"));
        assertEquals("close-request", controller.close(new SubmitBody<>("close-request", closeData)).getResponseId());

        when(service.get("operator-a", "op-id", 12, 25)).thenReturn(Map.of("status", "RUNNING"));
        assertEquals("RUNNING", controller.operation("op-id", 12, 25).getData().get("status"));

        when(service.cancel("operator-a", "cancel-request", "op-id")).thenReturn(Map.of("status", "CANCEL_REQUESTED"));
        var cancelled = controller.cancel("op-id", new SubmitBody<>("cancel-request", null));
        assertEquals("cancel-request", cancelled.getResponseId());
        assertEquals("CANCEL_REQUESTED", cancelled.getData().get("status"));
        verify(service).open("operator-a", "open-request", openData);
        verify(service).close("operator-a", "close-request", closeData);
        verify(service).get("operator-a", "op-id", 12, 25);
        verify(service).cancel("operator-a", "cancel-request", "op-id");
    }

    @Test
    void missingBodyOrDataIsAControlledBadRequest() {
        ActorControlService service = mock(ActorControlService.class);
        ActorControlController controller = new ActorControlController(service);
        authenticate("operator-a", "ROLE_ADMIN");
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ObservationException.class, () -> controller.open(null)).status());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ObservationException.class, () -> controller.open(new SubmitBody<>("request", null))).status());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ObservationException.class, () -> controller.close(new SubmitBody<>("request", null))).status());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ObservationException.class, () -> controller.cancel("op", null)).status());
        verifyNoInteractions(service);
    }

    @Test
    void localHandlersKeepStatusesAndNeverEchoInputOrInternalFailureMessages() {
        ActorControlController controller = new ActorControlController(mock(ActorControlService.class));
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND,
                HttpStatus.CONFLICT, HttpStatus.GONE, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.TOO_MANY_REQUESTS)) {
            var response = controller.observationFailure(new ObservationException(status, "fixed_reason"));
            assertEquals(status, response.getStatusCode());
            assertNotNull(response.getBody());
            assertEquals(status.value(), response.getBody().getCode());
            assertEquals(Map.of("reason", "fixed_reason"), response.getBody().getData());
        }
        String secret = "password=must-not-escape";
        var malformed = controller.malformedRequest(new HttpMessageNotReadableException(secret));
        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatusCode());
        assertNotNull(malformed.getBody());
        assertFalse(malformed.getBody().toString().contains(secret));
        var badParameter = controller.malformedRequest(new MethodArgumentTypeMismatchException(
                secret, Long.class, "since_cursor", null, new NumberFormatException(secret)));
        assertEquals(HttpStatus.BAD_REQUEST, badParameter.getStatusCode());
        assertNotNull(badParameter.getBody());
        assertEquals("malformed_request", badParameter.getBody().getMessage());
        var unexpected = controller.unexpectedFailure(new IllegalStateException(secret));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, unexpected.getStatusCode());
        assertNotNull(unexpected.getBody());
        assertFalse(unexpected.getBody().toString().contains(secret));
    }

    @Test
    void nativeIdentifiersRejectJacksonCoercionAndOverflow() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ActorOpenRequest opened = mapper.readValue("{\"profile_id\":\"primary\",\"world\":0,\"channel\":1}", ActorOpenRequest.class);
        assertEquals(new ActorOpenRequest("primary", 0, 1), opened);
        ActorCloseRequest closed = mapper.readValue("{\"session_id\":\"session-id\",\"expected_generation\":7}", ActorCloseRequest.class);
        assertEquals(new ActorCloseRequest("session-id", 7L), closed);
        for (String value : List.of("1.5", "\"1\"", "true", "2147483648")) {
            assertThrows(Exception.class, () -> mapper.readValue(
                    "{\"profile_id\":\"primary\",\"world\":" + value + ",\"channel\":1}", ActorOpenRequest.class), value);
            assertThrows(Exception.class, () -> mapper.readValue(
                    "{\"profile_id\":\"primary\",\"world\":0,\"channel\":" + value + "}", ActorOpenRequest.class), value);
        }
        for (String value : List.of("1.5", "\"7\"", "false", "9223372036854775808")) {
            assertThrows(Exception.class, () -> mapper.readValue(
                    "{\"session_id\":\"session-id\",\"expected_generation\":" + value + "}", ActorCloseRequest.class), value);
        }
    }

    private static void authenticate(String name, String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                name, "not-returned", List.of(new SimpleGrantedAuthority(authority))));
    }
}
