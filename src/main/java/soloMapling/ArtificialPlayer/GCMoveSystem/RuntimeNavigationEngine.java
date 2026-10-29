package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.client.inventory.InventoryType;
import org.gms.constants.game.CharacterStance;
import org.gms.server.ItemInformationProvider;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.Rope;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** Reuses GreenCat/Solo terrain planning and physics without writing or broadcasting a role's position. */
public final class RuntimeNavigationEngine {
    private RuntimeNavigationEngine() { }

    public enum Mode { IDLE, WALK, JUMP, DROP, CLIMB }

    public record Frame(Point position, int velocityX, int velocityY, int stance,
                        int footholdId, int durationMs, boolean stable, Mode mode) { }

    public static final class Failure extends RuntimeException {
        private final String reason;
        public Failure(String reason) { super(reason); this.reason = reason; }
        private Failure(String reason, Throwable cause) { super(reason, cause); this.reason = reason; }
        public String reason() { return reason; }
    }

    /** Planning runs on the managed caller, outside the role's short-command lane. */
    public static Session plan(Character actor, Point target, Runnable checkpoint) {
        checkpoint.run();
        MapleMap map = actor.getMap();
        if (map.isSwim() || map.getFootholds() == null || BotPhysicsEngine.slipperyGround(map)) {
            throw new Failure("UNSUPPORTED_TERRAIN");
        }
        var shoe = actor.getInventory(InventoryType.EQUIPPED).getItem((short) -7);
        boolean snowShoes = shoe != null
                && ItemInformationProvider.getInstance().getEquipStats(shoe.getItemId()).getOrDefault("fs", 0) >= 1;
        var profile = new BotMovementProfile(actor.getTotalMoveSpeedStat(), actor.getTotalJumpStat(), snowShoes);
        var pending = BotNavigationGraphProvider.requestGraph(map, profile);
        BotNavigationGraph source;
        while (true) {
            checkpoint.run();
            try {
                source = pending.get(25, TimeUnit.MILLISECONDS);
                break;
            } catch (TimeoutException waiting) {
                // The shared world-owned build continues; this caller still observes its own deadline.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("navigation_planning_interrupted");
            } catch (ExecutionException failed) {
                throw new Failure("READ_FAILED", failed.getCause());
            }
        }
        checkpoint.run();
        var landEdges = source.outgoingByRegionId.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                row -> row.getValue().stream().filter(edge -> edge.type != BotNavigationGraph.EdgeType.PORTAL).toList()));
        var graph = new BotNavigationGraph(source.mapId, source.version, source.movementProfile, source.regions,
                source.regionsById, source.regionIdByFootholdId, landEdges,
                source.collidableWallIds, source.collidableFromBelowIds);
        Point start = actor.getPosition();
        Point floor = BotPhysicsEngine.findGroundPoint(map, start);
        boolean onRope = CharacterStance.isClimbing(actor.getStance());
        int startRegion = onRope ? graph.findRopeRegionId(start) : graph.findRegionId(map, start);
        Point targetFloor = BotPhysicsEngine.findGroundPoint(map, target);
        int targetRegion = targetFloor != null && Math.abs(targetFloor.y - target.y) <= 4
                ? graph.findRegionId(map, target) : graph.findRopeRegionId(target);
        if (startRegion < 0 || targetRegion < 0) throw new Failure("NO_PATH");
        if (!onRope && (floor == null || Math.abs(floor.y - start.y) > 24)) {
            throw new Failure("UNSUPPORTED_START_POSITION");
        }
        var route = BotNavigationManager.runSearch(graph, map, start, startRegion, targetRegion, target,
                "runtime-navigation", true, false, 0, checkpoint).path();
        if (route.isEmpty() && startRegion != targetRegion) throw new Failure("NO_PATH");
        return new Session(actor, target, graph, profile, route, onRope, floor);
    }

    /** A session owns only predicted motion; the module dispatches every returned frame natively. */
    public static final class Session {
        private final Character actor;
        private final Point target;
        private final BotNavigationGraph graph;
        private final List<BotNavigationGraph.Edge> route;
        private final BotMovementState motion;
        private int nextEdge;

        private Session(Character actor, Point target, BotNavigationGraph graph,
                        BotMovementProfile profile, List<BotNavigationGraph.Edge> route,
                        boolean onRope, Point floor) {
            this.actor = actor;
            this.target = new Point(target);
            this.graph = graph;
            this.route = List.copyOf(route);
            motion = new BotMovementState(actor, null);
            motion.projectMotion();
            motion.movementProfile = profile;
            motion.facingDir = (actor.getStance() & 1) == 0 ? 1 : -1;
            BotPhysicsEngine.resetMotion(motion, motion.position());
            motion.navGraph = graph;
            motion.navPreciseTarget = true;
            if (onRope) {
                motion.climbing = true;
                motion.climbRope = actor.getMap().getRopes().stream()
                        .filter(rope -> Math.abs(rope.x() - motion.position().x) <= BotPhysicsEngine.cfg.ROPE_GRAB_X
                                && motion.position().y >= rope.topY() && motion.position().y <= rope.bottomY())
                        .findFirst().orElseThrow(() -> new Failure("UNSUPPORTED_START_POSITION"));
            } else if (floor.y - motion.position().y > 1) {
                motion.inAir = true;
                motion.physX = motion.position().x;
                motion.physY = motion.position().y;
                motion.fallPeakPhysY = motion.physY;
            }
        }

        public Point target() { return new Point(target); }
        public int tickMs() { return BotPhysicsEngine.cfg.TICK_MS; }

        /** Called once per role-lane tick; no search, timer, native role write or broadcast occurs here. */
        public Frame nextFrame(boolean stopping) {
            Mode mode = Mode.IDLE;
            if (stopping) {
                motion.navEdge = null;
                motion.moveDir = 0;
                motion.climbVerticalDir = 0;
                motion.climbUpIntent = false;
                if (motion.climbing) BotPhysicsEngine.holdClimb(motion, actor);
                else if (motion.inAir) {
                    motion.fixedAirArc = false;
                    BotMovementManager.tickAirborne(motion, null);
                } else BotPhysicsEngine.idleOnGround(motion, actor);
            } else {
                int region = motion.climbing ? graph.findRopeRegionId(motion.position())
                        : graph.findRegionId(actor.getMap(), motion.position());
                while (!motion.inAir && nextEdge < route.size() && route.get(nextEdge).toRegionId == region) nextEdge++;
                var edge = nextEdge < route.size() ? route.get(nextEdge) : null;
                motion.navEdge = edge;
                motion.navGraph = graph;
                motion.navTargetRegionId = edge == null ? region : edge.toRegionId;
                Point waypoint = target;
                boolean consumed = false;
                if (edge != null) {
                    mode = Mode.valueOf(edge.type.name());
                    var directive = BotNavigationManager.tryExecuteEdge(graph, motion, actor,
                            motion.position(), target, edge, true);
                    consumed = directive != null && directive.consumedTick;
                    if (!consumed) {
                        motion.navPreciseTarget = BotNavigationManager.shouldUsePreciseTarget(graph, motion,
                                motion.position(), edge);
                        waypoint = BotNavigationManager.selectWaypoint(motion, graph, motion.position(), edge);
                    }
                } else {
                    motion.navPreciseTarget = true;
                    mode = motion.climbing ? Mode.CLIMB : Mode.WALK;
                }
                if (!consumed) {
                    if (motion.climbing) BotMovementManager.tickClimbing(motion, waypoint, true);
                    else if (motion.inAir) BotMovementManager.tickAirborne(motion, waypoint);
                    else BotMovementManager.tickGrounded(motion, waypoint);
                }
            }
            Point position = motion.position();
            Point ground = BotPhysicsEngine.findGroundPoint(actor.getMap(), position);
            boolean grounded = !motion.inAir && !motion.climbing && ground != null && Math.abs(ground.y - position.y) <= 1;
            if (grounded) {
                var foothold = BotPhysicsEngine.findGroundFoothold(actor.getMap(), position);
                motion.lastGroundFhId = foothold.getId();
            }
            var movement = BotPhysicsEngine.movementSnapshot(motion);
            boolean stable = motion.climbing ? motion.climbVerticalDir == 0
                    : grounded && movement.velX() == 0 && movement.velY() == 0;
            if (stable) mode = Mode.IDLE;
            return new Frame(position, movement.velX(), movement.velY(), movement.stance(),
                    motion.lastGroundFhId, tickMs(), stable, mode);
        }
    }
}
