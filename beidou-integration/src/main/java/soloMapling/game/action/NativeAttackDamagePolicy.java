package soloMapling.game.action;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Disease;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.WeaponType;
import org.gms.server.ItemInformationProvider;
import org.gms.server.life.Monster;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Trusted physical calculation for basic_melee_v1, not the complete v83 client calculator.
 * BeiDou trusts client damage and only checks its upper bound; this backend therefore
 * uses its actual calculateMaxBaseDamage(totalWatk), DEX/LUK, equipped ACC and ACC buff,
 * and the target's level/eva/PDDamage. It deliberately has no job-tier Bot damage band,
 * skills, projectiles, critical rolls or complex mastery/charge/buff multipliers.
 */
final class NativeAttackDamagePolicy {
    static final String ID = "basic_melee_v1";
    static final List<String> WEAPONS = List.of("GENERAL1H_SWING", "GENERAL1H_STAB", "GENERAL2H_SWING",
            "GENERAL2H_STAB", "DAGGER_THIEVES", "DAGGER_OTHER", "KNUCKLE", "POLE_ARM_SWING",
            "POLE_ARM_STAB", "SPEAR_STAB", "SPEAR_SWING", "STAFF", "SWORD1H", "SWORD2H", "WAND");

    private NativeAttackDamagePolicy() { }

    record Attributes(int weaponItemId, WeaponType weaponType, int maximumDamage, double accuracy,
                      int actorLevel, int targetLevel, int targetAvoid, int targetDefense) { }
    record Roll(Attributes attributes, double hitChance, boolean hitRollPassed, int damage) { }

    static Attributes capture(Character actor, Monster target) {
        Inventory equipped = actor.getInventory(InventoryType.EQUIPPED);
        if (equipped == null) throw new NativeActionService.Invalid("attack_weapon_unavailable");
        int weaponItemId;
        WeaponType type;
        int equipmentAccuracy = 0;
        equipped.lockInventory();
        try {
            Item weapon = equipped.getItem((short) -11);
            if (!(weapon instanceof Equip)) throw new NativeActionService.Invalid("attack_weapon_unavailable");
            weaponItemId = weapon.getItemId();
            type = ItemInformationProvider.getInstance().getWeaponType(weaponItemId);
            if (type == null || !WEAPONS.contains(type.name())) {
                throw new NativeActionService.Invalid("attack_weapon_not_supported");
            }
            for (Item item : equipped.list()) if (item instanceof Equip equip) equipmentAccuracy += equip.getAcc();
        } finally { equipped.unlockInventory(); }
        // Native buff/stat recalculation takes role locks before the inventory lock. Never
        // acquire those role locks while holding EQUIPPED, or a buff expiry could deadlock.
        int maximum = actor.calculateMaxBaseDamage(actor.getTotalWatk(), type);
        if (maximum < 1 || actor.getLevel() < 1 || target.getStats() == null) {
            throw new NativeActionService.Invalid("attack_attributes_unavailable");
        }
        double accuracy = Math.max(0, actor.getTotalDex()) * 0.8 + Math.max(0, actor.getTotalLuk()) * 0.5 + equipmentAccuracy;
        Integer buff = actor.getBuffedValue(BuffStat.ACC);
        if (buff != null) accuracy += buff;
        if (actor.hasDisease(Disease.DARKNESS)) accuracy *= 0.5;
        return new Attributes(weaponItemId, type, maximum, Math.max(0, accuracy), actor.getLevel(),
                target.getStats().getLevel(), Math.max(0, target.getStats().eva), Math.max(0, target.getStats().PDDamage));
    }

    static Roll roll(Attributes attributes) {
        return roll(attributes, ThreadLocalRandom.current().nextDouble(), ThreadLocalRandom.current().nextDouble());
    }

    /** Package test seam for two independent server rolls; external callers cannot choose a damage number. */
    static Roll roll(Attributes a, double hitRoll, double damageRoll) {
        if (a.maximumDamage() < 1 || !Double.isFinite(a.accuracy()) || a.accuracy() < 0
                || !Double.isFinite(hitRoll) || !Double.isFinite(damageRoll)
                || hitRoll < 0 || hitRoll >= 1 || damageRoll < 0 || damageRoll >= 1) {
            throw new NativeActionService.Invalid("attack_attributes_unavailable");
        }
        int levelDifference = Math.max(0, a.targetLevel() - a.actorLevel());
        // Explicit physical policy: the level-gap factor raises required accuracy, and evade=0 always hits.
        double chance = a.targetAvoid() == 0 ? 1 : Math.clamp(
                a.accuracy() / ((1.84 + 0.07 * levelDifference) * a.targetAvoid()) - 1, 0, 1);
        boolean hit = hitRoll < chance;
        // No mastery table is claimed here: roll 10..100% of the native base bound, then apply
        // the target's physical defence and a bounded level-gap penalty. Damage never exceeds that bound.
        double base = a.maximumDamage() * (0.1 + 0.9 * damageRoll);
        double levelFactor = Math.max(0.01, 1.0 - 0.01 * levelDifference);
        int damage = hit ? Math.max(1, Math.min(a.maximumDamage(), (int) Math.floor(
                base * levelFactor - a.targetDefense() * 0.5))) : 0;
        return new Roll(a, chance, hit, damage);
    }
}
