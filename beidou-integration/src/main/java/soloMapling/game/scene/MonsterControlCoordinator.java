package soloMapling.game.scene;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.soloacceptance.actor.AcceptanceClient;
import org.gms.server.life.Monster;

/** One qualification rule for native controller selection, handoff and movement input. */
public final class MonsterControlCoordinator {
    private MonsterControlCoordinator() { }

    public static boolean isHeadless(Character character) {
        return character.getClient() instanceof BotClient || character.getClient() instanceof AcceptanceClient;
    }

    public static boolean canControl(Monster monster, Character character) {
        if (character == null || character.getMap() != monster.getMap()) return false;
        return isHeadless(character)
                ? MonsterSceneModule.canDrive(monster, character)
                : character.isLoggedInWorld();
    }

    /** Preserve native puppet/alive/load ordering while preferring a real client over a scene driver. */
    public static Character nextController(Monster monster) {
        Character next = null;
        int bestTier = Integer.MAX_VALUE;
        int bestLoad = Integer.MAX_VALUE;
        for (var character : monster.getMap().getAllPlayers()) {
            if (character.isHidden() || !canControl(monster, character)) continue;
            boolean puppet = monster.isCharacterPuppetInVicinity(character);
            int tier = (isHeadless(character) ? 3 : 0) + (puppet ? 0 : character.isAlive() ? 1 : 2);
            int load = puppet ? 0 : character.getNumControlledMonsters();
            if (tier < bestTier || tier == bestTier && load < bestLoad) {
                next = character;
                bestTier = tier;
                bestLoad = load;
            }
        }
        return next;
    }
}
