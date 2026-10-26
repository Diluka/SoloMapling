package soloMapling.beidou;

import io.netty.buffer.Unpooled;
import org.gms.exception.EmptyMovementException;
import org.gms.net.packet.ByteBufInPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.server.channel.handlers.AbstractMovementPacketHandler;
import org.gms.server.maps.AnimatedMapObject;

public aspect MovementCompatibility {
    public static void AbstractMovementPacketHandler.updatePositionBot(
            InPacket packet, AnimatedMapObject target, int yOffset) throws EmptyMovementException {
        BotMovementDecoder.update(packet, target, yOffset);
    }

    public InPacket InPacket.copy() {
        return new ByteBufInPacket(Unpooled.wrappedBuffer(getBytes()));
    }
}
