package soloMapling.game.action;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Disease;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;

import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeMovementGeometryTest {
    private Character character;
    private MapleMap map;
    private FootholdTree tree;

    @BeforeEach void ground() {
        CharacterTestContext.install();
        character = mock(Character.class);
        map = mock(MapleMap.class);
        when(character.getHp()).thenReturn(50);
        when(character.getPosition()).thenReturn(new Point(0, 0));
        when(character.getTotalMoveSpeedStat()).thenReturn(100);
        when(character.getBuffedValue(BuffStat.MONSTER_RIDING)).thenReturn(null);
        when(character.getChair()).thenReturn(-1);
        when(map.getFootholdSpeed()).thenReturn(1.0f);
        when(map.getMapArea()).thenReturn(new Rectangle(-2000, -2000, 4000, 4000));
        tree = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        tree.insert(new Foothold(new Point(-1000, 0), new Point(1000, 0), 41));
        when(map.getFootholds()).thenReturn(tree);
    }

    @Test void actualSpeedAndElapsedTimeBoundBothDirectionsWithoutChangingTheRole() {
        var right = NativeMovementGeometry.step(character, map, "RIGHT", 100);
        var left = NativeMovementGeometry.step(character, map, "LEFT", 100);
        assertEquals(new NativeMovementGeometry.Step(0, 0, 12, 0, 41, 125), right);
        assertEquals(new NativeMovementGeometry.Step(0, 0, -12, 0, 41, -125), left);
        when(character.getTotalMoveSpeedStat()).thenReturn(140);
        assertEquals(17, NativeMovementGeometry.step(character, map, "RIGHT", 100).x());
        when(character.getTotalMoveSpeedStat()).thenReturn(900);
        assertEquals(25, NativeMovementGeometry.step(character, map, "RIGHT", 100).x());
        verify(character, never()).setPosition(any());
    }

    @Test void partialPixelTimeDoesNotManufactureDisplacement() {
        assertEquals(0, NativeMovementGeometry.step(character, map, "RIGHT", 1).x());
        assertEquals(6, NativeMovementGeometry.step(character, map, "RIGHT", 50).x());
        when(character.getTotalMoveSpeedStat()).thenReturn(55);
        assertEquals(1, NativeMovementGeometry.step(character, map, "RIGHT", 29).x());
    }

    @Test void fractionalNativeSlopeIsInterpolatedWithoutIntegerDivision() {
        tree = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        tree.insert(new Foothold(new Point(-100, -20), new Point(100, 20), 42));
        when(map.getFootholds()).thenReturn(tree);
        var step = NativeMovementGeometry.step(character, map, "RIGHT", 100);
        assertEquals(12, step.x());
        assertEquals(2, step.y());
        assertEquals(42, step.footholdId());
    }

    @Test void bothNativeWallOrientationsStopAForwardStep() {
        tree.insert(new Foothold(new Point(8, -50), new Point(8, 50), 43));
        assertReason("movement_wall_blocked", () -> NativeMovementGeometry.step(character, map, "RIGHT", 100));
    }

    @Test void walkingLeftCannotPassAReverseOrientedWall() {
        tree.insert(new Foothold(new Point(-8, 50), new Point(-8, -50), 43));
        assertReason("movement_wall_blocked", () -> NativeMovementGeometry.step(character, map, "LEFT", 100));
    }

    @Test void footholdEndDoesNotBecomeAFallOrTeleport() {
        when(character.getPosition()).thenReturn(new Point(995, 0));
        assertReason("movement_foothold_edge", () -> NativeMovementGeometry.step(character, map, "RIGHT", 100));
    }

    @Test void mapAndPacketCoordinateBoundsAreEnforced() {
        when(map.getMapArea()).thenReturn(new Rectangle(-10, -10, 20, 20));
        assertReason("movement_map_boundary", () -> NativeMovementGeometry.step(character, map, "RIGHT", 100));
        when(character.getPosition()).thenReturn(new Point(40000, 0));
        assertReason("movement_position_out_of_range", () -> NativeMovementGeometry.step(character, map, "RIGHT", 100));
    }

    @Test void airUnknownOrSteepTerrainDoesNotInventGround() {
        when(character.getPosition()).thenReturn(new Point(0, -30));
        assertReason("movement_not_grounded", () -> NativeMovementGeometry.step(character, map, "RIGHT", 100));
        when(character.getPosition()).thenReturn(new Point(0, 0));
        when(map.getFootholds()).thenReturn(null);
        assertReason("movement_geometry_unavailable", () -> NativeMovementGeometry.step(character, map, "RIGHT", 100));
        tree = new FootholdTree(new Point(-2000, -2000), new Point(2000, 2000));
        tree.insert(new Foothold(new Point(-100, -100), new Point(100, 100), 44));
        when(map.getFootholds()).thenReturn(tree);
        assertReason("movement_slope_unsupported", () -> NativeMovementGeometry.step(character, map, "RIGHT", 100));
    }

    @Test void swimmingMountsAirAndLadderStatesRemainOutsideGroundWalking() {
        when(map.isSwim()).thenReturn(true);
        assertReason("movement_mode_unsupported", () -> NativeMovementGeometry.step(character, map, "RIGHT", 100));
        when(map.isSwim()).thenReturn(false);
        when(character.getBuffedValue(BuffStat.MONSTER_RIDING)).thenReturn(1);
        assertReason("movement_mode_unsupported", () -> NativeMovementGeometry.step(character, map, "RIGHT", 100));
        when(character.getBuffedValue(BuffStat.MONSTER_RIDING)).thenReturn(null);
        when(character.getStance()).thenReturn(14);
        assertReason("movement_mode_unsupported", () -> NativeMovementGeometry.step(character, map, "RIGHT", 100));
    }

    @Test void unusualSurfaceDeadActorAndUnknownInputsAreExplicitlyRejected() {
        when(map.getFootholdSpeed()).thenReturn(0.5f);
        assertReason("movement_surface_unsupported", () -> NativeMovementGeometry.step(character, map, "RIGHT", 100));
        when(map.getFootholdSpeed()).thenReturn(1.0f);
        when(character.getHp()).thenReturn(0);
        assertReason("movement_actor_dead", () -> NativeMovementGeometry.step(character, map, "RIGHT", 100));
        when(character.getHp()).thenReturn(50);
        assertReason("invalid_movement_direction", () -> NativeMovementGeometry.step(character, map, "UP", 100));
        assertReason("invalid_movement_interval", () -> NativeMovementGeometry.step(character, map, "RIGHT", 0));
        assertReason("invalid_movement_interval", () -> NativeMovementGeometry.step(character, map, "RIGHT", 101));
    }

    @Test void ordinaryPortalSpawnLandsNineteenPixelsInTwoBoundedVerticalTicks() {
        when(character.getPosition()).thenReturn(new Point(0, -19));
        when(character.getStance()).thenReturn(4);
        assertTrue(NativeMovementGeometry.needsSpawnSettle(character, map));
        var first = NativeMovementGeometry.settle(character, map, 100, 0);
        assertEquals(new NativeMovementGeometry.Settlement(0, -9, 41, 200, false), first);
        when(character.getPosition()).thenReturn(new Point(first.x(), first.y()));
        var second = NativeMovementGeometry.settle(character, map, 100, first.verticalVelocity());
        assertEquals(new NativeMovementGeometry.Settlement(0, 0, 41, 0, true), second);
        when(character.getPosition()).thenReturn(new Point(second.x(), second.y()));
        assertFalse(NativeMovementGeometry.needsSpawnSettle(character, map));
        assertEquals(12, NativeMovementGeometry.step(character, map, "RIGHT", 100).x());
        verify(character, never()).setPosition(any());
    }

    @Test void portalSettlementNeverBecomesArbitraryFallingOrAnUpwardSnap() {
        when(character.getPosition()).thenReturn(new Point(0, -25));
        assertReason("movement_spawn_settle_unsupported", () -> NativeMovementGeometry.settle(character, map, 100, 0));
        assertReason("movement_spawn_settle_unsupported", () -> NativeMovementGeometry.needsSpawnSettle(character, map));
        when(character.getPosition()).thenReturn(new Point(0, 2));
        assertFalse(NativeMovementGeometry.needsSpawnSettle(character, map));
        assertReason("movement_spawn_settle_unsupported", () -> NativeMovementGeometry.settle(character, map, 100, 0));
        when(character.getPosition()).thenReturn(new Point(0, -5));
        assertFalse(NativeMovementGeometry.needsSpawnSettle(character, map));
    }

    @Test void portalSettlementKeepsXAndRespectsGravityBudgetAndMapBounds() {
        when(character.getPosition()).thenReturn(new Point(11, -24));
        var first = NativeMovementGeometry.settle(character, map, 50, 0);
        assertEquals(new NativeMovementGeometry.Settlement(11, -22, 41, 100, false), first);
        var partial = NativeMovementGeometry.settle(character, map, 1, 0);
        assertEquals(-24, partial.y());
        assertFalse(partial.landed());
        when(map.getMapArea()).thenReturn(new Rectangle(-20, -30, 40, 30));
        assertReason("movement_map_boundary", () -> NativeMovementGeometry.settle(character, map, 100, 0));
        assertReason("invalid_movement_settle_velocity", () -> NativeMovementGeometry.settle(character, map, 100, -1));
        assertReason("invalid_movement_settle_velocity", () -> NativeMovementGeometry.settle(character, map, 100, 201));
    }

    @Test void portalSettlementDoesNotPermitSwimmingMountedOrAirborneRoles() {
        when(character.getPosition()).thenReturn(new Point(0, -19));
        when(map.isSwim()).thenReturn(true);
        assertReason("movement_mode_unsupported", () -> NativeMovementGeometry.settle(character, map, 100, 0));
        when(map.isSwim()).thenReturn(false);
        when(character.getBuffedValue(BuffStat.MONSTER_RIDING)).thenReturn(1);
        assertReason("movement_mode_unsupported", () -> NativeMovementGeometry.settle(character, map, 100, 0));
        when(character.getBuffedValue(BuffStat.MONSTER_RIDING)).thenReturn(null);
        when(character.getStance()).thenReturn(6);
        assertReason("movement_mode_unsupported", () -> NativeMovementGeometry.settle(character, map, 100, 0));
    }

    @Test void immobilizedRolesCannotWalkOrUsePortalSettlementToBypassTheirNativeState() {
        when(character.hasDisease(Disease.STUN)).thenReturn(true);
        assertReason("movement_actor_immobilized", () -> NativeMovementGeometry.step(character, map, "RIGHT", 100));
        assertReason("movement_actor_immobilized", () -> NativeMovementGeometry.settle(character, map, 100, 0));
        when(character.hasDisease(Disease.STUN)).thenReturn(false);
        when(character.hasDisease(Disease.SEDUCE)).thenReturn(true);
        assertReason("movement_actor_immobilized", () -> NativeMovementGeometry.step(character, map, "RIGHT", 100));
        assertReason("movement_actor_immobilized", () -> NativeMovementGeometry.settle(character, map, 100, 0));
        when(character.hasDisease(Disease.SEDUCE)).thenReturn(false);
        // Native sitChair accepts map chair index zero; only -1 means standing.
        when(character.getChair()).thenReturn(0);
        assertReason("movement_actor_immobilized", () -> NativeMovementGeometry.step(character, map, "RIGHT", 100));
        assertReason("movement_actor_immobilized", () -> NativeMovementGeometry.settle(character, map, 100, 0));
        verify(character, never()).setPosition(any());
    }

    @Test void diseaseSlowIsRejectedWhileItsClientFormulaHasNoVerifiedSharedAdapter() {
        when(character.hasDisease(Disease.SLOW)).thenReturn(true);
        assertReason("movement_slow_unsupported", () -> NativeMovementGeometry.step(character,map,"RIGHT",100));
        assertReason("movement_slow_unsupported", () -> NativeMovementGeometry.settle(character,map,100,0));
        verify(character,never()).setPosition(any());
    }
    @Test void theActualSharedSpeedStatStillBoundsNonDiseaseSpeedBuffs() {
        when(character.getTotalMoveSpeedStat()).thenReturn(80);
        var step = NativeMovementGeometry.step(character,map,"RIGHT",100);
        assertEquals(100,step.velocity()); assertEquals(10,step.x());
        verify(character,never()).setPosition(any());
    }

    private static void assertReason(String reason, Runnable body) {
        assertEquals(reason, assertThrows(NativeActionService.Invalid.class, body::run).reason());
    }
}
