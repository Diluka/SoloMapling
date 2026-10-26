package soloMapling.beidou;

import org.gms.client.Client;
import org.gms.exception.EmptyMovementException;
import org.gms.net.packet.InPacket;
import org.gms.net.server.channel.handlers.AbstractMovementPacketHandler;
import org.gms.server.maps.AnimatedMapObject;

/** Reuse the official v83 parser rather than carrying a second movement switch. */
public final class BotMovementDecoder extends AbstractMovementPacketHandler {
    private static final BotMovementDecoder INSTANCE = new BotMovementDecoder();

    private BotMovementDecoder() { }

    public static void update(InPacket packet, AnimatedMapObject target, int yOffset) throws EmptyMovementException {
        INSTANCE.updatePosition(packet, target, yOffset);
    }

    @Override
    public void handlePacket(InPacket packet, Client client) {
        throw new UnsupportedOperationException("Bot movement decoder is not a network packet handler");
    }
}
