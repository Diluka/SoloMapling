package org.gms.soloacceptance;

import org.gms.model.dto.SubmitBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcceptanceObservationControllerTest {
    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void bridgeBeansAreAbsentByDefaultAndPresentOnlyWhenExplicitlyEnabled() {
        for (String setting : List.of("missing", "false", "true")) {
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                if (!"missing".equals(setting)) {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                            Map.of("solomapling.acceptance.enabled", setting)));
                }
                context.register(AcceptanceObservationController.class, AcceptanceObservationService.class);
                context.refresh();
                assertEquals("true".equals(setting), !context.getBeansOfType(AcceptanceObservationController.class).isEmpty());
                assertEquals("true".equals(setting), !context.getBeansOfType(AcceptanceObservationService.class).isEmpty());
            }
        }
    }

    @Test
    void onlyAnAuthenticatedAdministratorCanReadEitherEndpoint() {
        AcceptanceObservationService service = mock(AcceptanceObservationService.class);
        AcceptanceObservationController controller = new AcceptanceObservationController(service);
        ObservationException unauthenticated = assertThrows(ObservationException.class, controller::capabilities);
        assertEquals(HttpStatus.UNAUTHORIZED, unauthenticated.status());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "observer", "not-returned", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        ObservationException forbidden = assertThrows(ObservationException.class,
                () -> controller.observe(new SubmitBody<>("request", new ObservationRequest("world", null, 10))));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.status());
        verifyNoInteractions(service);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", "not-returned", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(service.capabilities()).thenReturn(Map.of("service", "solomapling-acceptance"));
        assertEquals(20000, controller.capabilities().getCode());
        when(service.observe(any())).thenReturn(Map.of("scope", "world"));
        assertEquals("request", controller.observe(new SubmitBody<>("request", new ObservationRequest("world", null, 10))).getResponseId());
    }

    @Test
    void localErrorHandlingPreservesHttpStatusAndDoesNotReturnRequestOrInternalMessages() {
        AcceptanceObservationController controller = new AcceptanceObservationController(mock(AcceptanceObservationService.class));
        var stale = controller.observationFailure(new ObservationException(HttpStatus.GONE, "stale_scene_ref"));
        assertEquals(HttpStatus.GONE, stale.getStatusCode());
        assertNotNull(stale.getBody());
        assertEquals(Map.of("reason", "stale_scene_ref"), stale.getBody().getData());
        var malformed = controller.malformedRequest(new HttpMessageNotReadableException("password=must-not-escape"));
        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatusCode());
        assertNotNull(malformed.getBody());
        assertEquals("malformed_request", malformed.getBody().getMessage());
        assertFalse(malformed.getBody().toString().contains("must-not-escape"));
    }
}
