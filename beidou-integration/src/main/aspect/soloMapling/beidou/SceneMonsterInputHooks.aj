package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.server.life.Monster;
import soloMapling.game.scene.MonsterSceneModule;

/** Observe native resolved damage once; the scene only schedules subsequent client recoil input. */
public aspect SceneMonsterInputHooks {
    after(Monster monster, Character attacker, int damage) returning:
            execution(private void Monster.dispatchMonsterDamaged(Character, int))
            && this(monster) && args(attacker, damage) {
        MonsterSceneModule.damaged(monster, attacker, damage);
    }
}
