package org.gms.soloacceptance.actor;

import io.netty.handler.timeout.IdleStateEvent;
import org.gms.client.Client;
import org.gms.net.packet.Packet;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Socket-free player transport. Native login, ownership and persistence remain in Client. */
public final class AcceptanceClient extends Client {
    private static final int PACKET_CAPACITY = 256;
    private final World boundWorld;
    private final Channel boundChannel;
    private final ArrayDeque<Map<String, Object>> packets = new ArrayDeque<>();
    private long packetSequence;
    private long droppedPackets;
    private long malformedPackets;
    private volatile boolean closed;

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
        if (closed) return;
        byte[] bytes = null;
        String malformedReason = null;
        if (packet == null) {
            malformedReason = "packet_missing";
        } else {
            try {
                bytes = packet.getBytes();
                if (bytes == null) malformedReason = "packet_bytes_missing";
                else if (bytes.length < 2) malformedReason = "packet_header_missing";
            } catch (RuntimeException ignored) {
                malformedReason = "packet_read_failed";
            }
        }
        Integer opcode = bytes == null || bytes.length < 2 ? null
                : (bytes[0] & 0xff) | ((bytes[1] & 0xff) << 8);
        synchronized (packets) {
            if (closed) return;
            if (packets.size() == PACKET_CAPACITY) {
                packets.removeFirst();
                droppedPackets++;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("sequence", ++packetSequence);
            summary.put("opcode", opcode);
            summary.put("length", bytes == null ? null : bytes.length);
            if (malformedReason != null) {
                malformedPackets++;
                summary.put("malformed", true);
                summary.put("reason", malformedReason);
            }
            packets.addLast(Collections.unmodifiableMap(summary));
        }
    }

    public List<Map<String, Object>> packetSummaries() {
        synchronized (packets) {
            return List.copyOf(packets);
        }
    }

    public long droppedPacketCount() {
        synchronized (packets) {
            return droppedPackets;
        }
    }

    public long malformedPacketCount() {
        synchronized (packets) {
            return malformedPackets;
        }
    }

    public long latestPacketSequence() {
        synchronized (packets) {
            return packetSequence;
        }
    }

    public boolean transportClosed() {
        return closed;
    }

    @Override
    public void closeSession() {
        synchronized (packets) {
            closed = true;
        }
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
