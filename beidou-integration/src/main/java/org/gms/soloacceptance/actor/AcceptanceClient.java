package org.gms.soloacceptance.actor;

import io.netty.handler.timeout.IdleStateEvent;
import org.gms.client.Client;
import org.gms.net.packet.Packet;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.npc.NPCConversationManager;
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
                            "message_types", List.of(4))));
    private final World boundWorld;
    private final Channel boundChannel;
    private final PacketEventBuffer packets = new PacketEventBuffer();
    private final NativeDialogueTracker dialogue = new NativeDialogueTracker();

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
        Map<String, Object> event = packets.accept(packet, getLanguage());
        if (event == null || !Integer.valueOf(PacketEventDecoder.NPC_TALK).equals(event.get("opcode"))) return;
        NativeDialogueTracker.State pending = dialogue.begin(epoch, event);
        if (pending == null) return;
        NPCConversationManager cm = null;
        try {
            if (pending.supported && !packets.closed() && getQM() == null) cm = getCM();
        } catch (RuntimeException ignored) {
            // Diagnostics must not interrupt the native script which is sending this packet.
        }
        dialogue.capture(pending, cm);
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
        boolean active = nativeDialogueActive(state, false);
        if (!dialogue.isCurrent(state)) {
            state = dialogue.current();
            active = false;
        }
        long latest = packets.latestNpcSequence();
        if (state.sequence != null && state.sequence != latest) {
            return NativeDialogueTracker.State.unsupported(latest).view(false);
        }
        return state.view(active);
    }

    public void invalidateDialogue() { dialogue.invalidate(); }

    public void requireNativeCenterDialogue(long version) {
        NativeDialogueTracker.State state = dialogue.current();
        if (state.sequence == null || state.sequence != version || state.sequence != packets.latestNpcSequence()) {
            throw new ObservationException(HttpStatus.GONE, "stale_dialogue");
        }
        boolean active = nativeDialogueActive(state, true);
        if (!dialogue.isCurrent(state) || state.sequence != packets.latestNpcSequence()) {
            throw new ObservationException(HttpStatus.GONE, "stale_dialogue");
        }
        if (!active) throw new ObservationException(HttpStatus.CONFLICT, "dialogue_not_active");
    }

    private boolean nativeDialogueActive(NativeDialogueTracker.State state, boolean requireLegacyMenu) {
        if (!state.supported || state.cm == null || packets.closed()) return false;
        try {
            return getCM() == state.cm && getQM() == null
                    && state.cm.getNpc() == 9900001 && state.cm.getScriptName() == null && !state.cm.isItemScript()
                    && (!requireLegacyMenu || state.cm.getNextLevelContext().getLevelType() == null)
                    && !packets.closed();
        } catch (RuntimeException ignored) {
            return false;
        }
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
