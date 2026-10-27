package org.gms.soloacceptance.actor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.gms.soloacceptance.ObservationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Explicit local fixture profiles; resolving one never reads or changes game data. */
@Component
@ConfigurationProperties(prefix = "solomapling.acceptance")
@ConditionalOnProperty(prefix = "solomapling.acceptance", name = {"enabled", "actors-enabled"},
        havingValue = "true", matchIfMissing = false)
public final class ActorProfiles {
    private static final Pattern PROFILE_ID = Pattern.compile("[A-Za-z0-9_-]{1,48}");
    // The native accounts.name column is VARCHAR(13).
    private static final Pattern ACCOUNT_NAME = Pattern.compile("mcp_[A-Za-z0-9_]{1,9}");
    private Map<String, Profile> actorProfiles = new LinkedHashMap<>();

    public Map<String, Profile> getActorProfiles() {
        return actorProfiles;
    }

    public void setActorProfiles(Map<String, Profile> profiles) {
        actorProfiles = profiles == null ? new LinkedHashMap<>() : new LinkedHashMap<>(profiles);
    }

    public Resolved resolve(String profileId, Integer requestedWorld, Integer requestedChannel) {
        if (profileId == null || !PROFILE_ID.matcher(profileId).matches()) {
            throw invalid("invalid_profile_id");
        }
        Profile profile = actorProfiles.get(profileId);
        if (profile == null) {
            throw new ObservationException(HttpStatus.NOT_FOUND, "profile_not_configured");
        }
        if (profile.accountName == null || !ACCOUNT_NAME.matcher(profile.accountName).matches()
                || profile.accountPassword == null || profile.accountPassword.isBlank()
                || profile.characterName == null || profile.characterName.isBlank()
                || profile.world == null || profile.world < 0 || profile.world > 255
                || profile.channel == null || profile.channel < 1 || profile.channel > 255) {
            throw invalid("invalid_actor_profile");
        }
        for (Map.Entry<String, Profile> entry : actorProfiles.entrySet()) {
            Profile other = entry.getValue();
            if (!profileId.equals(entry.getKey()) && other != null
                    && profile.accountName.equalsIgnoreCase(other.accountName)) {
                throw invalid("profile_account_ambiguous");
            }
        }
        if (requestedWorld != null && !requestedWorld.equals(profile.world)) {
            throw invalid("profile_world_mismatch");
        }
        if (requestedChannel != null && !requestedChannel.equals(profile.channel)) {
            throw invalid("profile_channel_mismatch");
        }
        return new Resolved(profileId, profile.accountName, profile.accountPassword,
                profile.characterName, profile.world, profile.channel, profile.allowCreate);
    }

    private static ObservationException invalid(String reason) {
        return new ObservationException(HttpStatus.BAD_REQUEST, reason);
    }

    public record Resolved(String profileId, String accountName, @JsonIgnore String accountPassword,
                           String characterName, int world, int channel, boolean allowCreate) {
        @Override
        public String toString() {
            return "ResolvedActorProfile[profileId=" + profileId + ", world=" + world + ", channel=" + channel + "]";
        }
    }

    public static final class Profile {
        private String accountName;
        private String accountPassword;
        private String characterName;
        private Integer world = 0;
        private Integer channel = 1;
        private boolean allowCreate;

        public String getAccountName() { return accountName; }
        public void setAccountName(String value) { accountName = value; }
        @JsonIgnore
        public String getAccountPassword() { return accountPassword; }
        public void setAccountPassword(String value) { accountPassword = value; }
        public String getCharacterName() { return characterName; }
        public void setCharacterName(String value) { characterName = value; }
        public Integer getWorld() { return world; }
        public void setWorld(Integer value) { world = value; }
        public Integer getChannel() { return channel; }
        public void setChannel(Integer value) { channel = value; }
        public boolean isAllowCreate() { return allowCreate; }
        public void setAllowCreate(boolean value) { allowCreate = value; }
    }
}
