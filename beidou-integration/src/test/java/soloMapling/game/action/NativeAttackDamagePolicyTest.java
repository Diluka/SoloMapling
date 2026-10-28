package soloMapling.game.action;

import org.gms.client.inventory.WeaponType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeAttackDamagePolicyTest {
    private static NativeAttackDamagePolicy.Attributes attributes(int maximum, double accuracy, int level, int evade, int defense) {
        return new NativeAttackDamagePolicy.Attributes(1302000, WeaponType.SWORD1H, maximum, accuracy, 10, level, evade, defense);
    }

    @Test void nativeAttributeBoundAndDefenceControlTheDamageInsteadOfBotJobTiers() {
        var base = NativeAttackDamagePolicy.roll(attributes(100, 100, 10, 0, 0), 0.5, 0.5);
        var defended = NativeAttackDamagePolicy.roll(attributes(100, 100, 10, 0, 20), 0.5, 0.5);
        var stronger = NativeAttackDamagePolicy.roll(attributes(200, 100, 10, 0, 0), 0.5, 0.5);
        assertTrue(base.damage() > defended.damage()); assertTrue(stronger.damage() > base.damage());
        assertTrue(base.damage() >= 1 && base.damage() <= 100);
        assertEquals(1, NativeAttackDamagePolicy.roll(attributes(100, 100, 10, 0, 1000), 0.5, 0.5).damage());
    }

    @Test void targetEvadeAndLevelGapAffectHitProbabilityAndAnActualMissRollCarriesZeroDamage() {
        var ordinary = NativeAttackDamagePolicy.roll(attributes(100, 20, 10, 6, 0), 0, 0.5);
        var highLevel = NativeAttackDamagePolicy.roll(attributes(100, 20, 40, 6, 0), 0.99, 0.5);
        assertTrue(ordinary.hitChance() > highLevel.hitChance());
        assertFalse(highLevel.hitRollPassed()); assertEquals(0, highLevel.damage());
        assertEquals(1, NativeAttackDamagePolicy.roll(attributes(100, 0, 10, 0, 0), 0.99, 0.5).hitChance());
    }

    @Test void extremeNativeBoundsCannotOverflowOrLetTheRollExceedTheNativeMaximum() {
        for (double roll : new double[]{0, 0.1, 0.5, Math.nextDown(1.0)}) {
            var damage = NativeAttackDamagePolicy.roll(attributes(Integer.MAX_VALUE, 100, 10, 0, 0), 0, roll).damage();
            assertTrue(damage >= 1 && damage <= Integer.MAX_VALUE);
        }
        assertThrows(NativeActionService.Invalid.class,
                () -> NativeAttackDamagePolicy.roll(attributes(1, 100, 10, 0, 0), Double.NaN, 0));
    }
}
