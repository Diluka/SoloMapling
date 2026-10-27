package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gms.soloacceptance.ObservationException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActorProfilesTest {
    @Test
    void bindsOnlyExplicitLocalProfilesWithDefaultCoordinatesAndNoCreateByDefault() {
        ActorProfiles profiles = new Binder(new MapConfigurationPropertySource(Map.of(
                "solomapling.acceptance.actor-profiles.primary.account-name", "mcp_test",
                "solomapling.acceptance.actor-profiles.primary.account-password", "private-test-value",
                "solomapling.acceptance.actor-profiles.primary.character-name", "McpTest")))
                .bind("solomapling.acceptance", Bindable.of(ActorProfiles.class)).get();

        ActorProfiles.Resolved result = profiles.resolve("primary", null, null);

        assertEquals("primary", result.profileId());
        assertEquals("mcp_test", result.accountName());
        assertEquals("McpTest", result.characterName());
        assertEquals(0, result.world());
        assertEquals(1, result.channel());
        assertFalse(result.allowCreate());
        assertEquals("private-test-value", result.accountPassword());
    }

    @Test
    void rejectsMissingOrInvalidProfilesWithFixedReasons() {
        ActorProfiles profiles = configured();
        assertReason("invalid_profile_id", HttpStatus.BAD_REQUEST,
                () -> profiles.resolve("../../private", null, null));
        assertReason("invalid_profile_id", HttpStatus.BAD_REQUEST,
                () -> profiles.resolve(null, null, null));
        assertReason("profile_not_configured", HttpStatus.NOT_FOUND,
                () -> profiles.resolve("unknown", null, null));
        profiles.setActorProfiles(null);
        assertReason("profile_not_configured", HttpStatus.NOT_FOUND,
                () -> profiles.resolve("primary", null, null));
    }

    @Test
    void cannotRequestAnotherWorldOrChannel() {
        ActorProfiles profiles = configured();
        assertEquals(0, profiles.resolve("primary", 0, 1).world());
        assertReason("profile_world_mismatch", HttpStatus.BAD_REQUEST,
                () -> profiles.resolve("primary", 1, 1));
        assertReason("profile_channel_mismatch", HttpStatus.BAD_REQUEST,
                () -> profiles.resolve("primary", 0, 2));
    }

    @Test
    void restrictsAccountNamespaceAndNativeColumnWidthWithoutEchoingCredentials() throws Exception {
        ActorProfiles profiles = configured();
        ActorProfiles.Profile profile = profiles.getActorProfiles().get("primary");
        for (String unsafe : new String[]{"admin", "MCP_test", "mcp_", "mcp_overlength", "mcp_has space"}) {
            profile.setAccountName(unsafe);
            assertReason("invalid_actor_profile", HttpStatus.BAD_REQUEST,
                    () -> profiles.resolve("primary", null, null));
        }
        profile.setAccountName("mcp_test");
        profile.setAccountPassword(" ");
        assertReason("invalid_actor_profile", HttpStatus.BAD_REQUEST,
                () -> profiles.resolve("primary", null, null));
        profile.setAccountPassword("private-test-value");
        ActorProfiles.Resolved resolved = profiles.resolve("primary", null, null);
        ObjectMapper mapper = new ObjectMapper();
        assertFalse(resolved.toString().contains("private-test-value"));
        assertFalse(mapper.writeValueAsString(resolved).contains("private-test-value"));
        assertFalse(mapper.writeValueAsString(profile).contains("private-test-value"));
    }

    @Test
    void rejectsAccountAliasesBecauseNativeAccountsHaveOneOnlineOwner() {
        ActorProfiles profiles = configured();
        ActorProfiles.Profile duplicate = profile();
        duplicate.setAccountName("mcp_TEST");
        duplicate.setCharacterName("AnotherTest");
        profiles.getActorProfiles().put("alias", duplicate);
        assertReason("profile_account_ambiguous", HttpStatus.BAD_REQUEST,
                () -> profiles.resolve("primary", null, null));
    }

    @Test
    void bothAcceptanceAndActorFlagsMustBeExplicitlyEnabled() {
        for (boolean base : new boolean[]{false, true}) {
            for (boolean actors : new boolean[]{false, true}) {
                try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                            "solomapling.acceptance.enabled", Boolean.toString(base),
                            "solomapling.acceptance.actors-enabled", Boolean.toString(actors))));
                    context.register(ActorProfiles.class);
                    context.refresh();
                    assertEquals(base && actors, !context.getBeansOfType(ActorProfiles.class).isEmpty());
                }
            }
        }
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ActorProfiles.class);
            context.refresh();
            assertTrue(context.getBeansOfType(ActorProfiles.class).isEmpty());
        }
    }

    private static ActorProfiles configured() {
        ActorProfiles profiles = new ActorProfiles();
        profiles.getActorProfiles().put("primary", profile());
        return profiles;
    }

    private static ActorProfiles.Profile profile() {
        ActorProfiles.Profile profile = new ActorProfiles.Profile();
        profile.setAccountName("mcp_test");
        profile.setAccountPassword("private-test-value");
        profile.setCharacterName("McpTest");
        return profile;
    }

    private static void assertReason(String reason, HttpStatus status, Runnable operation) {
        ObservationException failure = assertThrows(ObservationException.class, operation::run);
        assertEquals(reason, failure.getMessage());
        assertEquals(status, failure.status());
    }
}
