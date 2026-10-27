package org.gms.soloacceptance.actor;

import io.netty.handler.timeout.IdleStateEvent;
import org.gms.client.Client;
import org.gms.net.packet.Packet;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;

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
                            "message_types", List.of(0, 1, 2, 3, 4, 5, 6, 7))));
    private final World boundWorld;
    private final Channel boundChannel;
    private final PacketEventBuffer packets = new PacketEventBuffer();

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
        packets.accept(packet, getLanguage());
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

    @Override
    public void closeSession() {
        packets.close();
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
