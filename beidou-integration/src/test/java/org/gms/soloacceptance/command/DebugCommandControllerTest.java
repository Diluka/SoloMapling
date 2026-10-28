package org.gms.soloacceptance.command;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gms.soloacceptance.ObservationException;
import org.gms.soloacceptance.actor.ActorProfiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import soloMapling.beidou.CharacterTestContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DebugCommandControllerTest {
    @BeforeAll
    static void legacyContext() {
        CharacterTestContext.install();
    }

    @AfterEach
    void authentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allThreeExplicitFlagsAreRequiredForServiceAndController() {
        for (int mask = 0; mask < 8; mask++) {
            Map<String, Object> settings = Map.of(
                    "solomapling.acceptance.enabled", Boolean.toString((mask & 1) != 0),
                    "solomapling.acceptance.actors-enabled", Boolean.toString((mask & 2) != 0),
                    "solomapling.acceptance.debug-commands-enabled", Boolean.toString((mask & 4) != 0));
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", settings));
                context.registerBean(ActorProfiles.class, DebugCommandServiceTest::profiles);
                context.registerBean(NativeDebugCommandBackend.class, () -> mock(NativeDebugCommandBackend.class));
                context.register(DebugCommandService.class, DebugCommandController.class);
                context.refresh();
                assertEquals(mask == 7, !context.getBeansOfType(DebugCommandService.class).isEmpty(), settings.toString());
                assertEquals(mask == 7, !context.getBeansOfType(DebugCommandController.class).isEmpty(), settings.toString());
            }
        }
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(DebugCommandController.class);
            context.refresh();
            assertTrue(context.getBeansOfType(DebugCommandController.class).isEmpty());
        }
    }

    @Test
    void bothEndpointsRequireAdministratorBeforeLookingAtInputsOrService() {
        DebugCommandService service = mock(DebugCommandService.class);
        DebugCommandController controller = new DebugCommandController(service);
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ObservationException.class,
                () -> controller.help(null)).status());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ObservationException.class,
                () -> controller.execute(null)).status());
        authenticate("reader", "ROLE_USER");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ObservationException.class,
                () -> controller.help("give")).status());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ObservationException.class,
                () -> controller.execute(null)).status());
        verifyNoInteractions(service);
    }

    @Test
    void administratorIdentityAndOuterRequestIdArePreserved() {
        DebugCommandService service = mock(DebugCommandService.class);
        DebugCommandController controller = new DebugCommandController(service);
        authenticate("admin-a", "ROLE_ADMIN");
        DebugCommandRequest request = new DebugCommandRequest("primary", "generation", "give",
                new DebugCommandRequest.Parameters(2000000, 1));
        Map<String, Object> outcome = Map.of("status", "SUCCEEDED");
        when(service.execute("admin-a", "give-id", request)).thenReturn(outcome);
        var response = controller.execute(new DebugCommandRequest.Submission("give-id", request));
        assertEquals(20000, response.getCode());
        assertEquals("give-id", response.getResponseId());
        assertSame(outcome, response.getData());
        verify(service).execute("admin-a", "give-id", request);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ObservationException.class,
                () -> controller.execute(new DebugCommandRequest.Submission("give-id", null))).status());
    }

    @Test
    void errorsRemainNonSuccessHttpResponsesAndNeverEchoRequestOrExceptionContents() {
        DebugCommandController controller = new DebugCommandController(mock(DebugCommandService.class));
        var response = controller.commandFailure(new ObservationException(HttpStatus.CONFLICT, "command_generation_changed"));
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(409, response.getBody().getCode());
        assertEquals("command_generation_changed", response.getBody().getData().get("reason"));
        var unexpected = controller.unexpectedFailure(new IllegalStateException("secret password and request"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, unexpected.getStatusCode());
        assertEquals("debug_command_error", unexpected.getBody().getMessage());
        assertFalse(unexpected.getBody().getData().toString().contains("secret"));
    }

    @Test
    void nativeJacksonCoercionAndUnknownFieldSettingsCannotExpandFiniteInputs() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        String valid = "{\"requestId\":\"give-id\",\"data\":{\"profile_id\":\"primary\","
                + "\"expected_command_generation\":\"generation\",\"command\":\"give\","
                + "\"parameters\":{\"item_id\":2000000,\"quantity\":1}}}";
        assertEquals(2000000, mapper.readValue(valid, DebugCommandRequest.Submission.class).data().parameters().itemId());
        for (String invalid : List.of(
                valid.replace("\"requestId\":", "\"playerId\":0,\"requestId\":"),
                valid.replace("\"profile_id\":", "\"player_id\":0,\"profile_id\":"),
                valid.replace("\"item_id\":", "\"type\":11,\"item_id\":"),
                valid.replace("\"quantity\":1", "\"quantity\":\"1\""),
                valid.replace("\"quantity\":1", "\"quantity\":1.0"),
                valid.replace("\"quantity\":1", "\"quantity\":true"),
                valid.replace("\"quantity\":1", "\"quantity\":2147483648"),
                valid.replace("\"quantity\":1", "\"quantity\":1,\"quantity\":2"),
                valid.replace("\"profile_id\":\"primary\"", "\"profile_id\":123"))) {
            assertThrows(Exception.class, () -> mapper.readValue(invalid, DebugCommandRequest.Submission.class), invalid);
        }
    }

    private static void authenticate(String name, String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                name, "unused", List.of(new SimpleGrantedAuthority(authority))));
    }
}
