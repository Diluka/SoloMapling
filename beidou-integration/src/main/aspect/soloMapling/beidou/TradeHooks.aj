package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.server.Trade;
import org.gms.server.Trade.TradeResult;
import org.gms.util.PacketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeQueue;

import java.util.function.Consumer;

/** Observe native settlement; never bypass capacity, ownership, meso or handshake checks. */
public privileged aspect TradeHooks {
    private static final Logger LOG = LoggerFactory.getLogger(TradeHooks.class);
    private Consumer<TradeResult> Trade.soloCallback;
    private boolean Trade.soloNativeCompleted;

    public int Trade.soloGetMeso() { return getMeso(); }
    public boolean Trade.soloIsLocked() { return isLocked(); }
    public void Trade.setTradeResultCallback(Consumer<TradeResult> callback) { soloCallback = callback; }

    /** Absolute escrow target using the host's normal debit path. */
    public void Trade.setMesoBot(int amount) {
        if (!BotRegistry.isBot(chr)) throw new IllegalStateException("Not a SoloMapling trade participant");
        Trade monitor = partner == null || chr.getId() < partner.chr.getId() ? this : partner;
        synchronized (monitor) {
            if (amount < 0 || isLocked()) throw new IllegalArgumentException("Invalid or locked trade offer");
            int current = getMeso();
            if (amount > current) {
                setMeso(amount - current);
            } else if (amount < current) {
                chr.gainMeso(current - amount, false, true, false);
                meso = amount;
                chr.sendPacket(PacketCreator.getTradeMesoSet((byte) 0, meso));
                if (partner != null) partner.chr.sendPacket(PacketCreator.getTradeMesoSet((byte) 1, meso));
            }
        }
    }

    /** The caller reserves the new native item and handles the replaced escrow item. */
    public boolean Trade.swapItem(Item item) {
        if (!BotRegistry.isBot(chr) || item == null || isLocked()) return false;
        synchronized (items) {
            if (isLocked()) return false;
            for (int index = 0; index < items.size(); index++) {
                if (items.get(index).getPosition() == item.getPosition()) {
                    items.set(index, item);
                    return true;
                }
            }
            return items.size() < 9 && addItem(item);
        }
    }

    after(Trade trade) returning: execution(void Trade.completeTrade()) && this(trade) {
        trade.soloNativeCompleted = true;
    }

    void around(Character character): execution(void Trade.completeTrade(Character)) && args(character) {
        Trade local = character.getTrade();
        Trade other = local == null ? null : local.getPartner();
        proceed(character);
        if (local != null && other != null && local.soloNativeCompleted && other.soloNativeCompleted) {
            notifyResult(local, TradeResult.SUCCESSFUL);
            notifyResult(other, TradeResult.SUCCESSFUL);
        }
    }

    after(Trade trade, byte result) returning:
            execution(void Trade.cancel(byte)) && this(trade) && args(result) {
        TradeResult resolved = TradeResult.PARTNER_CANCEL;
        for (TradeResult candidate : TradeResult.values()) {
            if (candidate.getValue() == result) resolved = candidate;
        }
        notifyResult(trade, resolved);
    }

    private static void notifyResult(Trade trade, TradeResult result) {
        if (!BotRegistry.isBot(trade.getChr())) return;
        BotTradeQueue.getInstance().removeTradeRequest(trade.getChr());
        Consumer<TradeResult> callback = trade.soloCallback;
        trade.soloCallback = null;
        if (callback != null) {
            try {
                callback.accept(result);
            } catch (RuntimeException failure) {
                LOG.warn("SoloMapling trade callback failed after native settlement", failure);
            }
        }
    }
}
