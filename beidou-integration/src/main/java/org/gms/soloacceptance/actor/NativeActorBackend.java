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
import org.gms.net.server.channel.handlers.EnterMTSHandler;
import org.gms.net.server.channel.handlers.NPCMoreTalkHandler;
import org.gms.net.server.channel.handlers.PlayerLoggedinHandler;
import org.gms.net.server.coordinator.session.Hwid;
import org.gms.net.server.coordinator.session.SessionCoordinator;
import org.gms.net.server.handlers.login.CharSelectedHandler;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.World;
import org.gms.server.maps.MapleMap;
import org.gms.server.CashShop;
import org.gms.scripting.npc.NPCConversationManager;
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
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.action.NativeChatAction;
import soloMapling.game.action.NativeInventoryActions;
import soloMapling.game.action.NativeInventoryMoveAction;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
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
    /** The accepted OPEN work owns the result before native entry validation can fail. */
    public interface OpenOwnership {
        void bindOrigin(World world, Channel channel);
        void adopt(NativeActorHandle handle);
        void rollback(NativeActorHandle handle);
        void confirmAbsent();
        void creationFailed(String fixedReason);
        void requireOrigin(World world, Channel channel);
    }

    private static final class OpenProgress {
        boolean nativeAttempt;
        boolean released;
    }

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
    private final NativeActionService actions = new NativeActionService();
    private final NativeInventoryActions inventoryActions = new NativeInventoryActions();

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

    public void validateChat(NativeActorHandle handle, String text) {
        requireOrdinaryActor(handle);
        try {
            NativeActionService.validateChat(handle.characterRef(), text);
        } catch (NativeActionService.Invalid invalid) {
            throw chatInputFailure(invalid);
        }
    }

    /** One ordinary MCP identity adapter; the shared service owns the sole lane submission. */
    public NativeActionService.ActionHandle prepareChat(NativeActorHandle handle, CharacterCommandLane lane,
                                                        CharacterCommandLane.ControlPermit permit,
                                                        ExecutionScope.WorkHandle work, NativeChatAction action) {
        requireOrdinaryActor(handle);
        Server server = servers.get();
        GameContext.SceneRef scene;
        try {
            scene = NativeContextChecks.captureScene(server, handle.characterRef());
        } catch (GameContext.Invalid invalid) {
            throw chatContextFailure(invalid);
        }
        return actions.prepareChat(server, lane, permit, work, scene, action,
                AcceptanceChatEvidence.source(handle));
    }

    public NativeActionService.SubmissionReceipt submitChat(NativeActionService.ActionHandle action) {
        return actions.submitChat(action);
    }

    /** The MCP adapter supplies ordinary identity; inventory rules and evidence remain in the shared action. */
    public NativeInventoryActions.MoveHandle prepareInventoryMove(NativeActorHandle handle, CharacterCommandLane lane,
                                                                  CharacterCommandLane.ControlPermit permit,
                                                                  ExecutionScope.WorkHandle work, NativeInventoryMoveAction action) {
        requireOrdinaryActor(handle);
        Server server = servers.get();
        GameContext.SceneRef scene;
        try { scene = NativeContextChecks.captureScene(server, handle.characterRef()); }
        catch (GameContext.Invalid invalid) { throw chatContextFailure(invalid); }
        return inventoryActions.prepareMove(server, lane, permit, work, scene, action);
    }

    public NativeActionService.SubmissionReceipt submitInventoryMove(NativeInventoryActions.MoveHandle action) {
        return inventoryActions.submitMove(action);
    }

    public void validateInventoryMove(NativeActorHandle handle, NativeInventoryMoveAction action) {
        requireOrdinaryActor(handle);
        Objects.requireNonNull(action);
    }

    public Map<String, Object> chatTimingCapabilities() {
        long updateInterval = GameConfig.getServerLong("update_interval");
        if (updateInterval <= 0) throw unavailable("actor_chat_timing_unavailable");
        return Map.of("chat_minimum_interval_ms", 200,
                "native_clock_update_interval_ms", updateInterval);
    }

    private static ObservationException chatInputFailure(NativeActionService.Invalid invalid) {
        return switch (invalid.reason()) {
            case "invalid_chat_text", "chat_text_not_encodable" ->
                    new ObservationException(HttpStatus.BAD_REQUEST, invalid.reason());
            case "actor_native_packet_state_rejected" -> conflict(invalid.reason());
            default -> unavailable("actor_chat_validation_unavailable");
        };
    }

    private static ObservationException chatContextFailure(GameContext.Invalid invalid) {
        return switch (invalid.reason()) {
            case WORLD_OFFLINE -> unavailable("actor_world_offline");
            case WORLD_CHANGED -> new ObservationException(HttpStatus.GONE, "stale_actor_session");
            case RUNTIME_CHANGED -> conflict("runtime_changed");
            case READ_UNAVAILABLE -> unavailable("actor_observation_unavailable");
            case SCENE_CHANGED, EVENT_CHANGED -> conflict("actor_context_mismatch");
            case CHARACTER_CHANGED, NOT_IN_WORLD -> conflict("actor_session_stale");
        };
    }

    public void validateNpcCenterOpen(NativeActorHandle handle) {
        requireOrdinaryActor(handle);
        if (GameConfig.getServerBoolean("use_mts")) throw conflict("native_center_mts_enabled");
        if (handle.client().getCM() != null || handle.client().getQM() != null) {
            throw conflict("native_dialogue_already_active");
        }
    }

    public Map<String, Object> npcCenterOpen(NativeActorHandle handle) {
        validateNpcCenterOpen(handle);
        AcceptanceClient client = handle.client();
        long before = client.latestPacketSequence();
        boolean[] started = {false};
        boolean handlerError = false;
        try {
            client.dispatchNativeCenter(() -> dispatch(new EnterMTSHandler(), client, packet -> { }, () -> {
                validateNpcCenterOpen(handle);
                started[0] = true;
            }));
        } catch (RuntimeException failure) {
            if (!started[0]) throw failure;
            handlerError = true;
        }

        Long after = null;
        Long sequence = null;
        boolean complete = false;
        boolean uncertainPacket = false;
        Boolean active = null;
        boolean verified = false;
        try {
            Map<String, Object> window = client.packetEvents(before, 500);
            after = (Long) window.get("latest_cursor");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = (List<Map<String, Object>>) window.get("items");
            for (Map<String, Object> event : events) {
                long eventSequence = (Long) event.get("sequence");
                if (eventSequence <= before || after == null || eventSequence > after) continue;
                if ("npc_dialogue".equals(event.get("event_type"))
                        && "decoded".equals(event.get("decode_status"))
                        && Integer.valueOf(9900001).equals(event.get("npc_template_id"))
                        && Integer.valueOf(4).equals(event.get("speaker_type"))
                        && Integer.valueOf(4).equals(event.get("message_type"))
                        && Integer.valueOf(0).equals(event.get("speaker"))) sequence = eventSequence;
                if ("malformed".equals(event.get("decode_status"))
                        && (event.get("opcode") == null || Integer.valueOf(0x130).equals(event.get("opcode")))) {
                    uncertainPacket = true;
                }
            }
            complete = after != null && after >= before && Boolean.FALSE.equals(window.get("cursor_expired"))
                    && Boolean.FALSE.equals(window.get("truncated"));
        } catch (RuntimeException ignored) { complete = false; }
        try {
            NPCConversationManager cm = client.getCM();
            boolean noQuest = client.getQM() == null;
            active = cm != null && noQuest;
            if (sequence != null && Boolean.TRUE.equals(active)) {
                client.requireNativeCenterDialogue(sequence);
                verified = client.getCM() == cm && client.getQM() == null && cm.getNpc() == 9900001
                        && cm.getNextLevelContext().getLevelType() == null;
            }
        } catch (RuntimeException ignored) { active = null; }
        after = latestPacketCursor(client, after);
        String status = verified ? "SUCCEEDED" : handlerError || !complete || uncertainPacket || active == null
                ? "INDETERMINATE" : "FAILED";
        String outcome = verified ? "center_dialogue_opened" : "INDETERMINATE".equals(status)
                ? "native_center_outcome_unknown" : "native_center_not_opened";
        return npcOutcome(status, outcome, started[0], sequence, active, false, before, after, handlerError);
    }

    public void validateNpcCancel(NativeActorHandle handle, long version) {
        requireOrdinaryActor(handle);
        handle.client().validateNativeDialogueCancel(version);
    }

    public Map<String, Object> npcCancel(NativeActorHandle handle, long version) {
        requireOrdinaryActor(handle);
        AcceptanceClient client = handle.client();
        AcceptanceClient.NativeDialogueCommand command = client.validateNativeDialogueCancel(version);
        NPCConversationManager original = client.getCM();
        long before = client.latestPacketSequence();
        boolean[] started = {false};
        boolean handlerError = false;
        boolean dispatchError = false;
        try {
            client.dispatchNativeDialogue(command, () -> dispatch(new NPCMoreTalkHandler(), client,
                    packet -> packet.writeByte(command.messageType()).writeByte(command.action()), () -> {
                requireOrdinaryActor(handle);
                if (client.getCM() != original) throw conflict("dialogue_not_active");
                started[0] = true;
            }));
        } catch (RuntimeException failure) {
            if (!started[0] && !command.consumed()) throw failure;
            handlerError = started[0];
            dispatchError = true;
        }
        Boolean active = null;
        boolean cleaned = false;
        boolean sameDialogue = false;
        try {
            NPCConversationManager cm = client.getCM();
            boolean noQuest = client.getQM() == null;
            cleaned = cm == null && noQuest;
            sameDialogue = cm == original && noQuest;
            active = cm != null && noQuest;
            if (cleaned) client.invalidateDialogue();
        } catch (RuntimeException ignored) { active = null; }
        boolean cancelled = started[0] && cleaned;
        String status = cancelled ? "SUCCEEDED" : dispatchError || active == null || !sameDialogue ? "INDETERMINATE" : "FAILED";
        String outcome = cancelled ? "dialogue_cancelled" : "INDETERMINATE".equals(status)
                ? "native_dialogue_outcome_unknown" : "native_dialogue_not_cancelled";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("message_type", command.messageType());
        metadata.put("reply_version_consumed", command.consumed());
        metadata.put("dialogue_path", cancelled ? "closed" : null);
        return npcMetadata(npcOutcome(status, outcome, started[0], version, active, cleaned,
                before, latestPacketCursor(client, null), handlerError), metadata);
    }

    public void validateNpcReply(NativeActorHandle handle, long version, String kind, Integer selection) {
        requireOrdinaryActor(handle);
        handle.client().validateNativeDialogueReply(version, kind, selection);
    }

    public Map<String, Object> npcReply(NativeActorHandle handle, long version, String kind, Integer selection) {
        requireOrdinaryActor(handle);
        AcceptanceClient client = handle.client();
        AcceptanceClient.NativeDialogueCommand command = client.validateNativeDialogueReply(version, kind, selection);
        long before = client.latestPacketSequence();
        boolean[] started = {false};
        boolean handlerError = false;
        boolean dispatchError = false;
        try {
            client.dispatchNativeDialogue(command, () -> dispatch(new NPCMoreTalkHandler(), client, packet -> {
                packet.writeByte(command.messageType()).writeByte(command.action());
                if (command.selection() != null) packet.writeIntLE(command.selection());
            }, () -> {
                requireOrdinaryActor(handle);
                started[0] = true;
            }));
        } catch (RuntimeException failure) {
            if (!started[0] && !command.consumed()) throw failure;
            handlerError = started[0];
            dispatchError = true;
        }

        Long after = null;
        Long sequence = null;
        Integer messageType = null;
        Boolean active = null;
        boolean cleaned = false;
        boolean complete = false;
        boolean malformedNpc = false;
        boolean trusted = false;
        try {
            Map<String, Object> window = client.packetEvents(before, 500);
            after = (Long) window.get("latest_cursor");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = (List<Map<String, Object>>) window.get("items");
            for (Map<String, Object> event : events) {
                long eventSequence = (Long) event.get("sequence");
                if (eventSequence <= before || after == null || eventSequence > after) continue;
                if ("malformed".equals(event.get("decode_status"))
                        && (event.get("opcode") == null || Integer.valueOf(0x130).equals(event.get("opcode")))) {
                    malformedNpc = true;
                }
                if (Integer.valueOf(0x130).equals(event.get("opcode"))) {
                    sequence = eventSequence;
                    messageType = "npc_dialogue".equals(event.get("event_type"))
                            && "decoded".equals(event.get("decode_status"))
                            ? (Integer) event.get("message_type") : null;
                }
            }
            complete = after != null && after >= before && Boolean.FALSE.equals(window.get("cursor_expired"))
                    && Boolean.FALSE.equals(window.get("truncated"));
        } catch (RuntimeException ignored) { complete = false; }
        try {
            NPCConversationManager cm = client.getCM();
            boolean noQuest = client.getQM() == null;
            active = cm != null && noQuest;
            cleaned = cm == null && noQuest;
            if (sequence != null && Boolean.TRUE.equals(active)) {
                Map<String, Object> state = client.dialogueState();
                if (Boolean.TRUE.equals(state.get("active"))
                        && Objects.equals(state.get("dialogue_version"), sequence)
                        && Objects.equals(state.get("path"), command.expectedPath())) {
                    client.validateNativeDialogueCancel(sequence);
                    trusted = client.getCM() == cm && client.getQM() == null;
                }
            }
        } catch (RuntimeException ignored) { active = null; trusted = false; }
        boolean succeeded = started[0] && command.consumed() && sequence != null && complete && !malformedNpc && !dispatchError
                && ("decline".equals(kind)
                    ? cleaned && Objects.equals(sequence, command.closedPacketSequence()) : trusted);
        String outcome = succeeded ? "decline".equals(kind) ? "dialogue_declined" : "dialogue_replied"
                : "native_dialogue_reply_outcome_unknown";
        if (succeeded && cleaned) client.invalidateDialogue();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", kind);
        metadata.put("selection", selection);
        metadata.put("reply_version_consumed", command.consumed());
        metadata.put("dialogue_path", succeeded ? cleaned ? "closed" : command.expectedPath() : null);
        metadata.put("message_type", messageType);
        return npcMetadata(npcOutcome(succeeded ? "SUCCEEDED" : "INDETERMINATE", outcome, started[0],
                sequence, active, cleaned, before, latestPacketCursor(client, after), handlerError), metadata);
    }

    private static Map<String, Object> npcMetadata(Map<String, Object> outcome, Map<String, Object> metadata) {
        @SuppressWarnings("unchecked")
        Map<String, Object> original = (Map<String, Object>) outcome.get("result");
        Map<String, Object> result = new LinkedHashMap<>(original);
        result.putAll(metadata);
        Map<String, Object> response = new LinkedHashMap<>(outcome);
        response.put("result", Collections.unmodifiableMap(result));
        return Collections.unmodifiableMap(response);
    }

    /** Trusted MCP origin adapter retains the same ordinary-role policy as existing actions. */
    void requireCurrentRole(NativeActorHandle handle) { requireOrdinaryActor(handle); }

    private void requireOrdinaryActor(NativeActorHandle handle) {
        if (!isCurrent(handle) || handle.character().gmLevel() != 0 || BotRegistry.isBot(handle.character())) {
            throw conflict("actor_session_stale");
        }
    }

    private static Long latestPacketCursor(AcceptanceClient client, Long observed) {
        if (observed != null) return observed;
        try { return client.latestPacketSequence(); }
        catch (RuntimeException ignored) { return null; }
    }

    private static Map<String, Object> npcOutcome(String status, String outcome, boolean started, Long version,
                                                 Boolean active, boolean cleaned, long before, Long after,
                                                 boolean handlerError) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("observed_outcome", outcome);
        result.put("native_dispatch_started", started);
        result.put("dialogue_version", version);
        result.put("packet_sequence", version);
        result.put("npc_template_id", 9900001);
        result.put("message_type", 4);
        result.put("active", active);
        result.put("native_dialogue_cleanup_verified", cleaned);
        result.put("packet_cursor_before", before);
        result.put("packet_cursor_after", after);
        if (handlerError) result.put("native_handler_error", true);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("error_reason", "SUCCEEDED".equals(status) ? null : outcome);
        response.put("result", Collections.unmodifiableMap(result));
        return Collections.unmodifiableMap(response);
    }

    public NativeActorHandle open(String profileId, Integer worldId, Integer channelId, BooleanSupplier cancelled,
                                  OpenOwnership ownership) {
        Objects.requireNonNull(ownership, "open ownership");
        OpenProgress progress = new OpenProgress();
        try {
            return openOwned(profileId, worldId, channelId, cancelled, ownership, progress);
        } catch (RuntimeException failure) {
            if (!progress.released) {
                if (progress.nativeAttempt) ownership.creationFailed("actor_open_cleanup_unverified");
                else ownership.confirmAbsent();
            }
            throw failure;
        }
    }

    private NativeActorHandle openOwned(String profileId, Integer worldId, Integer channelId,
                                        BooleanSupplier cancelled, OpenOwnership openOwnership,
                                        OpenProgress progress) {
        ActorProfiles.Resolved profile = profiles.resolve(profileId, worldId, channelId);
        checkCancelled(cancelled);
        Server server = servers.get();
        World world = server.getWorld(profile.world());
        Channel channel = world == null ? null : world.getChannel(profile.channel());
        if (world == null || channel == null) throw unavailable("actor_world_unavailable");
        if (world.isWorldCapacityFull()) throw conflict("actor_world_full");
        openOwnership.bindOrigin(world, channel);

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
        progress.nativeAttempt = true;
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
            prepareCharacterViews(server, login, world);
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
            openOwnership.adopt(handle);
            if (!isCurrent(handle) || character.getId() != characterId || character.gmLevel() != 0
                    || BotRegistry.isBot(character) || !loggedIn(accountId, Client.LOGIN_LOGGEDIN)) {
                throw unavailable("actor_native_entry_unverified");
            }
            checkCancelled(cancelled);
            return handle;
        } catch (RuntimeException failure) {
            try {
                if (handle != null) {
                    openOwnership.rollback(handle);
                } else if (loginOwned || ActorAccountOwnership.currentOnlineClient(accountId) == login
                        || ActorAccountOwnership.currentOnlineClient(accountId) == active) {
                    openOwnership.requireOrigin(world, channel);
                    cleanupFailedOpen(server, login, active, accountId, world, channel, openOwnership);
                    releaseOwnership(ownership);
                    openOwnership.confirmAbsent();
                } else {
                    openOwnership.requireOrigin(world, channel);
                    // Never log off an account merely because a rejected login populated accID.
                    SessionCoordinator.getInstance().closeLoginSession(login);
                    login.closeSession();
                    if (!loggedIn(accountId, Client.LOGIN_NOTLOGGEDIN)
                            || hasForeignOnlineCharacter(server, accountId, null)) {
                        throw unavailable("actor_open_cleanup_unverified_rejected_login");
                    }
                    releaseOwnership(ownership);
                    openOwnership.confirmAbsent();
                }
                progress.released = true;
            } catch (RuntimeException cleanupFailure) {
                throw unavailable("actor_open_cleanup_unverified");
            }
            throw failure;
        }
    }

    public Map<String, Object> snapshot(NativeActorHandle handle) {
        if (!isCurrent(handle)) throw conflict("actor_session_stale");
        Server server = servers.get();
        GameContext.SceneRef scene = AcceptanceActorContexts.captureScene(server, handle);
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
        CashShop cashShop = character.getCashShop();
        result.put("tickets_summary", cashShop == null ? null : Map.of(
                "nx_credit", cashShop.getCash(CashShop.NX_CREDIT),
                "maple_point", cashShop.getCash(CashShop.MAPLE_POINT),
                "nx_prepaid", cashShop.getCash(CashShop.NX_PREPAID)));
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
        result.put("inventory", inventorySnapshot(character));
        result.put("quest_summary", questSummary(character));
        result.put("packet_events", handle.client().packetSummaries());
        result.put("packet_events_dropped", handle.client().droppedPacketCount());
        result.put("malformed_packet_events", handle.client().malformedPacketCount());
        result.put("dialogue", handle.client().dialogueState());
        AcceptanceActorContexts.requireSceneCurrent(server, scene);
        return result;
    }

    public boolean isCurrent(NativeActorHandle handle) {
        if (handle == null || handle.closeEvidence() != null) return false;
        try {
            AcceptanceActorContexts.requireCharacter(servers.get(), handle);
            return true;
        } catch (ObservationException failure) {
            if (failure.status() == HttpStatus.SERVICE_UNAVAILABLE && !"actor_world_offline".equals(failure.reason())) throw failure;
            return false;
        } catch (RuntimeException unavailable) {
            throw unavailable("actor_observation_unavailable");
        }
    }

    public Map<String, Object> close(NativeActorHandle handle, ScopeResources.DrainPermit permit) {
        AcceptanceActorContexts.requireDrainPermit(handle, permit);
        synchronized (handle) {
            if (handle.closeEvidence() != null) return handle.closeEvidence();
            Character character = handle.character();
            AcceptanceClient client = handle.client();
            NativeActorHandle.CloseContext context = handle.closeContext();
            if (context == null) {
                AcceptanceActorContexts.requireCloseCharacter(servers.get(), handle, permit);
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
                AcceptanceActorContexts.requireDrainPermit(handle, permit);
                client.disconnect(false, false);
            } else {
                AcceptanceActorContexts.requireDrainPermit(handle, permit);
                requireCloseOwnership(handle);
            }
            NativeActorHandle.CloseContext closing = context;
            await(() -> nativeCleanupComplete(handle, closing, permit), "actor_close_cleanup_unverified");
            client.closeSession();
            client.invalidateDialogue();
            CharactersDO stored = characters.findById(closing.characterId());
            if (!matchesPersistedState(closing, stored)) {
                throw unavailable("actor_close_cleanup_unverified_persistence");
            }
            releaseOwnership(handle.ownership());
            Map<String, Object> result = identity(handle);
            result.put("native_cleanup_verified", true);
            result.put("persisted_state_verified", true);
            result.put("native_dialogue_cleanup_verified", true);
            result.put("persistence_verification_scope", List.of("identity", "level", "exp", "hp", "mp",
                    "meso", "job", "native_saved_map", "party"));
            result.put("fixture_retained", true);
            handle.recordClose(result);
            return handle.closeEvidence();
        }
    }

    private void cleanupFailedOpen(Server server, AcceptanceClient login, AcceptanceClient active, int accountId,
                                   World world, Channel channel, OpenOwnership ownership) {
        ownership.requireOrigin(world, channel);
        if (servers.get().getWorld(world.getId()) != world || world.getChannel(channel.getId()) != channel) {
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
        await(() -> {
            ownership.requireOrigin(world, channel);
            return loggedIn(accountId, Client.LOGIN_NOTLOGGEDIN)
                && ActorAccountOwnership.currentOnlineClient(accountId) == null
                && !hasForeignOnlineCharacter(server, accountId, null)
                && active.getPlayer() == null && !active.isLoggedIn();
        }, "actor_open_cleanup_unverified");
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

    private boolean nativeCleanupComplete(NativeActorHandle handle, NativeActorHandle.CloseContext context,
                                          ScopeResources.DrainPermit permit) {
        AcceptanceActorContexts.requireDrainPermit(handle, permit);
        requireCloseOwnership(handle);
        Character character = handle.character();
        return loggedIn(handle.accountId(), Client.LOGIN_NOTLOGGEDIN)
                && ActorAccountOwnership.currentOnlineClient(handle.accountId()) == null
                && handle.client().getPlayer() == null && !handle.client().isLoggedIn()
                && handle.client().getCM() == null && handle.client().getQM() == null
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

    static void prepareCharacterViews(Server server, AcceptanceClient login, World world) {
        server.loadAccountCharacters(login);
        // A same-JVM world restart retains Server's account cache but creates fresh World views.
        // The native getter initializes only a missing account view, preserving any existing entries.
        world.getAccountCharactersView(login.getAccID());
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

    /** Fresh values copied under each native inventory lock; this is not a second inventory ledger. */
    static Map<String, Object> inventorySnapshot(Character character) {
        List<Map<String, Object>> inventories = new ArrayList<>();
        boolean complete = true;
        for (InventoryType type : INVENTORIES) {
            if (type == InventoryType.EQUIPPED) continue;
            var inventory = character.getInventory(type);
            inventory.lockInventory();
            try {
                int slotLimit = Byte.toUnsignedInt(inventory.getSlotLimit());
                int observedLimit = Math.min(slotLimit, 127);
                boolean inventoryComplete = slotLimit <= 127;
                List<Map<String, Object>> items = new ArrayList<>();
                for (short slot = 1; slot <= observedLimit; slot++) {
                    var item = inventory.getItem(slot);
                    if (item != null) items.add(Map.of("slot", (int) slot, "item_id", item.getItemId(),
                            "quantity", (int) item.getQuantity()));
                }
                inventories.add(Map.of("inventory_type", type.name(), "slot_limit", slotLimit,
                        "complete", inventoryComplete, "items", List.copyOf(items)));
                complete &= inventoryComplete;
            } finally { inventory.unlockInventory(); }
        }
        return Map.of("complete", complete, "inventories", List.copyOf(inventories));
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
        dispatch(handler, client, writer, () -> { });
    }

    private static void dispatch(PacketHandler handler, AcceptanceClient client, Consumer<ByteBuf> writer,
                                 Runnable dispatchStarted) {
        ByteBuf packet = Unpooled.buffer();
        try {
            writer.accept(packet);
            withClient(client, () -> {
                if (!handler.validateState(client)) throw conflict("actor_native_packet_state_rejected");
                dispatchStarted.run();
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
