package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.client.inventory.Item;
import org.gms.net.server.Server;
import org.gms.server.maps.FieldLimit;
import org.gms.server.maps.MapItem;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.util.PacketCreator;

import java.awt.Point;

/** Casino/game props still use the host map registry, capacity limit and pickup path. */
public privileged aspect DropCompatibility {
    private boolean MapItem.soloPermanentOwner;
    public void MapItem.setPermanentOwner(boolean value) { soloPermanentOwner = value; }

    boolean around(MapItem item): execution(boolean MapItem.hasExpiredOwnershipTime()) && this(item) {
        return !item.soloPermanentOwner && proceed(item);
    }

    boolean around(MapItem item): execution(boolean MapItem.isFFADrop()) && this(item) {
        return !item.soloPermanentOwner && proceed(item);
    }

    public MapItem MapleMap.spawnItemDropNoExpire(MapObject dropper, Character owner, Item item,
            Point point, boolean freeForAll, boolean playerDrop) {
        if (!BotRegistry.isBot(owner)) {
            throw new IllegalArgumentException("Non-expiring game props require a registered SoloMapling owner");
        }
        if (FieldLimit.DROP_LIMIT.check(getFieldLimit())) return null;
        final Point position = calcDropPos(point, point);
        final MapItem drop = new MapItem(item, position, dropper, owner, owner.getClient(),
                (byte) (freeForAll ? 2 : 0), playerDrop);
        drop.setDropTime(Server.getInstance().getCurrentTime());
        spawnAndAddRangedMapObject(drop, client -> {
            drop.lockItem();
            try {
                client.sendPacket(PacketCreator.dropItemFromMapObject(client.getPlayer(), drop,
                        dropper.getPosition(), position, (byte) 1));
            } finally {
                drop.unlockItem();
            }
        }, null);
        drop.lockItem();
        try {
            broadcastItemDropMessage(drop, dropper.getPosition(), position, (byte) 0);
        } finally {
            drop.unlockItem();
        }
        instantiateItemDrop(drop);
        objectWLock.lock();
        try {
            droppedItems.put(drop, Long.MAX_VALUE);
        } finally {
            objectWLock.unlock();
        }
        activateItemReactors(drop, owner.getClient());
        return drop;
    }
}
