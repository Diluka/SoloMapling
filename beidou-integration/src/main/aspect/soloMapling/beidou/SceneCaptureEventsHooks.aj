package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.client.BotClient;
import org.gms.client.Client;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.Packet;
import org.gms.net.server.channel.handlers.AbstractDealDamageHandler;
import org.gms.net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import org.gms.net.server.channel.handlers.TakeDamageHandler;
import org.gms.server.life.Monster;
import org.gms.server.maps.AbstractAnimatedMapObject;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.soloacceptance.SceneCaptureEvents;
import org.gms.soloacceptance.actor.AcceptanceClient;
import soloMapling.game.scene.SceneRuntimeManager;

/** Read-only observation of existing native paths; never creates a role or starts scene simulation. */
public privileged aspect SceneCaptureEventsHooks {
    private final ThreadLocal<Packet> transitionPacket = new ThreadLocal<>();
    private final ThreadLocal<MapleMap> transitionTarget = new ThreadLocal<>();

    // Native warp sends SET_FIELD before actor.map changes. Associate only that exact packet with its destination.
    void around(Character actor, MapleMap destination, java.awt.Point position, Packet packet):
            execution(private void Character.changeMapInternal(MapleMap, java.awt.Point, Packet))
            && this(actor) && args(destination, position, packet) {
        Packet previousPacket = transitionPacket.get();
        MapleMap previousTarget = transitionTarget.get();
        transitionPacket.set(packet);
        transitionTarget.set(destination);
        try {
            proceed(actor, destination, position, packet);
        } finally {
            if (previousPacket == null) {
                transitionPacket.remove();
                transitionTarget.remove();
            } else {
                transitionPacket.set(previousPacket);
                transitionTarget.set(previousTarget);
            }
        }
    }

    // This call is inside Client's original announcerLock, so recording cannot reorder its sends.
    after(Client client, Packet packet) returning:
            call(* io.netty.channel.Channel.writeAndFlush(Object))
            && withincode(void Client.sendPacket(Packet)) && this(client) && args(packet) {
        SceneCaptureEvents.output(client, packet, true,
                transitionPacket.get() == packet ? transitionTarget.get() : null);
    }

    // Headless sources have no network channel. Keep their consumed scene outputs explicitly separate.
    after(Client client, Packet packet) returning:
            call(void SceneRuntimeManager.packet(Character, Packet)) && this(client) && args(*, packet)
            && (withincode(void BotClient.sendPacket(Packet))
                || withincode(void AcceptanceClient.sendPacket(Packet))) {
        SceneCaptureEvents.output(client, packet, false,
                transitionPacket.get() == packet ? transitionTarget.get() : null);
    }

    void around(Monster monster, Character next):
            execution(private void Monster.setController(Character)) && this(monster) && args(next) {
        if (!SceneCaptureEvents.interested(monster.getMap())) { proceed(monster, next); return; }
        Character previous = monster.getController();
        proceed(monster, next);
        SceneCaptureEvents.controller(monster, previous, next);
    }

    Integer around(Monster monster, int delta, boolean stayAlive):
            execution(Integer Monster.applyAndGetHpDamage(int, boolean))
            && this(monster) && args(delta, stayAlive) {
        if (!SceneCaptureEvents.interested(monster.getMap())) return proceed(monster, delta, stayAlive);
        int before = monster.getHp();
        Integer accounted = proceed(monster, delta, stayAlive);
        SceneCaptureEvents.monsterHp(monster, before, delta, accounted);
        return accounted;
    }

    before(Character actor, int oldHp):
            execution(void Character.hpChangeAction(int)) && this(actor) && args(oldHp) {
        SceneCaptureEvents.characterHp(actor, oldHp);
    }

    before(Monster monster, Character attacker, int accountedDamage):
            execution(private void Monster.dispatchMonsterDamaged(Character, int))
            && this(monster) && args(attacker, accountedDamage) {
        SceneCaptureEvents.monsterDamaged(monster, attacker, accountedDamage);
    }

    void around(AbstractAnimatedMapObject object, int stance):
            execution(void AbstractAnimatedMapObject.setStance(int)) && this(object) && args(stance) {
        int previous = object.getStance();
        proceed(object, stance);
        SceneCaptureEvents.stance(object, previous);
    }

    after(MapleMap map, MapObject object) returning:
            execution(void MapleMap.addMapObject(MapObject)) && this(map) && args(object) {
        SceneCaptureEvents.added(map, object, "native.map.object_add");
    }

    after(MapleMap map, MapObject object) returning:
            execution(private void MapleMap.spawnAndAddRangedMapObject(MapObject, *, *))
            && this(map) && args(object, *, *) {
        SceneCaptureEvents.added(map, object, "native.map.object_add");
    }

    void around(MapleMap map, int oid):
            execution(void MapleMap.removeMapObject(int)) && this(map) && args(oid) {
        if (!SceneCaptureEvents.interested(map)) { proceed(map, oid); return; }
        MapObject previous = map.getMapObject(oid);
        proceed(map, oid);
        SceneCaptureEvents.removed(map, previous, oid);
    }

    after(MapleMap map, Character actor) returning:
            execution(void MapleMap.addPlayer(Character)) && this(map) && args(actor) {
        SceneCaptureEvents.player(map, actor, true);
    }

    after(MapleMap map, Character actor) returning:
            execution(void MapleMap.removePlayer(Character)) && this(map) && args(actor) {
        SceneCaptureEvents.player(map, actor, false);
    }

    after(MapleMap map) returning:
            execution(void MapleMap.dispose()) && this(map) {
        SceneCaptureEvents.disposed(map);
    }

    after(Character actor) returning(AttackInfo attack):
            execution(AttackInfo AbstractDealDamageHandler.parseDamage(InPacket, Character, boolean, boolean))
            && args(*, actor, *, *) {
        SceneCaptureEvents.attackParsed(actor, attack);
    }

    void around(AttackInfo attack, Character actor, int count):
            execution(void AbstractDealDamageHandler.applyAttack(AttackInfo, Character, int))
            && args(attack, actor, count) {
        SceneCaptureEvents.ActionProbe probe = SceneCaptureEvents.action(actor, "native.attack.apply_attack", "attack_apply");
        boolean returned = false;
        try {
            proceed(attack, actor, count);
            returned = true;
        } finally {
            if (returned) SceneCaptureEvents.attackReturned(probe, attack);
            else SceneCaptureEvents.actionFailed(probe);
        }
    }

    void around(InPacket packet, Client client):
            execution(void TakeDamageHandler.handlePacket(InPacket, Client)) && args(packet, client) {
        SceneCaptureEvents.ActionProbe probe = SceneCaptureEvents.action(client.getPlayer(), "native.damage.handler", "damage_handler");
        boolean returned = false;
        try {
            proceed(packet, client);
            returned = true;
        } finally {
            if (returned) SceneCaptureEvents.actionReturned(probe);
            else SceneCaptureEvents.actionFailed(probe);
        }
    }

    before(MapleMap map, Character excluded, Packet packet, double range, java.awt.Point from):
            execution(private void MapleMap.broadcastMessage(Character, Packet, double, java.awt.Point))
            && this(map) && args(excluded, packet, range, from) {
        SceneCaptureEvents.broadcast(map, excluded, packet, range, from);
    }
}
