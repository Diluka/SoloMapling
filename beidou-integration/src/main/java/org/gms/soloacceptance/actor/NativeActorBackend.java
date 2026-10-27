package org.gms.soloacceptance.actor;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.DefaultDates;
import org.gms.client.QuestStatus;
import org.gms.client.creator.novice.BeginnerCreator;
import org.gms.client.inventory.InventoryType;
import org.gms.config.GameConfig;
import org.gms.constants.id.MapId;
import org.gms.constants.string.CharsetConstants;
import org.gms.dao.entity.AccountsDO;
import org.gms.dao.entity.CharactersDO;
import org.gms.model.dto.AddAccountDTO;
import org.gms.net.PacketHandler;
import org.gms.net.packet.ByteBufInPacket;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.channel.handlers.PlayerLoggedinHandler;
import org.gms.net.server.coordinator.session.Hwid;
import org.gms.net.server.coordinator.session.SessionCoordinator;
import org.gms.net.server.handlers.login.CharSelectedHandler;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import org.gms.service.AccountService;
import org.gms.service.CharacterService;
import org.gms.service.NoteService;
import org.gms.soloacceptance.ObservationException;
import org.gms.util.ThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import soloMapling.beidou.BotRegistry;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Uses native accounts, character creation, login handlers and logout persistence. */
@Component
@ConditionalOnProperty(prefix = "solomapling.acceptance", name = {"enabled", "actors-enabled"},
        havingValue = "true", matchIfMissing = false)
public final class NativeActorBackend {
    private static final AtomicLong SESSION_IDS = new AtomicLong(Long.MIN_VALUE);
    private static final long CLEANUP_TIMEOUT_NANOS = 15_000_000_000L;
    private static final InventoryType[] INVENTORIES = {InventoryType.EQUIPPED, InventoryType.EQUIP,
            InventoryType.USE, InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH};
    private final ActorProfiles profiles;
    private final AccountService accounts;
    private final CharacterService characters;
    private final NoteService notes;
    private final Supplier<Server> servers;
    private final long cleanupTimeoutNanos;

    @Autowired
    public NativeActorBackend(ActorProfiles profiles, AccountService accounts,
                              CharacterService characters, NoteService notes) {
        this(profiles, accounts, characters, notes, Server::getInstance);
    }

    NativeActorBackend(ActorProfiles profiles, AccountService accounts,
                       CharacterService characters, NoteService notes, Supplier<Server> servers) {
        this(profiles, accounts, characters, notes, servers, CLEANUP_TIMEOUT_NANOS);
    }

    NativeActorBackend(ActorProfiles profiles, AccountService accounts,
                       CharacterService characters, NoteService notes, Supplier<Server> servers,
                       long cleanupTimeoutNanos) {
        this.profiles = profiles;
        this.accounts = accounts;
        this.characters = characters;
        this.notes = notes;
        this.servers = servers;
        this.cleanupTimeoutNanos = cleanupTimeoutNanos;
    }

    public void validateProfile(String profileId, Integer world, Integer channel) {
        profiles.resolve(profileId, world, channel);
    }

    public NativeActorHandle open(String profileId, Integer worldId, Integer channelId, BooleanSupplier cancelled) {
        ActorProfiles.Resolved profile = profiles.resolve(profileId, worldId, channelId);
        checkCancelled(cancelled);
        Server server = servers.get();
        World world = server.getWorld(profile.world());
        Channel channel = world == null ? null : world.getChannel(profile.channel());
        if (world == null || channel == null) throw unavailable("actor_world_unavailable");
        if (world.isWorldCapacityFull()) throw conflict("actor_world_full");

        AccountsDO account = accounts.findByName(profile.accountName());
        boolean accountCreated = false;
        if (account == null) {
            if (!profile.allowCreate()) throw missing("actor_account_missing");
            AddAccountDTO data = new AddAccountDTO();
            data.setName(profile.accountName());
            data.setPassword(profile.accountPassword());
            data.setBirthday(Date.valueOf(DefaultDates.getBirthday()));
            data.setLanguage(3);
            try {
                accounts.addAccount(data);
            } catch (Exception failure) {
                // A partial creation is a retained fixture, never a reason to delete data.
                throw unavailable("actor_account_creation_failed");
            }
            accountCreated = true;
            account = accounts.findByName(profile.accountName());
        }
        validateAccount(account, profile);
        final int accountId = account.getId();
        requireAccountOffline(server, accountId);
        checkCancelled(cancelled);

        String remote = remoteAddress(accountId);
        Hwid hwid = new Hwid(String.format("%08X", accountId));
        AcceptanceClient login = client(Client.Type.LOGIN, remote, world, channel, accountId, profile.accountName());
        ActorAccountOwnership.Lease ownership = ActorAccountOwnership.claim(accountId);
        AcceptanceClient active = login;
        NativeActorHandle handle = null;
        boolean loginOwned = false;
        try {
            ActorAccountOwnership.authorize(ownership, login);
            requireAccountOffline(server, accountId);
            int result = login.login(profile.accountName(), profile.accountPassword(), hwid);
            if (login.hasBannedIP() || login.hasBannedMac() || login.hasBannedHWID()) {
                throw conflict("actor_account_banned");
            }
            Calendar tempban = login.getTempBanCalendarFromDB();
            if (tempban != null && tempban.getTimeInMillis() > System.currentTimeMillis()) {
                throw conflict("actor_account_tempbanned");
            }
            // Explicit fixture bootstrap permission also resumes an interrupted first login.
            // Native login already verified the configured password before returning 23.
            if (result == 23 && profile.allowCreate()) {
                if (login.acceptToS()) throw conflict("actor_terms_rejected");
                result = login.login(profile.accountName(), profile.accountPassword(), hwid);
            }
            if (result != 0) throw conflict("actor_native_login_rejected");
            requireAccountOffline(server, accountId);
            if (login.finishLogin() != 0) throw conflict("actor_native_login_conflict");
            loginOwned = true;
            if (!login.isLoggedIn() || !loggedIn(accountId, Client.LOGIN_LOGGEDIN)) {
                throw unavailable("actor_login_state_unverified");
            }
            server.registerLoginState(login);
            server.loadAccountCharacters(login);
            checkCancelled(cancelled);

            CharactersDO stored = characters.findByName(profile.characterName());
            boolean characterCreated = false;
            if (stored == null) {
                if (!profile.allowCreate()) throw missing("actor_character_missing");
                if (!GameConfig.getServerBoolean("enable_adventurers")) {
                    throw conflict("actor_native_creation_disabled");
                }
                int created = withClient(login, () -> BeginnerCreator.createCharacter(login, profile.characterName(),
                        20000, 30000, 0, 1040002, 1060002, 1072001, 1302000, 0));
                if (created != 0) throw conflict("actor_native_creation_rejected");
                characterCreated = true;
                stored = characters.findByName(profile.characterName());
            }
            validateCharacter(stored, profile, accountId);
            checkCancelled(cancelled);
            final int characterId = stored.getId();
            dispatch(new CharSelectedHandler(), login, packet -> {
                packet.writeIntLE(characterId);
                writeString(packet, login, "02-00-00-00-00-01");
                writeString(packet, login, "020000000001_" + hwid.hwid());
            });
            if (!login.isInTransition() || !loggedIn(accountId, Client.LOGIN_SERVER_TRANSITION)) {
                throw unavailable("actor_native_transition_rejected");
            }
            SessionCoordinator.getInstance().closeLoginSession(login);
            login.closeSession();
            active = client(Client.Type.CHANNEL, remote, world, channel, accountId, profile.accountName());
            ActorAccountOwnership.authorize(ownership, active);
            dispatch(new PlayerLoggedinHandler(notes), active, packet -> packet.writeIntLE(characterId));
            Character character = active.getPlayer();
            if (character == null) throw unavailable("actor_native_entry_rejected");
            handle = new NativeActorHandle(profileId, accountId, active, character, world, channel, ownership,
                    accountCreated, characterCreated);
            if (!isCurrent(handle) || character.getId() != characterId || character.gmLevel() != 0
                    || BotRegistry.isBot(character) || !loggedIn(accountId, Client.LOGIN_LOGGEDIN)) {
                throw unavailable("actor_native_entry_unverified");
            }
            checkCancelled(cancelled);
            return handle;
        } catch (RuntimeException failure) {
            try {
                if (handle != null) {
                    close(handle);
                } else if (loginOwned || ActorAccountOwnership.currentOnlineClient(accountId) == login
                        || ActorAccountOwnership.currentOnlineClient(accountId) == active) {
                    cleanupFailedOpen(server, login, active, accountId);
                    releaseOwnership(ownership);
                } else {
                    // Never log off an account merely because a rejected login populated accID.
                    SessionCoordinator.getInstance().closeLoginSession(login);
                    login.closeSession();
                    if (!loggedIn(accountId, Client.LOGIN_NOTLOGGEDIN)
                            || hasForeignOnlineCharacter(server, accountId, null)) {
                        throw unavailable("actor_open_cleanup_unverified_rejected_login");
                    }
                    releaseOwnership(ownership);
                }
            } catch (RuntimeException cleanupFailure) {
                throw unavailable("actor_open_cleanup_unverified");
            }
            throw failure;
        }
    }

    public Map<String, Object> snapshot(NativeActorHandle handle) {
        if (!isCurrent(handle)) throw conflict("actor_session_stale");
        Character character = handle.character();
        Map<String, Object> result = identity(handle);
        result.put("name", character.getName());
        result.put("world_id", handle.world().getId());
        result.put("channel_id", handle.channel().getId());
        result.put("map_id", character.getMapId());
        result.put("position", Map.of("x", character.getPosition().x, "y", character.getPosition().y));
        result.put("level", character.getLevel());
        result.put("exp", character.getExp());
        result.put("hp", character.getHp());
        result.put("mp", character.getMp());
        result.put("meso", character.getMeso());
        result.put("job_id", character.getJob().getId());
        result.put("guild_id", character.getGuildId());
        result.put("party_id", character.getParty() == null ? null : character.getParty().getId());
        result.put("logged_in", character.isLoggedIn());
        result.put("client_logged_in", handle.client().isLoggedIn());
        result.put("logged_in_world", character.isLoggedInWorld());
        result.put("is_current", true);
        result.put("persisted", matchingIdentity(characters.findById(character.getId()), handle));
        result.put("is_bot", BotRegistry.isBot(character));
        result.put("inventory_summary", inventorySummary(character));
        result.put("quest_summary", questSummary(character));
        result.put("packet_events", handle.client().packetSummaries());
        result.put("packet_events_dropped", handle.client().droppedPacketCount());
        result.put("malformed_packet_events", handle.client().malformedPacketCount());
        return result;
    }

    public boolean isCurrent(NativeActorHandle handle) {
        if (handle == null || handle.closeEvidence() != null) return false;
        Server server = servers.get();
        World world = server.getWorld(handle.world().getId());
        if (world != handle.world() || world.getChannel(handle.channel().getId()) != handle.channel()) return false;
        Character character = handle.character();
        MapleMap map = character.getMap();
        return character.getClient() == handle.client() && handle.client().getPlayer() == character
                && ActorAccountOwnership.owns(handle.ownership(), handle.client())
                && ActorAccountOwnership.currentOnlineClient(handle.accountId()) == handle.client()
                && handle.client().getWorld() == handle.world().getId()
                && handle.client().getChannel() == handle.channel().getId()
                && handle.client().getAccID() == handle.accountId() && character.getAccountId() == handle.accountId()
                && handle.client().isLoggedIn() && character.isLoggedInWorld() && !handle.client().transportClosed()
                && world.getPlayerStorage().getCharacterById(character.getId()) == character
                && handle.channel().getPlayerStorage().getCharacterById(character.getId()) == character
                && map != null && map.getCharacterById(character.getId()) == character;
    }

    public Map<String, Object> close(NativeActorHandle handle) {
        synchronized (handle) {
            if (handle.closeEvidence() != null) return handle.closeEvidence();
            Character character = handle.character();
            AcceptanceClient client = handle.client();
            NativeActorHandle.CloseContext context = handle.closeContext();
            if (context == null) {
                if (!isCurrent(handle)) throw conflict("actor_close_cleanup_unverified_stale_session");
                // These are native cooperative exits; dropping Java references leaves shared state behind.
                withClient(client, () -> {
                    if (character.getParty() != null) Party.leaveParty(character.getParty(), client);
                    handle.world().getMatchCheckerCoordinator().dismissMatchConfirmation(character.getId());
                    if (character.getEventInstance() != null) character.getEventInstance().exitPlayer(character);
                    return null;
                });
                if (character.getParty() != null || character.getEventInstance() != null) {
                    throw unavailable("actor_close_cleanup_unverified_cooperation");
                }
                context = closeContext(handle, character.getMap());
                handle.beginClose(context);
                client.disconnect(false, false);
            } else {
                requireCloseOwnership(handle);
            }
            NativeActorHandle.CloseContext closing = context;
            await(() -> nativeCleanupComplete(handle, closing), "actor_close_cleanup_unverified");
            client.closeSession();
            CharactersDO stored = characters.findById(closing.characterId());
            if (!matchesPersistedState(closing, stored)) {
                throw unavailable("actor_close_cleanup_unverified_persistence");
            }
            releaseOwnership(handle.ownership());
            Map<String, Object> result = identity(handle);
            result.put("native_cleanup_verified", true);
            result.put("persisted_state_verified", true);
            result.put("persistence_verification_scope", List.of("identity", "level", "exp", "hp", "mp",
                    "meso", "job", "native_saved_map", "party"));
            result.put("fixture_retained", true);
            handle.recordClose(result);
            return handle.closeEvidence();
        }
    }

    private void cleanupFailedOpen(Server server, AcceptanceClient login, AcceptanceClient active, int accountId) {
        if (servers.get().getWorld(login.getWorld()) != login.getWorldServer()) {
            throw unavailable("actor_open_cleanup_unverified_world_replaced");
        }
        if (hasForeignOnlineCharacter(server, accountId, active.getPlayer())) {
            throw unavailable("actor_open_cleanup_unverified_foreign_session");
        }
        Client online = ActorAccountOwnership.currentOnlineClient(accountId);
        if (online != null && online != login && online != active) {
            throw unavailable("actor_open_cleanup_unverified_foreign_owner");
        }
        if (active.getPlayer() != null) {
            active.disconnect(false, false);
        } else {
            // Native handlers may already have queued disconnect. Do not invent a second logout owner.
            server.freeCharacteridInTransition(active);
            active.disconnect(false, false);
        }
        await(() -> loggedIn(accountId, Client.LOGIN_NOTLOGGEDIN)
                && ActorAccountOwnership.currentOnlineClient(accountId) == null
                && !hasForeignOnlineCharacter(server, accountId, null)
                && active.getPlayer() == null && !active.isLoggedIn(), "actor_open_cleanup_unverified");
        SessionCoordinator.getInstance().closeLoginSession(login);
        server.unregisterLoginState(login);
        login.closeSession();
    }

    private void requireCloseOwnership(NativeActorHandle handle) {
        Server server = servers.get();
        Client online = ActorAccountOwnership.currentOnlineClient(handle.accountId());
        if (server.getWorld(handle.world().getId()) != handle.world()
                || handle.world().getChannel(handle.channel().getId()) != handle.channel()
                || !ActorAccountOwnership.owns(handle.ownership(), handle.client())
                || online != null && online != handle.client()) {
            throw conflict("actor_close_cleanup_unverified_stale_owner");
        }
    }

    private boolean nativeCleanupComplete(NativeActorHandle handle, NativeActorHandle.CloseContext context) {
        requireCloseOwnership(handle);
        Character character = handle.character();
        return loggedIn(handle.accountId(), Client.LOGIN_NOTLOGGEDIN)
                && ActorAccountOwnership.currentOnlineClient(handle.accountId()) == null
                && handle.client().getPlayer() == null && !handle.client().isLoggedIn()
                && !character.isLoggedIn() && !character.isLoggedInWorld()
                && handle.world().getPlayerStorage().getCharacterById(context.characterId()) == null
                && handle.channel().getPlayerStorage().getCharacterById(context.characterId()) == null
                && (context.lastMap() == null || context.lastMap().getCharacterById(context.characterId()) == null)
                && handle.world().getMatchCheckerCoordinator().getMatchConfirmationLeaderid(context.characterId()) == -1;
    }

    boolean matchesPersistedState(NativeActorHandle handle, CharactersDO stored, MapleMap map) {
        return matchesPersistedState(closeContext(handle, map), stored);
    }

    private static NativeActorHandle.CloseContext closeContext(NativeActorHandle handle, MapleMap map) {
        Character character = handle.character();
        int nativeSavedMap = map == null ? character.getMapId()
                : map.getForcedReturnId() != MapId.NONE ? map.getForcedReturnId()
                : character.getHp() < 1 ? map.getReturnMapId() : map.getId();
        return new NativeActorHandle.CloseContext(map, character.getId(), handle.accountId(), handle.world().getId(),
                character.getName(), character.getLevel(), Math.abs(character.getExp()), character.getHp(),
                character.getMp(), character.getMeso(), character.getJob().getId(), nativeSavedMap);
    }

    private static boolean matchesPersistedState(NativeActorHandle.CloseContext expected, CharactersDO stored) {
        return stored != null && Objects.equals(stored.getId(), expected.characterId())
                && Objects.equals(stored.getAccountid(), expected.accountId())
                && Objects.equals(stored.getWorld(), expected.worldId())
                && Objects.equals(stored.getName(), expected.name()) && Objects.equals(stored.getGm(), 0)
                && Objects.equals(stored.getLevel(), expected.level()) && Objects.equals(stored.getExp(), expected.exp())
                && Objects.equals(stored.getHp(), expected.hp()) && Objects.equals(stored.getMp(), expected.mp())
                && Objects.equals(stored.getMeso(), expected.meso()) && Objects.equals(stored.getJob(), expected.jobId())
                && Objects.equals(stored.getMap(), expected.nativeSavedMap())
                && Objects.equals(stored.getParty(), -1);
    }

    private static void validateAccount(AccountsDO account, ActorProfiles.Resolved profile) {
        if (account == null || account.getId() == null || account.getId() <= 0
                || !profile.accountName().equals(account.getName())
                || account.getWebadmin() == null || account.getWebadmin() != 0
                || Boolean.TRUE.equals(account.getBanned())) throw conflict("actor_account_not_eligible");
    }

    private static void validateCharacter(CharactersDO stored, ActorProfiles.Resolved profile, int accountId) {
        if (stored == null || stored.getId() == null || stored.getId() <= 0
                || !Objects.equals(stored.getAccountid(), accountId)
                || !Objects.equals(stored.getWorld(), profile.world())
                || !profile.characterName().equals(stored.getName()) || !Objects.equals(stored.getGm(), 0)) {
            throw conflict("actor_character_not_eligible");
        }
    }

    private void requireAccountOffline(Server server, int accountId) {
        if (!loggedIn(accountId, Client.LOGIN_NOTLOGGEDIN) || hasForeignOnlineCharacter(server, accountId, null)) {
            throw conflict("actor_account_in_use");
        }
    }

    private static boolean hasForeignOnlineCharacter(Server server, int accountId, Character owned) {
        return server.getWorlds().stream().anyMatch(world -> world.getPlayerStorage().getAllCharacters().stream()
                .anyMatch(character -> character.getAccountId() == accountId && character != owned));
    }

    private boolean loggedIn(int accountId, int state) {
        AccountsDO account = accounts.findById(accountId);
        return account != null && Objects.equals(account.getLoggedin(), state);
    }

    private static boolean matchingIdentity(CharactersDO stored, NativeActorHandle handle) {
        return stored != null && Objects.equals(stored.getId(), handle.character().getId())
                && Objects.equals(stored.getAccountid(), handle.accountId())
                && Objects.equals(stored.getWorld(), handle.world().getId())
                && Objects.equals(stored.getName(), handle.character().getName()) && Objects.equals(stored.getGm(), 0);
    }

    private static Map<String, Object> identity(NativeActorHandle handle) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile_id", handle.profileId());
        result.put("account_id", handle.accountId());
        result.put("character_id", handle.character().getId());
        result.put("account_created", handle.accountCreated());
        result.put("character_created", handle.characterCreated());
        return result;
    }

    private static Map<String, Object> inventorySummary(Character character) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (InventoryType type : INVENTORIES) {
            var items = character.getInventory(type).list().stream()
                    .sorted(Comparator.comparingInt(item -> item.getPosition())).toList();
            List<Map<String, Object>> summary = items.stream().limit(100).map(item -> Map.<String, Object>of(
                    "item_id", item.getItemId(), "position", (int) item.getPosition(), "quantity", (int) item.getQuantity())).toList();
            result.put(type.name().toLowerCase(java.util.Locale.ROOT), Map.of("items", summary,
                    "count", items.size(), "truncated", items.size() > 100));
        }
        return result;
    }

    private static Map<String, Object> questSummary(Character character) {
        List<QuestStatus> quests = new ArrayList<>(character.getStartedQuests());
        quests.addAll(character.getCompletedQuests());
        quests.sort(Comparator.comparingInt(quest -> Short.toUnsignedInt(quest.getQuestID())));
        List<Map<String, Object>> summary = quests.stream().limit(100).map(quest -> Map.<String, Object>of(
                "quest_id", Short.toUnsignedInt(quest.getQuestID()), "status", quest.getStatus().getId())).toList();
        return Map.of("quests", summary, "count", quests.size(), "truncated", quests.size() > 100);
    }

    private static AcceptanceClient client(Client.Type type, String remote, World world, Channel channel,
                                            int accountId, String name) {
        AcceptanceClient client = new AcceptanceClient(type, SESSION_IDS.incrementAndGet(), remote, world, channel);
        client.setAccID(accountId);
        client.setAccountName(name);
        client.setLanguage(3);
        return client;
    }

    static String remoteAddress(int accountId) {
        return "127." + ((accountId >>> 16) & 255) + "." + ((accountId >>> 8) & 255) + "." + (accountId & 255);
    }

    private static void dispatch(PacketHandler handler, AcceptanceClient client, Consumer<ByteBuf> writer) {
        ByteBuf packet = Unpooled.buffer();
        try {
            writer.accept(packet);
            withClient(client, () -> {
                if (!handler.validateState(client)) throw conflict("actor_native_packet_state_rejected");
                handler.handlePacket(new ByteBufInPacket(packet), client);
                return null;
            });
        } finally {
            packet.release();
        }
    }

    private static void writeString(ByteBuf packet, Client client, String value) {
        byte[] bytes = value.getBytes(CharsetConstants.getCharset(client.getLanguage()));
        packet.writeShortLE(bytes.length).writeBytes(bytes);
    }

    private static <T> T withClient(Client client, Supplier<T> action) {
        Client previous = ThreadLocalUtil.getCurrentClient();
        ThreadLocalUtil.setCurrentClient(client);
        try { return action.get(); }
        finally {
            if (previous == null) ThreadLocalUtil.removeCurrentClient();
            else ThreadLocalUtil.setCurrentClient(previous);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static void releaseOwnership(ActorAccountOwnership.Lease ownership) {
        if (!ActorAccountOwnership.release(ownership)) throw unavailable("actor_cleanup_unverified_ownership");
    }

    private void await(BooleanSupplier condition, String reason) {
        long deadline = System.nanoTime() + cleanupTimeoutNanos;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw unavailable(reason);
            try { Thread.sleep(25); }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw unavailable(reason);
            }
        }
    }

    private static ObservationException conflict(String reason) { return new ObservationException(HttpStatus.CONFLICT, reason); }
    private static ObservationException missing(String reason) { return new ObservationException(HttpStatus.NOT_FOUND, reason); }
    private static ObservationException unavailable(String reason) { return new ObservationException(HttpStatus.SERVICE_UNAVAILABLE, reason); }
}
