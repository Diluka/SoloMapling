package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
                assertThrows(ObservationException.class, () -> controller.observe(null)).status());
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ObservationException.class, () -> controller.act(null)).status());
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ObservationException.class, () -> controller.operation("op", 0, 100)).status());
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ObservationException.class, () -> controller.cancel("op", null)).status());
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ObservationException.class, () -> controller.scriptRun(null)).status());
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ObservationException.class, () -> controller.scriptHelp(null)).status());
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ObservationException.class, () -> controller.scriptCancel(null)).status());

        authenticate("reader", "ROLE_USER");
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ObservationException.class, () -> controller.operation("op", 0, 100)).status());
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ObservationException.class, () -> controller.observe(null)).status());
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ObservationException.class, () -> controller.act(null)).status());
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ObservationException.class, () -> controller.scriptRun(null)).status());
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ObservationException.class, () -> controller.scriptHelp(null)).status());
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ObservationException.class, () -> controller.scriptCancel(null)).status());
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
    void observationDelegatesToTheOwnerScopedReadWithoutPassingRequestIdsToAnOperation() {
        ActorControlService service = mock(ActorControlService.class);
        ActorControlController controller = new ActorControlController(service);
        authenticate("operator-a", "ROLE_ADMIN");
        ActorObserveRequest data = new ActorObserveRequest("session-id", 7L, 12L, 25);
        Map<String, Object> snapshot = Map.of("scope", "actor", "session_id", "session-id");
        when(service.observe("operator-a", data)).thenReturn(snapshot);

        var first = controller.observe(new SubmitBody<>("read-first", data));
        var repeated = controller.observe(new SubmitBody<>("read-next", data));
        assertEquals(20000, first.getCode());
        assertEquals("read-first", first.getResponseId());
        assertSame(snapshot, first.getData());
        assertEquals("read-next", repeated.getResponseId());
        assertSame(snapshot, repeated.getData());
        verify(service, times(2)).observe("operator-a", data);
        verifyNoMoreInteractions(service);
    }

    @Test
    void actionDelegatesTheOwnerOuterRequestIdAndExactContextWithoutExecutingInTheController() {
        ActorControlService service = mock(ActorControlService.class);
        ActorControlController controller = new ActorControlController(service);
        authenticate("operator-a", "ROLE_ADMIN");
        ActorActRequest data = new ActorActRequest("session-id", 7L, "chat",
                new ActorActRequest.Parameters("聊天验收 abc123"),
                new ActorActRequest.ExpectedContext("scene-id", "runtime-id"));
        Map<String, Object> operation = Map.of("kind", "ACTION", "status", "QUEUED", "action", "chat");
        when(service.act("operator-a", "action-request", data)).thenReturn(operation);

        var accepted = controller.act(new SubmitBody<>("action-request", data));
        assertEquals(20000, accepted.getCode());
        assertEquals("action-request", accepted.getResponseId());
        assertSame(operation, accepted.getData());
        verify(service).act("operator-a", "action-request", data);
        verifyNoMoreInteractions(service);
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
                assertThrows(ObservationException.class, () -> controller.observe(null)).status());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ObservationException.class, () -> controller.observe(new SubmitBody<>("request", null))).status());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ObservationException.class, () -> controller.act(null)).status());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ObservationException.class, () -> controller.act(new SubmitBody<>("request", null))).status());
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

    @Test
    void packetObservationDefaultsAndIntegerWidthsDoNotApplyServiceRangeRules() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ActorObserveRequest defaulted = mapper.readValue(
                "{\"session_id\":\"session-id\",\"expected_generation\":7}", ActorObserveRequest.class);
        assertEquals(new ActorObserveRequest("session-id", 7L, 0L, 100), defaulted);
        ActorObserveRequest explicit = mapper.readValue(
                "{\"session_id\":\"session-id\",\"expected_generation\":9223372036854775807,"
                        + "\"since_cursor\":9223372036854775807,\"limit\":2147483647}", ActorObserveRequest.class);
        assertEquals(new ActorObserveRequest("session-id", Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE), explicit);
        assertEquals(new ActorObserveRequest(null, null, 0L, 100), mapper.readValue("{}", ActorObserveRequest.class));
        ActorObserveRequest outsideRange = mapper.readValue(
                "{\"session_id\":\"\",\"expected_generation\":0,\"since_cursor\":-1,\"limit\":0}", ActorObserveRequest.class);
        assertEquals(new ActorObserveRequest("", 0L, -1L, 0), outsideRange);
    }

    @Test
    void packetObservationRejectsNonStringIdentifiersAndNumericCoercion() {
        ObjectMapper mapper = new ObjectMapper();
        for (String value : List.of("123", "1.5", "true", "{}", "[]")) {
            assertThrows(Exception.class, () -> mapper.readValue(
                    "{\"session_id\":" + value + ",\"expected_generation\":7}", ActorObserveRequest.class), value);
        }
        for (String field : List.of("expected_generation", "since_cursor")) {
            for (String value : List.of("1.0", "\"7\"", "false", "9223372036854775808", "-9223372036854775809")) {
                assertThrows(Exception.class, () -> mapper.readValue(
                        "{\"session_id\":\"session-id\",\"" + field + "\":" + value + "}", ActorObserveRequest.class),
                        field + "=" + value);
            }
        }
        for (String value : List.of("1.0", "\"100\"", "true", "2147483648", "-2147483649")) {
            assertThrows(Exception.class, () -> mapper.readValue(
                    "{\"session_id\":\"session-id\",\"expected_generation\":7,\"limit\":" + value + "}",
                    ActorObserveRequest.class), value);
        }
    }

    @Test
    void actionDtoBindsTheNativeEnvelopeAndLeavesSemanticValidationToTheService() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String data = actionJson();
        SubmitBody<ActorActRequest> envelope = mapper.readValue(
                "{\"requestId\":\"action-request\",\"data\":" + data + "}", new TypeReference<>() {});
        assertEquals("action-request", envelope.getRequestId());
        assertEquals(new ActorActRequest("session-id", 7L, "chat",
                new ActorActRequest.Parameters("聊天验收 abc123"),
                new ActorActRequest.ExpectedContext("scene-id", "runtime-id")), envelope.getData());
        assertEquals(new ActorActRequest(null, null, null, null, null),
                mapper.readValue("{}", ActorActRequest.class));
        ObjectNode widest = (ObjectNode) mapper.readTree(data);
        widest.put("expected_generation", Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, mapper.treeToValue(widest, ActorActRequest.class).expectedGeneration());
        widest.put("expected_generation", 0);
        widest.put("action", "unsupported");
        assertEquals("unsupported", mapper.treeToValue(widest, ActorActRequest.class).action());
    }

    @Test
    void actionDtoRejectsCoercionAndOverflowAtEveryContractStringAndGeneration() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for (String value : List.of("\"value\"", "7", "true", "[]")) {
            assertThrows(Exception.class, () -> mapper.readValue(value, ActorActRequest.class), value);
        }
        for (String path : List.of("session_id", "action", "parameters.text",
                "expected_context.scene_ref", "expected_context.runtime_generation")) {
            for (String value : List.of("123", "1.0", "true", "{}", "[]")) {
                ObjectNode body = (ObjectNode) mapper.readTree(actionJson());
                String[] components = path.split("\\.");
                ObjectNode owner = components.length == 1 ? body : (ObjectNode) body.get(components[0]);
                owner.set(components[components.length - 1], mapper.readTree(value));
                assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorActRequest.class), path + "=" + value);
            }
        }
        for (String value : List.of("1.0", "\"7\"", "true", "9223372036854775808", "-9223372036854775809")) {
            ObjectNode body = (ObjectNode) mapper.readTree(actionJson());
            body.set("expected_generation", mapper.readTree(value));
            assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorActRequest.class), value);
        }
        for (String field : List.of("parameters", "expected_context")) {
            for (String value : List.of("\"value\"", "1", "true", "[]")) {
                ObjectNode body = (ObjectNode) mapper.readTree(actionJson());
                body.set(field, mapper.readTree(value));
                assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorActRequest.class), field + "=" + value);
            }
        }
    }

    @Test
    void actionDtoRejectsUnknownFieldsEvenWhenTheHostMapperIgnoresThem() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for (String path : List.of("request_id", "character_id", "parameters.show", "parameters.script",
                "expected_context.map_id", "expected_context.character_id")) {
            ObjectNode body = (ObjectNode) mapper.readTree(actionJson());
            String[] components = path.split("\\.");
            ObjectNode owner = components.length == 1 ? body : (ObjectNode) body.get(components[0]);
            owner.putNull(components[components.length - 1]);
            assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorActRequest.class), path);
        }
    }

    @Test
    void actionParametersFollowTheirTagsAndPreserveTheExistingChatConstructor() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for (ActorActRequest request : List.of(
                new ActorActRequest("session-id", 7L, "chat", new ActorActRequest.Parameters("聊天验收 abc123"),
                        new ActorActRequest.ExpectedContext("scene-id", "runtime-id")),
                new ActorActRequest("session-id", 7L, "npc_center_open", new ActorActRequest.Parameters(null, null),
                        new ActorActRequest.ExpectedContext("scene-id", "runtime-id")),
                new ActorActRequest("session-id", 7L, "npc_cancel", new ActorActRequest.Parameters(null, Long.MAX_VALUE),
                        new ActorActRequest.ExpectedContext("scene-id", "runtime-id")))) {
            String serialized = mapper.writeValueAsString(request);
            assertEquals(request, mapper.readValue(serialized, ActorActRequest.class));
            assertEquals("npc_center_open".equals(request.action()) ? 0 : 1,
                    mapper.readTree(serialized).get("parameters").size());
        }
        assertEquals(new ActorActRequest.Parameters("chat", null), new ActorActRequest.Parameters("chat"));
        assertEquals(mapper.readValue(actionJson(), ActorActRequest.class).parameters().text(), "聊天验收 abc123");
    }

    @Test
    void npcDialogueVersionRejectsCoercionAndOverflowWhileRangesRemainWithTheService() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for (String value : List.of("1.0", "\"7\"", "true", "{}", "[]",
                "9223372036854775808", "-9223372036854775809")) {
            ObjectNode body = npcActionJson(mapper, "npc_cancel");
            ((ObjectNode) body.get("parameters")).set("dialogue_version", mapper.readTree(value));
            assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorActRequest.class), value);
        }
        for (long value : List.of(0L, -1L, Long.MAX_VALUE)) {
            ObjectNode body = npcActionJson(mapper, "npc_cancel");
            ((ObjectNode) body.get("parameters")).put("dialogue_version", value);
            assertEquals(value, mapper.treeToValue(body, ActorActRequest.class).parameters().dialogueVersion());
        }
        ObjectNode missing = npcActionJson(mapper, "npc_cancel");
        assertNull(mapper.treeToValue(missing, ActorActRequest.class).parameters().dialogueVersion());
        ((ObjectNode) missing.get("parameters")).putNull("dialogue_version");
        assertNull(mapper.treeToValue(missing, ActorActRequest.class).parameters().dialogueVersion());
    }

    @Test
    void taggedActionParametersRejectFieldsFromOtherActionsEvenWhenExplicitlyNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for (String action : List.of("chat", "npc_center_open", "npc_cancel")) {
            for (String field : List.of("text", "dialogue_version", "npc_template_id", "script", "kind", "selection")) {
                if ("chat".equals(action) && "text".equals(field)
                        || "npc_cancel".equals(action) && "dialogue_version".equals(field)) continue;
                ObjectNode body = npcActionJson(mapper, action);
                ((ObjectNode) body.get("parameters")).putNull(field);
                assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorActRequest.class), action + "." + field);
            }
        }
    }

    @Test
    void unsupportedActionsReachTheServiceOnlyWithKnownStrictParameterShapes() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        ObjectNode body = npcActionJson(mapper, "unsupported");
        assertEquals("unsupported", mapper.treeToValue(body, ActorActRequest.class).action());
        ObjectNode parameters = (ObjectNode) body.get("parameters");
        parameters.put("text", "保留给服务校验");
        parameters.put("dialogue_version", 7);
        assertEquals(new ActorActRequest.Parameters("保留给服务校验", 7L),
                mapper.treeToValue(body, ActorActRequest.class).parameters());
        parameters.put("script", "unavailable");
        assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorActRequest.class));
    }

    @Test
    void npcReplyParametersRoundTripAndPreserveTheExistingParameterConstructors() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for (ActorActRequest.Parameters parameters : List.of(
                new ActorActRequest.Parameters(null, 7L, "select", Integer.MAX_VALUE),
                new ActorActRequest.Parameters(null, 7L, "decline", null))) {
            ActorActRequest request = new ActorActRequest("session-id", 7L, "npc_reply", parameters,
                    new ActorActRequest.ExpectedContext("scene-id", "runtime-id"));
            String serialized = mapper.writeValueAsString(request);
            assertEquals(request, mapper.readValue(serialized, ActorActRequest.class));
            assertEquals("select".equals(parameters.kind()) ? 3 : 2,
                    mapper.readTree(serialized).get("parameters").size());
        }
        assertEquals(new ActorActRequest.Parameters("chat", null, null, null),
                new ActorActRequest.Parameters("chat"));
        assertEquals(new ActorActRequest.Parameters(null, 7L, null, null),
                new ActorActRequest.Parameters(null, 7L));
    }

    @Test
    void npcReplyKindsAreMutuallyExclusiveAndRejectUnknownFieldsEvenWhenNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for (String kind : List.of("select", "decline", "accept", "unsupported")) {
            for (String field : List.of("text", "selection", "lastMsg", "action", "npc_template_id", "script")) {
                if ("select".equals(kind) && "selection".equals(field)) continue;
                ObjectNode body = npcActionJson(mapper, "npc_reply");
                ObjectNode parameters = (ObjectNode) body.get("parameters");
                parameters.put("dialogue_version", 7);
                parameters.put("kind", kind);
                parameters.putNull(field);
                assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorActRequest.class), kind + "." + field);
            }
        }
    }

    @Test
    void npcReplyFieldsRejectCoercionAndIntegerOverflowWithoutApplyingServiceRanges() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for (String field : List.of("kind", "selection", "dialogue_version")) {
            List<String> invalid = "kind".equals(field) ? List.of("1", "1.0", "true", "{}", "[]")
                    : "selection".equals(field) ? List.of("1.0", "\"7\"", "true", "{}", "[]", "2147483648", "-2147483649")
                    : List.of("1.0", "\"7\"", "true", "{}", "[]", "9223372036854775808", "-9223372036854775809");
            for (String value : invalid) {
                ObjectNode body = npcActionJson(mapper, "npc_reply");
                ObjectNode parameters = (ObjectNode) body.get("parameters");
                parameters.put("dialogue_version", 7);
                parameters.put("kind", "select");
                parameters.set(field, mapper.readTree(value));
                assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorActRequest.class), field + "=" + value);
            }
        }
        ObjectNode body = npcActionJson(mapper, "npc_reply");
        ObjectNode parameters = (ObjectNode) body.get("parameters");
        parameters.put("kind", "select");
        parameters.put("dialogue_version", Long.MAX_VALUE);
        for (int value : List.of(Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE)) {
            parameters.put("selection", value);
            assertEquals(value, mapper.treeToValue(body, ActorActRequest.class).parameters().selection());
        }
        parameters.putNull("selection");
        assertNull(mapper.treeToValue(body, ActorActRequest.class).parameters().selection());
        parameters.remove("selection");
        assertNull(mapper.treeToValue(body, ActorActRequest.class).parameters().selection());
    }

    @Test
    void unsupportedNpcReplyKindsReachTheServiceWithoutOpeningAdditionalParameterShapes() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for (String kind : List.of("accept", "unsupported")) {
            ObjectNode body = npcActionJson(mapper, "npc_reply");
            ObjectNode parameters = (ObjectNode) body.get("parameters");
            parameters.put("dialogue_version", 7);
            parameters.put("kind", kind);
            assertEquals(kind, mapper.treeToValue(body, ActorActRequest.class).parameters().kind());
        }
        ObjectNode body = npcActionJson(mapper, "npc_reply");
        ObjectNode parameters = (ObjectNode) body.get("parameters");
        parameters.put("dialogue_version", 7);
        assertNull(mapper.treeToValue(body, ActorActRequest.class).parameters().kind());
        parameters.putNull("kind");
        assertNull(mapper.treeToValue(body, ActorActRequest.class).parameters().kind());
        parameters.putNull("selection");
        assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorActRequest.class));
    }

    @Test
    void scriptEndpointsKeepAdminOwnerRequestIdsAndOriginalCancellationFingerprint() {
        ActorControlService service = mock(ActorControlService.class);
        ActorControlController controller = new ActorControlController(service);
        authenticate("script-admin", "ROLE_ADMIN");
        var script = new ActorScriptRequest("session", 7L, "parameters.x + 1", Map.of("x", 3), 1000L);
        when(service.scriptRun("script-admin", "script-id", script)).thenReturn(Map.of("kind", "SCRIPT", "status", "SUCCEEDED"));
        assertEquals("SUCCEEDED", controller.scriptRun(new SubmitBody<>("script-id", script)).getData().get("status"));
        var help = new ActorScriptHelpRequest("session", 7L, List.of());
        when(service.scriptHelp("script-admin", help)).thenReturn(Map.of("functions", List.of()));
        assertEquals(List.of(), controller.scriptHelp(new SubmitBody<>("help-id", help)).getData().get("functions"));
        var cancel = new ActorScriptCancelRequest("session", 7L, "script-id", script.source(), script.parameters(), 1000L);
        when(service.scriptCancel("script-admin", "cancel-id", cancel)).thenReturn(Map.of("kind", "SCRIPT", "status", "CANCELLED"));
        assertEquals("cancel-id", controller.scriptCancel(new SubmitBody<>("cancel-id", cancel)).getResponseId());
        verify(service).scriptRun("script-admin", "script-id", script);
        verify(service).scriptHelp("script-admin", help);
        verify(service).scriptCancel("script-admin", "cancel-id", cancel);
    }

    @Test
    void scriptJsonRejectsCoercedSourcesGenerationsTimeoutsAndHelpNames() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String valid = "{\"session_id\":\"session\",\"expected_generation\":7,\"source\":\"1+1\",\"parameters\":{},\"timeout_ms\":1000}";
        for (String field : List.of("session_id", "expected_generation", "source", "timeout_ms")) {
            List<String> invalid = List.of("true", "{}", "[]", "1.25");
            for (String value : invalid) {
                ObjectNode body = (ObjectNode) mapper.readTree(valid);
                body.set(field, mapper.readTree(value));
                assertThrows(Exception.class, () -> mapper.treeToValue(body, ActorScriptRequest.class), field + "=" + value);
            }
        }
        ActorScriptRequest defaults = mapper.readValue("{\"session_id\":\"session\",\"expected_generation\":7,\"source\":\"1\"}", ActorScriptRequest.class);
        assertEquals(1000L, defaults.timeoutMs());
        assertEquals(Map.of(), defaults.parameters());
        for (String invalid : List.of("[1]", "[true]", "{}", "\"fn\"")) {
            assertThrows(Exception.class, () -> mapper.readValue("{\"session_id\":\"session\",\"expected_generation\":7,\"names\":" + invalid + "}", ActorScriptHelpRequest.class));
        }
    }

    @Test
    void nativeScriptDescriptorPreservesJsonArgumentsAndAllowsAnOmittedSource() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        ActorScriptRequest request = mapper.readValue("""
                {"session_id":"session","expected_generation":7,
                 "environment":{"kind":"npc","operation":"invoke","context_id":"native-context",
                    "options":{"npc_id":9010000,"nested":{"flag":true}},"entry":"action",
                    "args":[1,0,{"selection":2},null,"中文"]}}
                """, ActorScriptRequest.class);
        assertEquals("", request.source());
        assertEquals(1000L, request.timeoutMs());
        assertEquals(Map.of(), request.parameters());
        ActorScriptEnvironment environment = request.environment();
        assertEquals("npc", environment.kind());
        assertEquals("invoke", environment.operation());
        assertEquals("native-context", environment.contextId());
        assertEquals(Map.of("npc_id", 9010000, "nested", Map.of("flag", true)), environment.options());
        assertEquals("action", environment.entry());
        assertEquals(java.util.Arrays.asList(1, 0, Map.of("selection", 2), null, "中文"), environment.args());
        assertEquals(request, mapper.readValue(mapper.writeValueAsString(request), ActorScriptRequest.class));

        ActorScriptRequest minimal = mapper.readValue("""
                {"session_id":"session","expected_generation":7,"source":null,
                 "environment":{"kind":"event","operation":"start"}}
                """, ActorScriptRequest.class);
        assertEquals("", minimal.source());
        assertEquals(Map.of(), minimal.environment().options());
        assertEquals(List.of(), minimal.environment().args());
        assertNull(minimal.environment().contextId());
        assertNull(minimal.environment().entry());
    }

    @Test
    void nativeDescriptorsRejectUnknownFieldsAndTypeCoercionWhenTheGlobalMapperIsLenient() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        String valid = """
                {"session_id":"session","expected_generation":7,
                 "environment":{"kind":"npc","operation":"start","context_id":"context",
                    "options":{},"entry":"start","args":[]}}
                """;
        for (String field : List.of("kind", "operation", "context_id", "entry")) {
            for (String value : List.of("1", "true", "{}", "[]")) {
                ObjectNode request = (ObjectNode) mapper.readTree(valid);
                ((ObjectNode) request.get("environment")).set(field, mapper.readTree(value));
                assertThrows(Exception.class, () -> mapper.treeToValue(request, ActorScriptRequest.class), field + "=" + value);
            }
        }
        for (String field : List.of("options", "args")) {
            for (String value : field.equals("options") ? List.of("null", "[]", "1", "true", "\"text\"")
                    : List.of("null", "{}", "1", "true", "\"text\"")) {
                ObjectNode request = (ObjectNode) mapper.readTree(valid);
                ((ObjectNode) request.get("environment")).set(field, mapper.readTree(value));
                assertThrows(Exception.class, () -> mapper.treeToValue(request, ActorScriptRequest.class), field + "=" + value);
            }
        }
        for (String field : List.of("kind", "operation")) {
            ObjectNode missing = (ObjectNode) mapper.readTree(valid);
            ((ObjectNode) missing.get("environment")).remove(field);
            assertThrows(Exception.class, () -> mapper.treeToValue(missing, ActorScriptRequest.class));
            ObjectNode explicitNull = (ObjectNode) mapper.readTree(valid);
            ((ObjectNode) explicitNull.get("environment")).putNull(field);
            assertThrows(Exception.class, () -> mapper.treeToValue(explicitNull, ActorScriptRequest.class));
        }
        ObjectNode unknown = (ObjectNode) mapper.readTree(valid);
        ((ObjectNode) unknown.get("environment")).put("arbitrary_target", 73);
        assertThrows(Exception.class, () -> mapper.treeToValue(unknown, ActorScriptRequest.class));
        for (String value : List.of("[]", "1", "true", "\"npc\"")) {
            ObjectNode request = (ObjectNode) mapper.readTree(valid);
            request.set("environment", mapper.readTree(value));
            assertThrows(Exception.class, () -> mapper.treeToValue(request, ActorScriptRequest.class));
        }
    }

    @Test
    void nativeCancellationForwardsTheExactEnvironmentAndControllerKeepsItsOwnerAndRequestId() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ActorScriptCancelRequest cancel = mapper.readValue("""
                {"session_id":"session","expected_generation":7,"target_request_id":"original-native",
                 "parameters":{"selection":3},"timeout_ms":2000,
                 "environment":{"kind":"quest","operation":"invoke","context_id":"quest-context",
                    "options":{"quest_id":1000},"entry":"end","args":[1,0,3]}}
                """, ActorScriptCancelRequest.class);
        ActorScriptRequest invocation = cancel.invocation();
        assertEquals("", invocation.source());
        assertEquals(cancel.environment(), invocation.environment());
        assertEquals(Map.of("selection", 3), invocation.parameters());
        assertEquals(2000L, invocation.timeoutMs());
        assertEquals("quest-context", invocation.environment().contextId());
        assertEquals(List.of(1, 0, 3), invocation.environment().args());

        ActorControlService service = mock(ActorControlService.class);
        ActorControlController controller = new ActorControlController(service);
        authenticate("native-admin", "ROLE_ADMIN");
        when(service.scriptRun("native-admin", "original-native", invocation)).thenReturn(Map.of("kind", "SCRIPT", "status", "SUCCEEDED"));
        when(service.scriptCancel("native-admin", "native-cancel", cancel)).thenReturn(Map.of("kind", "SCRIPT", "status", "CANCELLED"));
        assertEquals("original-native", controller.scriptRun(new SubmitBody<>("original-native", invocation)).getResponseId());
        assertEquals("native-cancel", controller.scriptCancel(new SubmitBody<>("native-cancel", cancel)).getResponseId());
        verify(service).scriptRun("native-admin", "original-native", invocation);
        verify(service).scriptCancel("native-admin", "native-cancel", cancel);
    }

    @Test
    void originalScriptConstructorsRetainTheirPureScriptDefaultsAndNoEnvironment() throws Exception {
        ActorScriptRequest original = new ActorScriptRequest("session", 7L, "1+1", null, null);
        assertNull(original.environment());
        assertEquals(Map.of(), original.parameters());
        assertEquals(1000L, original.timeoutMs());
        ActorScriptCancelRequest cancel = new ActorScriptCancelRequest("session", 7L, "original", "1+1", null, null);
        assertEquals(original, cancel.invocation());
        ObjectMapper mapper = new ObjectMapper();
        ActorScriptRequest pureNullSource = mapper.readValue("{\"session_id\":\"session\",\"expected_generation\":7}", ActorScriptRequest.class);
        assertNull(pureNullSource.source(), "the service still rejects missing source for a pure script");
        assertNull(pureNullSource.environment());
    }

    private static ObjectNode npcActionJson(ObjectMapper mapper, String action) throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree(actionJson());
        body.put("action", action);
        body.set("parameters", mapper.createObjectNode());
        return body;
    }

    private static String actionJson() {
        return "{\"session_id\":\"session-id\",\"expected_generation\":7,\"action\":\"chat\","
                + "\"parameters\":{\"text\":\"聊天验收 abc123\"},\"expected_context\":{"
                + "\"scene_ref\":\"scene-id\",\"runtime_generation\":\"runtime-id\"}}";
    }

    private static void authenticate(String name, String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                name, "not-returned", List.of(new SimpleGrantedAuthority(authority))));
    }
}
