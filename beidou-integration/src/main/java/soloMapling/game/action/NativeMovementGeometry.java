package soloMapling.game.action;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Disease;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;

/** Native movement eligibility and conservative ground-walk geometry, without changing the role. */
public final class NativeMovementGeometry {
    private NativeMovementGeometry() { }

    record Step(int beforeX, int beforeY, int x, int y, int footholdId, int velocity) { }
    record Settlement(int x, int y, int footholdId, int verticalVelocity, boolean landed) { }

    /** Only the small gap at a normal portal spawn may precede the ground-walk phase. */
    static boolean needsSpawnSettle(Character character, MapleMap map) {
        return spawnGround(character, map, true).gap() > 5;
    }

    /** A bounded vertical step; the caller owns the at-most-two-tick settlement phase. */
    static Settlement settle(Character character, MapleMap map, int previousVerticalVelocity) {
        SpawnGround ground = spawnGround(character, map, false);
        int velocity = previousVerticalVelocity + 200; // g=2000px/s^2 over the fixed 100ms spawn frame.
        int distance = (previousVerticalVelocity + velocity) / 20;
        int y = ground.y() + Math.min(ground.gap(), distance);
        boolean landed = y == ground.groundY();
        return new Settlement(ground.x(), y, ground.footholdId(), landed ? 0 : velocity, landed);
    }

    static Step step(Character character, MapleMap map, String direction, int elapsedMs) {
        require(character != null && map != null, "movement_geometry_unavailable");
        requireOrdinaryGroundMode(character, map);
        Point position = character.getPosition();
        require(position != null, "movement_position_unavailable");
        int beforeX = position.x, beforeY = position.y;
        require(shortRange(beforeX) && shortRange(beforeY), "movement_position_out_of_range");
        FootholdTree tree = map.getFootholds();
        require(tree != null, "movement_geometry_unavailable");
        Foothold ground = tree.findBelow(new Point(beforeX, beforeY - 5));
        require(ground != null && !ground.isWall() && ground.getX2() > ground.getX1(), "movement_not_grounded");
        require(Math.abs((double) ground.getY2() - ground.getY1()) / (ground.getX2() - ground.getX1()) <= 0.5,
                "movement_slope_unsupported");
        int groundY = height(ground, beforeX);
        require(Math.abs((long) beforeY - groundY) <= 5, "movement_not_grounded");
        int speed = character.getTotalMoveSpeedStat();
        int velocity = (int) Math.floor(125.0 * Math.min(speed, 200) / 100.0);
        int distance = (int) Math.floor(velocity * (double) elapsedMs / 1000.0);
        int x = beforeX + ("LEFT".equals(direction) ? -distance : distance);
        require(x >= ground.getX1() && x <= ground.getX2(), "movement_foothold_edge");
        int y = height(ground, x);
        require(shortRange(x) && shortRange(y), "movement_position_out_of_range");
        Rectangle area = map.getMapArea();
        require(area != null && area.width > 0 && area.height > 0
                && area.contains(beforeX, beforeY) && area.contains(x, y), "movement_map_boundary");
        require(tree.findBelow(new Point(x, y - 5)) == ground, "movement_foothold_changed");
        Point left = new Point(Math.min(beforeX, x), groundY);
        Point right = new Point(Math.max(beforeX, x), groundY);
        require(tree.findWall(left, right) == null, "movement_wall_blocked");
        // Native findWall only handles one wall orientation. Keep the same native foothold set
        // while checking both orientations, including a wall intersecting a sloped walking step.
        for (Foothold foothold : tree.getAllFootholds()) {
            if (!foothold.isWall() || foothold.getX1() < left.x || foothold.getX1() > right.x) continue;
            int wallY = height(ground, foothold.getX1());
            require(wallY < Math.min(foothold.getY1(), foothold.getY2())
                    || wallY > Math.max(foothold.getY1(), foothold.getY2()), "movement_wall_blocked");
        }
        return new Step(beforeX, beforeY, x, y, ground.getId(), "LEFT".equals(direction) ? -velocity : velocity);
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
        require(Float.isFinite(map.getFootholdSpeed()) && Math.abs(map.getFootholdSpeed() - 1.0f) <= 0.0001f,
                "movement_surface_unsupported");
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
