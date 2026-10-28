package org.gms.soloacceptance.command;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.dao.entity.AccountsDO;
import org.gms.dao.entity.CharactersDO;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.service.AccountService;
import org.gms.service.CharacterService;
import org.gms.soloacceptance.actor.ActorProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import soloMapling.beidou.BotRegistry;
import soloMapling.game.command.NativeCommandExecutor;

import java.util.Objects;

/** Resolves an eligible native target, then calls the same executor exposed to native scripts. */
@Component
@ConditionalOnProperty(prefix = "solomapling.acceptance", name = {
        "enabled", "actors-enabled", "debug-commands-enabled"}, havingValue = "true", matchIfMissing = false)
public final class NativeDebugCommandBackend {
    private final AccountService accounts;
    private final CharacterService characters;

    @Autowired
    public NativeDebugCommandBackend(AccountService accounts, CharacterService characters) {
        this.accounts = accounts;
        this.characters = characters;
    }

    public DebugCommandService.Outcome execute(ActorProfiles.Resolved profile, String command) {
        Target target;
        try {
            target = target(profile);
            requireCurrent(target);
        } catch (Rejected rejected) {
            return failed(rejected.reason);
        } catch (RuntimeException unavailable) {
            return failed("command_validation_unavailable");
        }
        NativeCommandExecutor.Result result = NativeCommandExecutor.execute(target.client(), command);
        return new DebugCommandService.Outcome(result.status(), result.nativeHandlerInvoked(),
                result.nativeHandlerReturned(), result.commandOutcome(), result.reason(), target.characterId(), profile.world());
    }

    private Target target(ActorProfiles.Resolved profile) {
        if (profile == null) throw new Rejected("profile_not_configured");
        AccountsDO account = accounts.findByName(profile.accountName());
        CharactersDO stored = characters.findByName(profile.characterName());
        if (account == null || account.getId() == null || account.getId() <= 0
                || !Objects.equals(profile.accountName(), account.getName())
                || !Objects.equals(account.getWebadmin(), 0) || Boolean.TRUE.equals(account.getBanned())
                || stored == null || stored.getId() == null || stored.getId() <= 0
                || !Objects.equals(stored.getAccountid(), account.getId())
                || !Objects.equals(stored.getName(), profile.characterName())
                || !Objects.equals(stored.getWorld(), profile.world()) || !Objects.equals(stored.getGm(), 0)) {
            throw new Rejected("profile_target_not_eligible");
        }
        Server server = Server.getInstance();
        if (server == null || !server.isOnline()) throw new Rejected("server_offline");
        World world = server.getWorld(profile.world());
        Character character = world == null ? null : world.getPlayerStorage().getCharacterById(stored.getId());
        Client client = character == null ? null : character.getClient();
        Channel channel = client == null || world == null ? null : world.getChannel(client.getChannel());
        Target target = new Target(server, world, channel, character, client, account.getId(), stored.getId(),
                profile.characterName());
        requireCurrent(target);
        return target;
    }

    private void requireCurrent(Target target) {
        Character character = target.character();
        Client client = target.client();
        World world = target.world();
        Channel channel = target.channel();
        if (character == null || client == null || world == null || channel == null
                || Server.getInstance() != target.server() || !target.server().isOnline()
                || target.server().getWorld(world.getId()) != world
                || world.getChannel(channel.getId()) != channel
                || world.getPlayerStorage().getCharacterById(target.characterId()) != character
                || channel.getPlayerStorage().getCharacterById(target.characterId()) != character
                || client.getPlayer() != character || character.getClient() != client
                || character.getId() != target.characterId() || character.getAccountId() != target.accountId()
                || !Objects.equals(character.getName(), target.characterName())
                || client.getAccID() != target.accountId() || character.getWorld() != world.getId()
                || client.getChannel() != channel.getId() || !client.isLoggedIn() || !character.isLoggedInWorld()
                || character.gmLevel() != 0 || BotRegistry.isBot(character)) {
            throw new Rejected("profile_target_not_online");
        }
    }

    private static DebugCommandService.Outcome failed(String reason) {
        return new DebugCommandService.Outcome("FAILED", false, false, "NOT_INVOKED", reason, null, null);
    }

    private record Target(Server server, World world, Channel channel, Character character, Client client,
                          int accountId, int characterId, String characterName) { }

    private static final class Rejected extends RuntimeException {
        final String reason;
        Rejected(String reason) { this.reason = reason; }
    }
}
