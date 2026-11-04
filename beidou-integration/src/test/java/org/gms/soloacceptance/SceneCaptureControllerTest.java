package org.gms.soloacceptance;

import jakarta.servlet.http.HttpServletResponse;
import org.gms.model.dto.SubmitBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SceneCaptureControllerTest {
    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void captureAndItsStopControlsUseTheExistingAdministratorIdentity() throws Exception {
        var service = mock(SceneCaptureService.class);
        var controller = new SceneCaptureController(service);
        var unauthenticated = assertThrows(ObservationException.class, () -> controller.snapshot(
                new SubmitBody<>("snapshot", new SceneCaptureRequest.Snapshot("scene-ref"))));
        assertEquals(HttpStatus.UNAUTHORIZED, unauthenticated.status());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "observer", "unused", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        var response = mock(HttpServletResponse.class);
        var forbidden = assertThrows(ObservationException.class, () -> controller.record(
                new SubmitBody<>("record", new SceneCaptureRequest.Record("scene-ref", 100, 50)), response));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.status());
        verifyNoInteractions(service, response);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(service.status("admin", "capture-known")).thenReturn(Map.of("sampling_stopped", true));
        assertEquals(true, controller.status("capture-known").getData().get("sampling_stopped"));
        controller.cancel("capture-known");
        verify(service).cancel("admin", "capture-known");
    }
}
