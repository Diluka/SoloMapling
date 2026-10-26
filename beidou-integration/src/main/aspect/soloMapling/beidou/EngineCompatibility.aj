package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.client.BuffStat;
import org.gms.client.inventory.BodyPart;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.constants.inventory.ItemConstants;
import org.gms.net.server.channel.Channel;
import org.gms.server.StatEffect;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.HiredMerchant;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.PlayerShop;
import org.gms.server.maps.PlayerShopItem;
import org.gms.server.maps.Rope;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Reactor;
import org.gms.server.maps.MapObject;
import org.gms.server.life.SpawnPoint;
import soloMapling.ArtificialPlayer.BotTier;
import soloMapling.server.MethodScheduler;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/** Additive simulation methods. Original host sources and public behavior stay authoritative. */
public privileged aspect EngineCompatibility {
    private BotTier Character.soloTier = BotTier.getDefaultTier();

    public void Character.setTier(BotTier tier) {
        soloTier = tier == null ? BotTier.getDefaultTier() : tier;
    }

    public BotTier Character.getTier() {
        return soloTier == null ? BotTier.getDefaultTier() : soloTier;
    }

    public int Character.getTotalMoveSpeedStat() {
        int result = 100;
        for (Item item : getInventory(InventoryType.EQUIPPED)) {
            if (item instanceof Equip) {
                result += ((Equip) item).getSpeed();
            }
        }
        Integer buff = getBuffedValue(BuffStat.SPEED);
        return Math.max(1, result + (buff == null ? 0 : buff));
    }

    public int Character.getTotalJumpStat() {
        int result = 100;
        for (Item item : getInventory(InventoryType.EQUIPPED)) {
            if (item instanceof Equip) {
                result += ((Equip) item).getJump();
            }
        }
        Integer buff = getBuffedValue(BuffStat.JUMP);
        return Math.max(1, result + (buff == null ? 0 : buff));
    }

    public void Character.soloSetChair(int chairId) { setChair(chairId); }
    public void Character.soloUnsitChair() { unsitChairInternal(); }

    public boolean StatEffect.applyToTarget(Character caster, Character target) {
        return applyTo(caster, target, false, null, false, 1);
    }

    public boolean StatEffect.soloIsPartyBuff() { return isPartyBuff(); }

    public Rectangle StatEffect.getAttackBox(Point from, boolean facingLeft) {
        return hasBoundingBox() ? calculateBoundingBox(from, facingLeft) : null;
    }

    public void Channel.soloCloseAllMerchants() { closeAllMerchants(); }

    public static int ItemConstants.getItemPrefix(int itemId) { return itemId / 10000; }

    public static int ItemConstants.getEquipSlotType(int itemId) {
        if (ItemConstants.isWeapon(itemId)) return BodyPart.WEAPON.getValue();
        switch (itemId / 10000) {
            case 100: return BodyPart.CAP.getValue();
            case 101: return BodyPart.FACE_ACCESSORY.getValue();
            case 102: return BodyPart.EYE_ACCESSORY.getValue();
            case 103: return BodyPart.EAR_ACCESSORY.getValue();
            case 104: return BodyPart.COAT.getValue();
            case 105: return BodyPart.LONGCOAT.getValue();
            case 106: return BodyPart.PANTS.getValue();
            case 107: return BodyPart.SHOES.getValue();
            case 108: return BodyPart.GLOVE.getValue();
            case 109: case 119: case 134: return BodyPart.SHIELD.getValue();
            case 110: return BodyPart.CAPE.getValue();
            case 111: return BodyPart.RING_1.getValue();
            case 112: return BodyPart.PENDANT.getValue();
            case 113: return BodyPart.BELT.getValue();
            case 114: return BodyPart.MEDAL.getValue();
            case 115: return BodyPart.SHOULDER.getValue();
            case 118: return BodyPart.BADGE.getValue();
            default: return 0;
        }
    }

    private boolean Foothold.soloForbidFallDown;
    public boolean Foothold.isForbidFallDown() { return soloForbidFallDown; }
    public void Foothold.setForbidFallDown(boolean value) { soloForbidFallDown = value; }
    public double Foothold.slope() {
        return isWall() ? 0.0 : (double) (getY2() - getY1()) / (getX2() - getX1());
    }

    public static boolean Foothold.isCollidableWall(Foothold wall, Map<Integer, Foothold> footholdsById) {
        if (wall == null || !wall.isWall()) return false;
        Point endpoint = wall.getY1() >= wall.getY2()
                ? new Point(wall.getX1(), wall.getY1()) : new Point(wall.getX2(), wall.getY2());
        return reachesGround(wall.getPrev(), false, endpoint, footholdsById)
                || reachesGround(wall.getNext(), true, endpoint, footholdsById);
    }

    private static boolean reachesGround(int id, boolean forward, Point endpoint, Map<Integer, Foothold> all) {
        Foothold first = all.get(id);
        if (first == null || !((first.getX1() == endpoint.x && first.getY1() == endpoint.y)
                || (first.getX2() == endpoint.x && first.getY2() == endpoint.y))) return false;
        for (int depth = 0; id != 0 && depth < 10; depth++) {
            Foothold current = all.get(id);
            if (current == null) return false;
            if (!current.isWall()) return true;
            id = forward ? current.getNext() : current.getPrev();
        }
        return false;
    }

    public List<Foothold> FootholdTree.getAllFootholds() {
        List<Foothold> result = new LinkedList<Foothold>();
        soloCollectFootholds(result);
        return result;
    }

    private void FootholdTree.soloCollectFootholds(List<Foothold> result) {
        result.addAll(footholds);
        if (nw != null) {
            nw.soloCollectFootholds(result);
            ne.soloCollectFootholds(result);
            sw.soloCollectFootholds(result);
            se.soloCollectFootholds(result);
        }
    }

    private final List<Rope> MapleMap.soloRopes = new ArrayList<Rope>();
    private float MapleMap.soloFootholdSpeed = 1.0f;
    private boolean MapleMap.soloSwim;
    public Collection<Portal> MapleMap.getPortals() { return Collections.unmodifiableCollection(portals.values()); }
    public List<Rope> MapleMap.getRopes() { return Collections.unmodifiableList(soloRopes); }
    public void MapleMap.addRope(Rope rope) { soloRopes.add(rope); }
    public float MapleMap.getFootholdSpeed() { return soloFootholdSpeed; }
    public void MapleMap.setFootholdSpeed(float value) { soloFootholdSpeed = value; }
    public boolean MapleMap.isSwim() { return soloSwim; }
    public void MapleMap.setSwim(boolean value) { soloSwim = value; }
    public void MapleMap.moveBot(Character character, Point point) { character.setPosition(point); }
    public void MapleMap.dropFromReactor(Character character, Reactor reactor, Item item, Point point, short questId, short delay) {
        if (delay > 0) MethodScheduler.runAfterDelay(() -> dropFromReactor(character, reactor, item, point, questId), delay);
        else dropFromReactor(character, reactor, item, point, questId);
    }
    public void MapleMap.spawnMesoDrop(int meso, Point point, MapObject dropper, Character owner,
            boolean playerDrop, byte dropType, short delay) {
        if (delay > 0) MethodScheduler.runAfterDelay(() -> spawnMesoDrop(meso, point, dropper, owner, playerDrop, dropType), delay);
        else spawnMesoDrop(meso, point, dropper, owner, playerDrop, dropType);
    }
    public List<PlayerShop> MapleMap.getAllPlayerShops() {
        List<PlayerShop> result = new ArrayList<PlayerShop>();
        for (MapObject object : getMapObjects()) if (object instanceof PlayerShop) result.add((PlayerShop) object);
        return result;
    }
    public List<HiredMerchant> MapleMap.getAllHiredMerchants() {
        List<HiredMerchant> result = new ArrayList<HiredMerchant>();
        for (MapObject object : getMapObjects()) if (object instanceof HiredMerchant) result.add((HiredMerchant) object);
        return result;
    }
    public List<Point> MapleMap.getMonsterSpawnPositions() {
        List<Point> result = new ArrayList<Point>();
        synchronized (allMonsterSpawn) {
            for (SpawnPoint spawn : allMonsterSpawn) {
                Point point = spawn.getPosition();
                if (point != null) result.add(new Point(point));
            }
        }
        return result;
    }

    public void PlayerShop.setItems(List<PlayerShopItem> stock) {
        synchronized (items) {
            if (stock.size() > 16 || !items.isEmpty()) {
                throw new IllegalArgumentException("Shop stock must be initialized once with at most 16 entries");
            }
            items.addAll(stock);
        }
    }

    public void PlayerShop.chat(Character character, String message) { chat(character.getClient(), message); }

    public boolean PlayerShop.botBuy(Character bot, PlayerShopItem item, int index, short quantity) {
        if (!BotRegistry.isBot(bot) || index < 0 || index >= getItems().size()
                || getItems().get(index) != item) return false;
        if (!isVisitor(bot) && !visitShop(bot)) return false;
        return buy(bot.getClient(), index, quantity);
    }

    public void HiredMerchant.botBuy(Character bot, PlayerShopItem item, short quantity) {
        if (!BotRegistry.isBot(bot)) return;
        int index = getItems().indexOf(item);
        if (index >= 0) buy(bot.getClient(), index, quantity);
    }

}
