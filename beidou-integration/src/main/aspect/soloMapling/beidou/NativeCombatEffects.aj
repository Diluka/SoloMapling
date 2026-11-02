package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.client.autoban.AutobanFactory;
import org.gms.client.autoban.AutobanManager;
import org.gms.net.server.channel.handlers.AbstractDealDamageHandler;
import org.gms.server.StatEffect;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;
import soloMapling.game.action.NativeCombatEvidence;

/** Finite Solo combat casts reuse native resource calculations and native damage evidence. */
public privileged aspect NativeCombatEffects {
    public static int hpChange(StatEffect effect, Character actor) {
        return effect.calcHPChange(actor, true, 1);
    }

    public static int mpChange(StatEffect effect, Character actor) {
        return effect.calcMPChange(actor, true);
    }

    after(StatEffect effect, Character actor) returning(boolean applied):
            execution(boolean StatEffect.applyTo(Character)) && this(effect) && args(actor) {
        NativeCombatEvidence.effectReturned(effect, actor, applied);
    }

    void around(AutobanFactory factory, AutobanManager manager, String reason):
            call(void AutobanFactory.addPoint(AutobanManager, String))
            && withincode(void AbstractDealDamageHandler.applyAttack(..))
            && target(factory) && args(manager, reason) {
        if (factory == AutobanFactory.MPCON && NativeCombatEvidence.nativeMpCostSatisfied(manager)) return;
        proceed(factory, manager, reason);
    }

    after(StatEffect effect, Character actor) returning(boolean applied):
            execution(boolean StatEffect.applyTo(Character, java.awt.Point)) && this(effect) && args(actor, *) {
        NativeCombatEvidence.effectReturned(effect, actor, applied);
    }

    boolean around(MapleMap map, Character actor, Monster target, int damage):
            execution(boolean MapleMap.damageMonster(Character, Monster, int))
            && this(map) && args(actor, target, damage) {
        if (NativeCombatEvidence.rejectDamage(actor, target)) return false;
        NativeCombatEvidence.Observation observation = NativeCombatEvidence.beginDamage(actor, target);
        try { return proceed(map, actor, target, damage); }
        finally { NativeCombatEvidence.endDamage(observation); }
    }
}
