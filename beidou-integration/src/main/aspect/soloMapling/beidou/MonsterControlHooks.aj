package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.server.life.Monster;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;
import soloMapling.game.scene.MonsterControlCoordinator;

/** Adapt the immutable native controller contract to actual client-driving capability. */
public privileged aspect MonsterControlHooks {
    Character around(Monster monster):
            execution(private Character Monster.getNextControllerCandidate()) && this(monster) {
        return MonsterControlCoordinator.nextController(monster);
    }

    Character around(Monster monster):
            execution(private Character Monster.getActiveController()) && this(monster) {
        Character controller = monster.getController();
        return MonsterControlCoordinator.canControl(monster, controller) ? controller : null;
    }

    // Cleanup owns the original handle after its driver stops or the character leaves the map.
    Pair<Character, Boolean> around(Monster monster):
            execution(Pair Monster.aggroRemoveController()) && this(monster) {
        Character previous;
        boolean hadAggro;
        monster.aggroUpdateLock.lock();
        try {
            previous = monster.getController();
            hadAggro = monster.isControllerHasAggro();
            monster.setController(null);
            monster.setControllerHasAggro(false);
            monster.setControllerKnowsAboutAggro(false);
        } finally {
            monster.aggroUpdateLock.unlock();
        }
        if (previous != null) {
            if (!monster.isFake() && previous.getMap() == monster.getMap()
                    && (MonsterControlCoordinator.isHeadless(previous) || previous.isLoggedInWorld())) {
                previous.sendPacket(PacketCreator.stopControllingMonster(monster.getObjectId()));
            }
            previous.stopControllingMonster(monster);
        }
        return new Pair<Character, Boolean>(previous, Boolean.valueOf(hadAggro));
    }

    void around(Monster monster, Character next, boolean immediateAggro):
            execution(void Monster.aggroSwitchController(Character, boolean))
            && this(monster) && args(next, immediateAggro) {
        if (!monster.aggroUpdateLock.tryLock()) return;
        boolean switched = false;
        boolean refreshed = false;
        try {
            Character previous = monster.getController();
            if (previous == next) return;
            if (next == null) {
                monster.aggroRemoveController();
                return;
            }
            boolean previousValid = MonsterControlCoordinator.canControl(monster, previous);
            if (!MonsterControlCoordinator.canControl(monster, next)
                    || MonsterControlCoordinator.isHeadless(next) && previousValid
                    && !MonsterControlCoordinator.isHeadless(previous)) {
                if (previousValid && immediateAggro) {
                    monster.setControllerHasAggro(true);
                    refreshed = true;
                }
            } else {
                // Eligibility is decided under the original native lock before removing the old controller.
                monster.aggroRemoveController();
                monster.setController(next);
                monster.setControllerHasAggro(immediateAggro);
                monster.setControllerKnowsAboutAggro(false);
                monster.setControllerHasPuppet(false);
                switched = true;
            }
        } finally {
            monster.aggroUpdateLock.unlock();
        }
        if (switched) {
            monster.aggroUpdatePuppetVisibility();
            Monster.aggroMonsterControl(next.getClient(), monster, immediateAggro);
            next.controlMonster(monster);
        } else if (refreshed) {
            monster.aggroUpdatePuppetVisibility();
        }
    }

    Boolean around(Monster monster, Character character):
            execution(Boolean Monster.aggroMoveLifeUpdate(Character))
            && this(monster) && args(character) {
        if (monster.getController() != character || !MonsterControlCoordinator.canControl(monster, character)) {
            return null;
        }
        return proceed(monster, character);
    }
}
