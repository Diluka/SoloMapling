package soloMapling.game.action;

import io.netty.buffer.ByteBuf;
import org.gms.client.Client;
import org.gms.net.PacketHandler;
import org.gms.net.packet.ByteBufInPacket;
import org.gms.util.ThreadLocalUtil;

/** Package-owned finite native adapters share packet admission and temporary native resources. */
final class NativePacketDispatch {
    private NativePacketDispatch() { }

    /** Returns handler failure separately from dispatch and observed business effects. */
    static boolean dispatch(Client client, PacketHandler handler, ByteBuf packet,
                            Runnable requireCurrent, Runnable dispatchStarted) {
        Client previous = ThreadLocalUtil.getCurrentClient();
        try {
            requireCurrent.run();
            ThreadLocalUtil.setCurrentClient(client);
            if (!handler.validateState(client)) throw new NativeActionService.Invalid("native_packet_state_rejected");
            requireCurrent.run();
            dispatchStarted.run();
            try { handler.handlePacket(new ByteBufInPacket(packet), client); }
            catch (Throwable failure) { return true; }
            return false;
        } finally {
            if (previous == null) ThreadLocalUtil.removeCurrentClient(); else ThreadLocalUtil.setCurrentClient(previous);
            packet.release();
        }
    }
}
