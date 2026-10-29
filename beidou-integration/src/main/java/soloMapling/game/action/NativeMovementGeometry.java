package soloMapling.game.action;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Disease;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import soloMapling.ArtificialPlayer.GCMoveSystem.GroundRules;
import soloMapling.ArtificialPlayer.GCMoveSystem.RuntimeNavigationEngine;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Line2D;

/** Native movement eligibility and conservative ground-walk geometry, without changing the role. */
public final class NativeMovementGeometry {
    private NativeMovementGeometry() { }

    record Settlement(int x, int y, int footholdId, int verticalVelocity, boolean landed) { }

    /** Only the small gap at a normal portal spawn may precede the ground-walk phase. */
    static boolean needsSpawnSettle(Character character, MapleMap map) {
        return spawnGround(character, map, true).gap() > 5;
    }

    /** A bounded vertical step; the caller owns the at-most-four-tick settlement phase. */
    static Settlement settle(Character character, MapleMap map, int previousVerticalVelocity, int elapsedMs) {
        SpawnGround ground = spawnGround(character, map, false);
        int velocity = previousVerticalVelocity + 2000 * elapsedMs / 1000;
        int distance = (int) Math.round((previousVerticalVelocity + velocity) * elapsedMs / 2000.0);
        int y = ground.y() + Math.min(ground.gap(), distance);
        boolean landed = y == ground.groundY();
        return new Settlement(ground.x(), y, ground.footholdId(), landed ? 0 : velocity, landed);
    }

    record GroundBounds(int minimumX, int maximumX, String leftReason, String rightReason) { }

    /** One basic hold keeps its conservative native footprint throughout input and stop prediction. */
    static GroundBounds groundBounds(Character character, MapleMap map) {
        requireOrdinaryGroundMode(character, map);
        Point position = character.getPosition();
        require(position != null, "movement_position_unavailable");
        require(shortRange(position.x) && shortRange(position.y), "movement_position_out_of_range");
        FootholdTree tree = map.getFootholds();
        require(tree != null, "movement_geometry_unavailable");
        Foothold ground = tree.findBelow(new Point(position.x, position.y - 5));
        require(ground != null && !ground.isWall() && ground.getX2() > ground.getX1(), "movement_not_grounded");
        require(Math.abs((double) ground.getY2() - ground.getY1()) / (ground.getX2() - ground.getX1()) <= 0.5,
                "movement_slope_unsupported");
        require(Math.abs((long) position.y - height(ground, position.x)) <= 5, "movement_not_grounded");
        Rectangle area = map.getMapArea();
        require(area != null && area.width > 0 && area.height > 0 && area.contains(position), "movement_map_boundary");
        int minimum = Math.max(ground.getX1(), Short.MIN_VALUE);
        int maximum = Math.min(ground.getX2(), Short.MAX_VALUE);
        String leftReason = minimum == ground.getX1() ? "movement_foothold_edge" : "movement_position_out_of_range";
        String rightReason = maximum == ground.getX2() ? "movement_foothold_edge" : "movement_position_out_of_range";
        if (area.x > minimum) { minimum = area.x; leftReason = "movement_map_boundary"; }
        int right = (int) Math.min(Short.MAX_VALUE, (long) area.x + area.width - 1);
        if (right < maximum) { maximum = right; rightReason = "movement_map_boundary"; }
        for (Foothold wall : tree.getAllFootholds()) {
            if (!wall.isWall() || wall.getX1() < ground.getX1() || wall.getX1() > ground.getX2()) continue;
            int wallY = height(ground, wall.getX1());
            if (wallY < Math.min(wall.getY1(), wall.getY2()) || wallY > Math.max(wall.getY1(), wall.getY2())) continue;
            require(wall.getX1() != position.x, "movement_wall_blocked");
            if (wall.getX1() < position.x && wall.getX1() + 1 > minimum) {
                minimum = wall.getX1() + 1; leftReason = "movement_wall_blocked";
            } else if (wall.getX1() > position.x && wall.getX1() - 1 < maximum) {
                maximum = wall.getX1() - 1; rightReason = "movement_wall_blocked";
            }
        }
        require(position.x >= minimum && position.x <= maximum, "movement_geometry_unavailable");
        return new GroundBounds(minimum, maximum, leftReason, rightReason);
    }

    /** The shared physical frame is still constrained to this basic action's current foothold. */
    static void requireGroundStep(Character character, MapleMap map, Point before,
                                  RuntimeNavigationEngine.Frame frame) {
        Point after = frame.position();
        require(shortRange(before.x) && shortRange(before.y) && shortRange(after.x) && shortRange(after.y),
                "movement_position_out_of_range");
        FootholdTree tree = map.getFootholds();
        require(tree != null, "movement_geometry_unavailable");
        Foothold ground = tree.findBelow(new Point(before.x, before.y - 5));
        require(ground != null && !ground.isWall() && ground.getX2() > ground.getX1(), "movement_not_grounded");
        require(Math.abs((double) ground.getY2() - ground.getY1()) / (ground.getX2() - ground.getX1()) <= 0.5,
                "movement_slope_unsupported");
        require(Math.abs((long) before.y - height(ground, before.x)) <= 5, "movement_not_grounded");
        require(frame.footholdId() == ground.getId() && after.x >= ground.getX1() && after.x <= ground.getX2(),
                "movement_foothold_edge");
        require(Math.abs((long) after.y - height(ground, after.x)) <= 1, "movement_foothold_changed");
        Rectangle area = map.getMapArea();
        require(area != null && area.width > 0 && area.height > 0 && area.contains(before) && area.contains(after),
                "movement_map_boundary");
        Point left = new Point(Math.min(before.x, after.x), height(ground, before.x));
        Point right = new Point(Math.max(before.x, after.x), height(ground, before.x));
        require(tree.findWall(left, right) == null, "movement_wall_blocked");
        for (Foothold foothold : tree.getAllFootholds()) {
            if (!foothold.isWall() || foothold.getX1() < left.x || foothold.getX1() > right.x) continue;
            int wallY = height(ground, foothold.getX1());
            require(wallY < Math.min(foothold.getY1(), foothold.getY2())
                    || wallY > Math.max(foothold.getY1(), foothold.getY2()), "movement_wall_blocked");
        }
    }

    /** Cleanup may land below the original foothold; shared physics owns that continuation. */
    static void requireCleanupFrame(MapleMap map, Point before, RuntimeNavigationEngine.Frame frame) {
        Point after = frame.position();
        require(shortRange(before.x) && shortRange(before.y) && shortRange(after.x) && shortRange(after.y),
                "movement_position_out_of_range");
        Rectangle area = map.getMapArea();
        require(area != null && area.width > 0 && area.height > 0 && area.contains(before) && area.contains(after),
                "movement_map_boundary");
        for (Foothold foothold : map.getFootholds().getAllFootholds()) {
            if (foothold.isWall() && Line2D.linesIntersect(before.x, before.y, after.x, after.y,
                    foothold.getX1(), foothold.getY1(), foothold.getX2(), foothold.getY2())) {
                throw new NativeActionService.Invalid("movement_wall_blocked");
            }
        }
    }

    private record SpawnGround(int x, int y, int groundY, int footholdId) {
        int gap() { return groundY - y; }
    }

    private static SpawnGround spawnGround(Character character, MapleMap map, boolean allowGroundTolerance) {
        require(character != null && map != null, "movement_geometry_unavailable");
        requireOrdinaryGroundMode(character, map);
        Point position = character.getPosition();
        require(position != null, "movement_position_unavailable");
        int x = position.x, y = position.y;
        require(shortRange(x) && shortRange(y), "movement_position_out_of_range");
        FootholdTree tree = map.getFootholds();
        require(tree != null, "movement_geometry_unavailable");
        Foothold ground = tree.findBelow(new Point(x, y - (allowGroundTolerance ? 5 : 0)));
        require(ground != null && !ground.isWall() && ground.getX2() > ground.getX1(),
                "movement_spawn_settle_unsupported");
        require(Math.abs((double) ground.getY2() - ground.getY1()) / (ground.getX2() - ground.getX1()) <= 0.5,
                "movement_slope_unsupported");
        int groundY = height(ground, x);
        int gap = groundY - y;
        require(gap >= (allowGroundTolerance ? -5 : 0) && gap <= 24,
                "movement_spawn_settle_unsupported");
        require(shortRange(groundY), "movement_position_out_of_range");
        Rectangle area = map.getMapArea();
        require(area != null && area.width > 0 && area.height > 0 && area.contains(x, y)
                && area.contains(x, groundY), "movement_map_boundary");
        return new SpawnGround(x, y, groundY, ground.getId());
    }

    /** Character and map facts shared by native walking and target-directed navigation. */
    public static void requireOrdinaryMovementMode(Character character, MapleMap map) {
        require(character.getHp() > 0, "movement_actor_dead");
        require(!character.hasDisease(Disease.STUN) && !character.hasDisease(Disease.SEDUCE)
                && character.getChair() < 0, "movement_actor_immobilized");
        require(!character.hasDisease(Disease.SLOW), "movement_slow_unsupported");
        require(!map.isSwim() && character.getBuffedValue(BuffStat.MONSTER_RIDING) == null,
                "movement_mode_unsupported");
        require(GroundRules.fromCharacter(character).supportedSurface(), "movement_surface_unsupported");
    }

    private static void requireOrdinaryGroundMode(Character character, MapleMap map) {
        requireOrdinaryMovementMode(character, map);
        int stance = character.getStance();
        require(stance >= 0 && stance <= 5, "movement_mode_unsupported");
    }

    private static int height(Foothold foothold, int x) {
        return (int) Math.round(foothold.getY1() + ((double) foothold.getY2() - foothold.getY1())
                * (x - (double) foothold.getX1()) / (foothold.getX2() - (double) foothold.getX1()));
    }

    private static boolean shortRange(int value) { return value >= Short.MIN_VALUE && value <= Short.MAX_VALUE; }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new NativeActionService.Invalid(reason);
    }
}
