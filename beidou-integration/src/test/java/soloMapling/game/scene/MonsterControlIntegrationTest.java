package soloMapling.game.scene;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.coordinator.world.MonsterAggroCoordinator;
import org.gms.server.life.Monster;
import org.gms.server.life.MonsterStats;
import org.gms.server.maps.MapleMap;
import org.gms.soloacceptance.actor.AcceptanceClient;
import org.gms.util.PacketCreator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the woven native controller lifecycle; no world, account or database is started. */
class MonsterControlIntegrationTest {
    @BeforeAll static void context() { CharacterTestContext.install(); }

    @Test void damageFromAHeadlessAttackerRetainsTheRealClientsControllerAndMovementInput() {
        var map = mock(MapleMap.class);
        var aggro = mock(MonsterAggroCoordinator.class);
        when(map.getAggroCoordinator()).thenReturn(aggro);
        var player = character(map, mock(Client.class), 2, true);
        var attacker = character(map, mock(BotClient.class), 90, false);
        var monster = monster(map);
        when(aggro.isLeadingCharacterAggro(monster, attacker)).thenReturn(true);
        try (var packets = mockStatic(PacketCreator.class); var drivers = mockStatic(MonsterSceneModule.class)) {
            monster.aggroSwitchController(player, false);
            clearInvocations(player);

            monster.aggroMonsterDamage(attacker, 10);

            assertSame(player, monster.getController());
            assertEquals(Boolean.TRUE, monster.aggroMoveLifeUpdate(player));
            verify(aggro).addAggroDamage(monster, attacker.getId(), 10);

            drivers.when(() -> MonsterSceneModule.canDrive(monster, attacker)).thenReturn(true);
            monster.aggroMonsterDamage(attacker, 20);
            assertSame(player, monster.getController());
            assertEquals(Boolean.TRUE, monster.aggroMoveLifeUpdate(player));
            verify(player, never()).stopControllingMonster(monster);
            verify(attacker, never()).controlMonster(monster);
        }
    }

    @Test void observingAcceptanceClientDoesNotTakeAControllerDuringCandidateSelectionOrDirectHandoff() {
        var map = mock(MapleMap.class);
        var observer = character(map, mock(AcceptanceClient.class), 4, true);
        var player = character(map, mock(Client.class), 2, true);
        when(player.getNumControlledMonsters()).thenReturn(17);
        when(map.getAllPlayers()).thenReturn(List.of(observer, player));
        var monster = monster(map);
        try (var packets = mockStatic(PacketCreator.class); var drivers = mockStatic(MonsterSceneModule.class)) {
            monster.aggroUpdateController();
            monster.aggroSwitchController(observer, false);

            assertSame(player, monster.getController());
            assertNotNull(monster.aggroMoveLifeUpdate(player));
            assertNull(monster.aggroMoveLifeUpdate(observer));
            verify(observer, never()).controlMonster(monster);
        }
    }

    @Test void anAvailableSceneDriverCanOwnAControllerAndReleasesItsNativeHandleAfterStopping() {
        var map = mock(MapleMap.class);
        var driver = character(map, mock(BotClient.class), 90, false);
        var player = character(map, mock(Client.class), 2, true);
        when(map.getAllPlayers()).thenReturn(List.of(driver));
        var monster = monster(map);
        try (var packets = mockStatic(PacketCreator.class); var drivers = mockStatic(MonsterSceneModule.class)) {
            drivers.when(() -> MonsterSceneModule.canDrive(monster, driver)).thenReturn(true);
            monster.aggroUpdateController();
            assertSame(driver, monster.getController());
            assertNotNull(monster.aggroMoveLifeUpdate(driver));

            drivers.when(() -> MonsterSceneModule.canDrive(monster, driver)).thenReturn(false);
            when(map.getAllPlayers()).thenReturn(List.of(driver, player));
            assertNull(monster.aggroMoveLifeUpdate(driver));
            monster.aggroRedirectController();

            verify(driver).stopControllingMonster(monster);
            assertSame(player, monster.getController());
            assertNotNull(monster.aggroMoveLifeUpdate(player));
        }
    }

    @Test void nativeMonsterRemovalStillClearsItsControllerExplicitly() {
        var map = mock(MapleMap.class);
        var player = character(map, mock(Client.class), 2, true);
        var monster = monster(map);
        try (var packets = mockStatic(PacketCreator.class)) {
            monster.aggroSwitchController(player, false);
            when(player.getMap()).thenReturn(mock(MapleMap.class));
            monster.aggroSwitchController(null, false);
            assertNull(monster.getController());
            verify(player).stopControllingMonster(monster);
            verify(player, never()).sendPacket(any());
        }
    }

    private static Character character(MapleMap map, Client client, int id, boolean loggedIn) {
        var character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getMap()).thenReturn(map);
        when(character.getClient()).thenReturn(client);
        when(character.isAlive()).thenReturn(true);
        when(character.isLoggedInWorld()).thenReturn(loggedIn);
        return character;
    }

    private static Monster monster(MapleMap map) {
        var stats = new MonsterStats();
        stats.hp = 100;
        var monster = spy(new Monster(100101, stats));
        monster.setObjectId(501);
        monster.setMap(map);
        doNothing().when(monster).aggroUpdatePuppetVisibility();
        return monster;
    }
}
