package soloMapling.game.action;

import io.netty.buffer.Unpooled;
import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.net.server.Server;
import org.gms.net.server.channel.handlers.TakeDamageHandler;
import org.gms.server.life.Monster;
import soloMapling.ArtificialPlayer.GCMoveSystem.BotMobHitboxProvider;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;

import java.awt.Rectangle;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Trusted body contact on the existing role lane; native damage owns shields, HP/MP and death. */
public final class NativeContactDamageActions {
    public static final String POLICY_ID = "contact_physical_v1";

    private NativeContactDamageActions() { }

    public record ContactResult(String policy, boolean dispatched, int monsterOid, int monsterId,
                                int monsterPhysicalAttack, int physicalDefense, int computedDamage,
                                int hpBefore, int hpAfter, int mpBefore, int mpAfter,
                                boolean dead, boolean handlerError, boolean cancelRequested) {
        public int hpLost() { return Math.max(0, hpBefore - hpAfter); }
        public int mpLost() { return Math.max(0, mpBefore - mpAfter); }

        public Map<String, Object> toMap() {
            var result = new LinkedHashMap<String, Object>();
            result.put("policy", policy);
            result.put("dispatched", dispatched);
            result.put("monster_oid", monsterOid);
            result.put("monster_id", monsterId);
            result.put("monster_physical_attack", monsterPhysicalAttack);
            result.put("physical_defense", physicalDefense);
            result.put("computed_damage", computedDamage);
            result.put("hp_before", hpBefore);
            result.put("hp_after", hpAfter);
            result.put("mp_before", mpBefore);
            result.put("mp_after", mpAfter);
            result.put("hp_lost", hpLost());
            result.put("mp_lost", mpLost());
            result.put("dead", dead);
            result.put("handler_error", handlerError);
            result.put("cancel_requested", cancelRequested);
            return Map.copyOf(result);
        }
    }

    /** Caller owns the role-wide contact cadence and is already executing its short lane work. */
    public static ContactResult contact(Server server, GameContext.SceneRef scene,
                                        ExecutionScope.WorkHandle invocationWork,
                                        ExecutionScope.WorkHandle contactWork, long deadlineNanos) {
        if (invocationWork.cancelRequested() || contactWork.cancelRequested()
                || System.nanoTime() >= deadlineNanos) return null;
        NativeContextChecks.requireSceneCurrent(server, scene);
        var actor = scene.actor().character();
        if (!actor.isAlive() || actor.isHidden()) return null;
        var position = actor.getPosition();
        var target = scene.map().getAllMonsters().stream()
                .filter(monster -> monster.getMap() == scene.map() && monster.isAlive()
                        && !monster.getStats().isFriendly() && monster.getPADamage() > 0)
                .filter(monster -> touching(actor, monster))
                .min(Comparator.comparingDouble((Monster monster) -> monster.getPosition().distanceSq(position))
                        .thenComparingInt(Monster::getObjectId)).orElse(null);
        if (target == null) return null;

        int defense;
        var equipped = actor.getInventory(InventoryType.EQUIPPED);
        equipped.lockInventory();
        try {
            defense = equipped.list().stream().filter(Equip.class::isInstance).map(Equip.class::cast)
                    .mapToInt(Equip::getWdef).sum();
        } finally { equipped.unlockInventory(); }
        // Native buff/stat recomputation takes role locks before the inventory lock.
        Integer buffDefense = actor.getBuffedValue(BuffStat.WDEF);
        defense = Math.max(0, defense + (buffDefense == null ? 0 : buffDefense));
        int physicalAttack = target.getPADamage();
        // Explicit bounded policy, not the complete v83 client physical defence/avoid calculator.
        int damage = Math.max(1, physicalAttack - defense / 2);
        int hpBefore = actor.getHp(), mpBefore = actor.getMp();
        var dispatched = new AtomicBoolean();
        boolean handlerError;
        try {
            handlerError = NativePacketDispatch.dispatch(scene.actor().client(), new TakeDamageHandler(),
                Unpooled.buffer(19).writeIntLE((int) server.getCurrentTime()).writeByte(-1).writeByte(0)
                        .writeIntLE(damage).writeIntLE(target.getId()).writeIntLE(target.getObjectId())
                        .writeByte(target.getPosition().x > position.x ? 0 : 1),
                () -> {
                    if (invocationWork.cancelRequested() || contactWork.cancelRequested()
                            || System.nanoTime() >= deadlineNanos) {
                        throw new CancellationException("contact_cancelled_before_dispatch");
                    }
                    NativeContextChecks.requireSceneCurrent(server, scene);
                    if (!actor.isAlive() || actor.isHidden()
                            || scene.map().getMonsterByOid(target.getObjectId()) != target
                            || !target.isAlive() || !touching(actor, target)) {
                        throw new NativeActionService.Invalid("contact_source_changed");
                    }
                }, () -> dispatched.set(true));
        } catch (NativeActionService.Invalid changed) {
            if (!dispatched.get() && changed.reason().equals("contact_source_changed")) return null;
            throw changed;
        }
        return new ContactResult(POLICY_ID, dispatched.get(), target.getObjectId(), target.getId(),
                physicalAttack, defense, damage, hpBefore, actor.getHp(), mpBefore, actor.getMp(),
                !actor.isAlive(), handlerError, invocationWork.cancelRequested() || contactWork.cancelRequested());
    }

    private static boolean touching(Character actor, Monster monster) {
        var bounds = BotMobHitboxProvider.getMobBounds(monster);
        if (bounds == null) return false;
        int lowerHeight = Math.max(1, bounds.height / 2);
        var lowerBody = new Rectangle(bounds.x, bounds.y + bounds.height - lowerHeight, bounds.width, lowerHeight);
        var point = actor.getPosition();
        return lowerBody.intersects(new Rectangle(point.x, point.y - 50, 1, 51));
    }
}
