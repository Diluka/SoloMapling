package org.gms.soloacceptance.actor;

import io.netty.handler.timeout.IdleStateEvent;
import org.gms.client.Client;
import org.gms.constants.game.NextLevelType;
import org.gms.net.packet.Packet;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.npc.NPCConversationManager;
import org.gms.model.pojo.NextLevelContext;
import org.gms.soloacceptance.ObservationException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** Socket-free player transport. Native login, ownership and persistence remain in Client. */
public final class AcceptanceClient extends Client {
    private static final Map<String, Object> PACKET_OBSERVATION_CAPABILITIES = Map.of(
            "capacity", PacketEventBuffer.CAPACITY,
            "maximum_text_chars", PacketEventDecoder.MAXIMUM_TEXT_CHARS,
            "cursor_scope", "session_packet_sequence",
            "decoded_packets", List.of(
                    Map.of("event_type", "chat_text", "opcode", PacketEventDecoder.CHAT_TEXT),
                    Map.of("event_type", "player_hint", "opcode", PacketEventDecoder.PLAYER_HINT),
                    Map.of("event_type", "server_message", "opcode", PacketEventDecoder.SERVER_MESSAGE,
                            "message_types", List.of(0, 1, 2, 3, 4, 5, 6, 7)),
                    Map.of("event_type", "npc_dialogue", "opcode", PacketEventDecoder.NPC_TALK,
                            "message_types", List.of(4, 12))));
    private final World boundWorld;
    private final Channel boundChannel;
    private final PacketEventBuffer packets = new PacketEventBuffer();
    private final NativeDialogueTracker dialogue = new NativeDialogueTracker(packets);

    public AcceptanceClient(Type type, long sessionId, String remoteAddress, World world, Channel channel) {
        super(type, sessionId, remoteAddress, null, world.getId(), channel.getId());
        boundWorld = world;
        boundChannel = channel;
    }

    @Override
    public World getWorldServer() {
        return boundWorld;
    }

    @Override
    public Channel getChannelServer() {
        return boundChannel;
    }

    @Override
    public Channel getChannelServer(byte channel) {
        return channel == boundChannel.getId() ? boundChannel : boundWorld.getChannel(channel);
    }

    @Override
    public void sendPacket(Packet packet) {
        long epoch = dialogue.epoch();
        PacketEventBuffer.Receipt receipt = packets.accept(packet, getLanguage());
        if (receipt == null) return;
        if (receipt.event().get("opcode") == null && "malformed".equals(receipt.event().get("decode_status"))) {
            invalidateDialogue();
            return;
        }
        if (!Integer.valueOf(PacketEventDecoder.NPC_TALK).equals(receipt.event().get("opcode"))) return;
        NativeDialogueTracker.Pending pending = dialogue.begin(epoch, receipt);
        if (pending == null) return;
        NPCConversationManager cm = null;
        try {
            if (pending.scope() != null && !packets.closed() && getQM() == null) {
                cm = getCM();
                if (cm != null) {
                    dialogue.capture(pending, cm, cm.getNpc(), cm.getScriptName(), cm.isItemScript(), true);
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must not interrupt the native script which is sending this packet.
        }
        dialogue.capture(pending, null, 0, null, false, false);
    }

    public List<Map<String, Object>> packetSummaries() {
        return packets.summaries();
    }

    public Map<String, Object> packetEvents(long sinceCursor, int limit) {
        return packets.window(sinceCursor, limit);
    }

    public static Map<String, Object> packetObservationCapabilities() {
        return PACKET_OBSERVATION_CAPABILITIES;
    }

    public static Map<String, Object> nativeDialogueCapabilities() {
        return Map.of("entrypoints", List.of("native_ui_center"), "message_types", List.of(4, 12),
                "version_scope", "session_packet_sequence", "cancel_requires_latest_version", true,
                "npc_minimum_interval_ms", 500, "reply_kinds", List.of("select", "decline"),
                "requires_complete_offers", true, "requires_current_offered_selection", true,
                "maximum_offered_selections", NativeDialogueTracker.MAXIMUM_OFFERS);
    }

    public long droppedPacketCount() {
        return packets.dropped();
    }

    public long malformedPacketCount() {
        return packets.malformed();
    }

    public long latestPacketSequence() {
        return packets.latest();
    }

    public boolean transportClosed() {
        return packets.closed();
    }

    public Map<String, Object> dialogueState() {
        NativeDialogueTracker.State state = dialogue.current();
        boolean active = nativeDialogueActive(state);
        boolean routeValid = active && nativeRouteValid(state);
        if (!dialogue.isCurrent(state)) {
            state = dialogue.current();
            active = false;
            routeValid = false;
        }
        long latest = packets.latestNpcSequence();
        if (state.sequence != null && state.sequence != latest) {
            return NativeDialogueTracker.State.unsupported(latest).view(false, false);
        }
        return state.view(active, routeValid);
    }

    public void invalidateDialogue() { dialogue.invalidate(); }

    public void requireNativeCenterDialogue(long version) {
        NativeDialogueTracker.State state = requireDialogue(version);
        if (!"center".equals(state.path)) throw conflict("dialogue_not_active");
    }

    public NativeDialogueCommand validateNativeDialogueReply(long version, String kind, Integer selection) {
        if (!("select".equals(kind) && selection != null && selection >= 0
                || "decline".equals(kind) && selection == null)) {
            throw new ObservationException(HttpStatus.BAD_REQUEST, "invalid_npc_reply");
        }
        NativeDialogueTracker.State state = requireDialogue(version);
        String expected;
        int action;
        if ("select".equals(kind)) {
            if (state.type != 4) throw conflict("npc_reply_path_not_supported");
            if (!state.offers.complete()) throw conflict("npc_menu_incomplete");
            if (!state.offers.ids().contains(selection)) throw conflict("npc_selection_not_offered");
            expected = "center".equals(state.path) && selection == 0 ? "welfare"
                    : "center".equals(state.path) && selection == 71 ? "transport_category"
                    : "transport_category".equals(state.path) && selection == 2 ? "transport_town" : null;
            if (expected == null) throw conflict("npc_reply_path_not_supported");
            action = 1;
        } else {
            if (state.type != 12 || !"welfare".equals(state.path)) throw conflict("npc_reply_path_not_supported");
            expected = "closed";
            action = 0;
        }
        requireUnchanged(state);
        return new NativeDialogueCommand(this, state, kind, action, selection, expected);
    }

    public NativeDialogueCommand validateNativeDialogueCancel(long version) {
        NativeDialogueTracker.State state = requireDialogue(version);
        int action = "transport_category".equals(state.path) || "transport_town".equals(state.path) ? 0 : -1;
        return new NativeDialogueCommand(this, state, "cancel", action, null, "closed");
    }

    public void dispatchNativeCenter(Runnable nativeDispatch) {
        if (packets.closed()) throw conflict("dialogue_not_active");
        NativeDialogueTracker.DispatchScope scope = dialogue.openCenter();
        boolean failed = true;
        try { nativeDispatch.run(); failed = false; }
        finally { dialogue.finish(scope, failed); }
    }

    public void dispatchNativeDialogue(NativeDialogueCommand command, Runnable nativeDispatch) {
        if (command == null || command.client != this) throw stale();
        NativeDialogueCommand checked = "cancel".equals(command.kind)
                ? validateNativeDialogueCancel(command.version())
                : validateNativeDialogueReply(command.version(), command.kind, command.selection);
        if (checked.state != command.state) throw stale();
        NativeDialogueTracker.DispatchScope scope = dialogue.consume(command.state, command.expectedPath, "decline".equals(command.kind));
        command.consumed = true;
        boolean failed = true;
        try { nativeDispatch.run(); failed = false; }
        finally {
            dialogue.finish(scope, failed);
            command.closedPacketSequence = failed ? null : scope.closedPacketSequence;
        }
    }

    private NativeDialogueTracker.State requireDialogue(long version) {
        NativeDialogueTracker.State state = dialogue.current();
        requireUnchanged(state);
        if (state.sequence == null || state.sequence != version || state.consumed) throw stale();
        boolean active = nativeDialogueActive(state) && nativeRouteValid(state);
        requireUnchanged(state);
        if (!active) throw conflict("dialogue_not_active");
        return state;
    }

    private void requireUnchanged(NativeDialogueTracker.State state) {
        if (!dialogue.isCurrent(state) || state.sequence != null && state.sequence != packets.latestNpcSequence()) throw stale();
    }

    private boolean nativeDialogueActive(NativeDialogueTracker.State state) {
        if (!state.supported() || state.cm == null || packets.closed()) return false;
        try {
            String script = switch (state.path) {
                case "center" -> null;
                case "welfare" -> "新人福利";
                case "transport_category", "transport_town" -> "万能传送";
                default -> throw new IllegalStateException();
            };
            return getCM() == state.cm && getQM() == null
                    && state.cm.getNpc() == 9900001 && java.util.Objects.equals(state.cm.getScriptName(), script)
                    && !state.cm.isItemScript()
                    && !packets.closed();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean nativeRouteValid(NativeDialogueTracker.State state) {
        try {
            NextLevelContext context = state.cm.getNextLevelContext();
            NextLevelType type = context.getLevelType();
            return switch (state.path) {
                case "center" -> state.type == 4 && type == null;
                case "welfare" -> state.type == 12 && type == null;
                case "transport_category" -> state.type == 4 && type == NextLevelType.SEND_SELECT
                        && "".equals(context.getPrefix());
                case "transport_town" -> state.type == 4 && type == NextLevelType.SEND_NEXT_SELECT
                        && "Town".equals(context.getNextLevel());
                default -> false;
            };
        } catch (RuntimeException ignored) { return false; }
    }

    private static ObservationException stale() { return new ObservationException(HttpStatus.GONE, "stale_dialogue"); }
    private static ObservationException conflict(String reason) { return new ObservationException(HttpStatus.CONFLICT, reason); }

    /** Opaque client-bound command. Construction and consumed status are controlled by this transport. */
    public static final class NativeDialogueCommand {
        private final AcceptanceClient client;
        private final NativeDialogueTracker.State state;
        private final String kind;
        private final int action;
        private final Integer selection;
        private final String expectedPath;
        private volatile boolean consumed;
        private volatile Long closedPacketSequence;
        private NativeDialogueCommand(AcceptanceClient client, NativeDialogueTracker.State state, String kind,
                                      int action, Integer selection, String expectedPath) {
            this.client = client; this.state = state; this.kind = kind; this.action = action;
            this.selection = selection; this.expectedPath = expectedPath;
        }
        public long version() { return state.sequence; }
        public int messageType() { return state.type; }
        public int action() { return action; }
        public Integer selection() { return selection; }
        public String path() { return state.path; }
        public String expectedPath() { return expectedPath; }
        public boolean consumed() { return consumed; }
        public Long closedPacketSequence() { return closedPacketSequence; }
    }

    @Override
    public void closeSession() {
        packets.close();
        invalidateDialogue();
    }

    @Override
    public void disconnectSession() {
        closeSession();
    }

    @Override
    public void checkIfIdle(IdleStateEvent event) {
        // No socket idle events exist. The actor session lease owns its timeout.
    }
}
