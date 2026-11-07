/*
 * Ground/jump integration, connected footholds and roaming are adapted from the Journey MMORPG client.
 * Copyright (C) 2015-2019 Daniel Allendorf, Ryan Payton.
 * GNU Affero General Public License, version 3 or later. See LICENSE and NOTICE.
 * Source: ryantpayton/MapleStory-Client, cbb0fe27cf9683a12eca0a569121d8033cdc2a4d,
 * Gameplay/MapleMap/Mob.cpp, Gameplay/Physics/Physics.cpp and Gameplay/Physics/FootholdTree.cpp.
 */
package soloMapling.game.scene;

import org.gms.client.Character;
import org.gms.client.status.MonsterStatus;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import org.gms.server.life.Monster;
import org.gms.server.maps.Foothold;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.action.NativeMonsterMovementActions;
import soloMapling.game.action.NativeContactDamageActions;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.runtime.CharacterRuntime;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/** Finite compatible-client reference policy, not an implementation of the official v83 client AI. */
public final class MonsterSceneModule implements SceneRuntimeModule {
    public static final String POLICY_ID = "training_ground_jump_aggro_v2";
    private static final List<Integer> SUPPORTED_MONSTERS = List.of(100101, 120100, 130101, 1210100);
    private static final int TICK_MS = 96;
    private static final int PHYSICS_MS = 8;
    // Matched V17 input samples: jump -555px/s and gravity 2000px/s².
    private static final double JUMP_STEP_SPEED = -555.0 * PHYSICS_MS / 1000;
    private static final double GRAVITY_STEP = 2000.0 * PHYSICS_MS * PHYSICS_MS / 1_000_000;
    private final SceneRuntime scene;
    private final Map<Integer, GroundPlatform> platforms = new HashMap<>();
    private final Set<SceneActorBinding> drivers = ConcurrentHashMap.newKeySet();
    private final Map<Monster, Motion> motions = new ConcurrentHashMap<>();
    private final Map<Integer, Double> forces = new ConcurrentHashMap<>();
    private final AtomicLong nativeDispatches = new AtomicLong();
    private final AtomicLong positionConfirmedFrames = new AtomicLong();
    private volatile FrameFact lastFrame;
    private volatile boolean closed;

    private record Recoil(int direction, long untilNanos) { }
    private record FrameFact(int objectId, boolean dispatched, boolean positionConfirmed, boolean handlerError) { }
    private record GroundPlatform(List<Foothold> footholds, int left, int right) { }

    private static final class Motion {
        Character controller;
        double x, y, speed, verticalSpeed;
        Foothold foothold;
        volatile boolean grounded;
        int confirmedX, confirmedY;
        int direction;
        int action = 2;
        int elapsed;
        short moveId;
        long hitUntil;
        final AtomicReference<Recoil> pendingRecoil = new AtomicReference<>();
        Motion(Monster monster) {
            var position = monster.getPosition();
            controller = monster.getController();
            x = confirmedX = position.x;
            y = confirmedY = position.y;
            direction = (monster.getStance() & 1) == 0 ? 1 : -1;
        }
    }

    public MonsterSceneModule(SceneRuntime scene) { this.scene = scene; }

    private synchronized GroundPlatform platform(Foothold foothold) {
        var known = platforms.get(foothold.getId());
        if (known != null) return known;
        var footholds = new HashMap<Integer, Foothold>();
        scene.environment().geometry().getAllFootholds().forEach(part -> footholds.put(part.getId(), part));
        var connected = new HashSet<Foothold>();
        var pending = new ArrayDeque<Foothold>();
        pending.add(foothold);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!connected.add(current)) continue;
            for (int neighborId : List.of(current.getPrev(), current.getNext())) {
                var neighbor = footholds.get(neighborId);
                if (neighbor == null || neighbor.isWall()) continue;
                if (current.getX1() == neighbor.getX2() && current.getY1() == neighbor.getY2()
                        || current.getX2() == neighbor.getX1() && current.getY2() == neighbor.getY1()) {
                    pending.add(neighbor);
                }
            }
        }
        int left = connected.stream().mapToInt(part -> Math.min(part.getX1(), part.getX2())).min().orElseThrow();
        int right = connected.stream().mapToInt(part -> Math.max(part.getX1(), part.getX2())).max().orElseThrow();
        var platform = new GroundPlatform(List.copyOf(connected), left, right);
        connected.forEach(part -> platforms.put(part.getId(), platform));
        return platform;
    }

    public Map<String, Object> snapshot() {
        var last = lastFrame;
        return Map.of("policy", POLICY_ID, "supported_monster_ids", SUPPORTED_MONSTERS,
                "native_dispatches", nativeDispatches.get(), "position_confirmed_frames", positionConfirmedFrames.get(),
                "last_frame", last == null ? Map.of() : Map.of("object_id", last.objectId(),
                        "dispatch_started", last.dispatched(), "position_confirmed", last.positionConfirmed(),
                        "handler_error", last.handlerError()));
    }

    /** The native controller coordinator reads the one actual scene membership, not a second registry. */
    public static boolean canDrive(Monster monster, Character character) {
        var binding = SceneRuntimeManager.find(character);
        return binding != null && binding.scene().monsters().hasDriver(monster, binding);
    }

    /** Cross-platform/jump trajectories use a conservative contact upper bound, not a zero-risk gap. */
    record ContactReach(Rectangle area, String model, boolean positionUnbounded) { }

    ContactReach contactReach(Monster monster, int windowMs) {
        var body = NativeContactDamageActions.contactBody(monster);
        if (body == null) return null;
        var position = monster.getPosition();
        body = body.union(new Rectangle(2 * position.x - body.x - body.width, body.y, body.width, body.height));
        var controller = monster.getController();
        // These WZ templates have bodyAttack but no attackN/skillN. Unknown movement can widen
        // contact exposure without removing the role's shared contact interval or damage bound.
        if (!SUPPORTED_MONSTERS.contains(monster.getId()) || scene.map().getFootholdSpeed() != 1.0f
                || scene.map().isSwim() || monster.isBuffed(MonsterStatus.SPEED)
                || monster.isBuffed(MonsterStatus.DOOM)) return null;
        if (controller != null && !MonsterControlCoordinator.isHeadless(controller)
                && MonsterControlCoordinator.canControl(monster, controller)) {
            return new ContactReach(body, "native_controller_contact_upper_bound_v1", true);
        }
        return supports(monster) ? new ContactReach(body, "headless_contact_upper_bound_v2", true) : null;
    }

    private boolean hasDriver(Monster monster, SceneActorBinding binding) {
        var world = WorldEpoch.snapshot();
        return binding.active() && !closed && !scene.closed() && drivers.contains(binding)
                && world.active() && world.token() == scene.epoch()
                && !binding.characterRef().character().isChangingMaps()
                && binding.context().scope().isActive() && supports(monster);
    }

    private boolean supports(Monster monster) {
        if (!SUPPORTED_MONSTERS.contains(monster.getId()) || monster.getMap() != scene.map()
                || scene.map().getFootholdSpeed() != 1.0f || scene.map().isSwim()
                || monster.isBuffed(MonsterStatus.SPEED) || monster.isBuffed(MonsterStatus.DOOM)) return false;
        var position = monster.getPosition();
        var foothold = scene.environment().geometry().findBelow(new Point(position.x, position.y - 1));
        var motion = motions.get(monster);
        return foothold != null && !foothold.isWall()
                || motion != null && !motion.grounded && motion.foothold != null;
    }

    @Override public void onEnter(SceneActorBinding actor) {
        actor.startTask("scene-monsters-" + actor.characterRef().character().getId(), work -> run(actor, work));
    }

    @Override public void onLeave(SceneActorBinding actor) { drivers.remove(actor); }

    @Override public void close() {
        closed = true;
        drivers.clear();
        motions.clear();
        forces.clear();
    }

    private void run(SceneActorBinding binding, ExecutionScope.WorkHandle work) {
        var context = binding.context();
        var registration = context.scope().resources().beginOwnedResult(work, "scene_monster_driver");
        var cleanupVerified = new CompletableFuture<Void>();
        binding.trackCleanup(cleanupVerified);
        try {
            registration.attach(binding, () -> context.scope().requestCancel(work));
            drivers.add(binding);
            while (!work.cancelRequested() && binding.active()) {
                var failure = new AtomicReference<Throwable>();
                var step = context.acceptWork(work);
                try {
                    context.submit("scene-client", step, binding::requireCurrent, () -> {
                        try { tick(binding, step); }
                        catch (RuntimeException | Error problem) { failure.set(problem); }
                    }, () -> {});
                } catch (RuntimeException rejected) { step.reject(); throw rejected; }
                step.completion().toCompletableFuture().join();
                if (failure.get() instanceof Error fatal) throw fatal;
                if (failure.get() instanceof RuntimeException problem) throw problem;
                try { Thread.sleep(TICK_MS); }
                catch (InterruptedException cancelled) { return; }
            }
        } catch (CancellationException | GameContext.Invalid | CharacterRuntime.Invalid | ExecutionScope.Invalid stopped) {
            if (binding.active() && !work.cancelRequested() && context.scope().isActive()) throw stopped;
        } finally {
            drivers.remove(binding);
            try {
                // World-stop cancellation and revoked admission may precede the binding's leave hook.
                binding.whenActionsDrained().toCompletableFuture().join();
                var cleanup = registration.acquireCleanup();
                context.submitCleanup(cleanup, () -> {
                    cleanup.permit().requireExecuting(binding);
                    var character = binding.characterRef().character();
                    for (var monster : scene.environment().monsters()) {
                        if (monster.getController() != character) continue;
                        var successor = SceneRuntimeManager.find(character);
                        if (successor != null && successor != binding && successor.scene().map() == scene.map()
                                && successor.scene().monsters().hasDriver(monster, successor)) continue;
                        cleanup.permit().requireExecuting(binding);
                        monster.aggroRemoveController();
                        if (monster.getController() == character || character.getControlledMonsters().contains(monster)) {
                            throw new IllegalStateException("scene_monster_controller_release_unverified");
                        }
                        cleanup.permit().requireExecuting(binding);
                        var world = WorldEpoch.snapshot();
                        if (world.active() && world.token() == scene.epoch()) monster.aggroUpdateController();
                    }
                    registration.confirmReleased(cleanup.permit(), binding);
                }, () -> {});
                cleanup.work().completion().toCompletableFuture().join();
            } finally {
                if (registration.released()) cleanupVerified.complete(null);
                else cleanupVerified.completeExceptionally(new IllegalStateException("scene_monster_cleanup_unverified"));
            }
        }
    }

    private void tick(SceneActorBinding binding, ExecutionScope.WorkHandle work) {
        binding.requireCurrent();
        var character = binding.characterRef().character();
        if (character.isChangingMaps()) return;
        var monsters = scene.environment().monsters();
        var geometry = scene.environment().geometry();
        List<Foothold> walls = null;
        motions.keySet().removeIf(monster -> scene.map().getMonsterByOid(monster.getObjectId()) != monster);
        for (var monster : monsters) {
            if (work.cancelRequested() || !binding.active()) return;
            if (!monster.isAlive()) continue;
            if (!supports(monster)) {
                if (monster.getController() == character) monster.aggroRedirectController();
                motions.remove(monster);
                continue;
            }
            var controller = monster.getController();
            if (!MonsterControlCoordinator.canControl(monster, controller)) monster.aggroRedirectController();
            if (monster.getController() != character) continue;
            if (walls == null) walls = geometry.getAllFootholds().stream().filter(Foothold::isWall).toList();
            var position = monster.getPosition();
            var motion = motions.computeIfAbsent(monster, Motion::new);
            synchronized (motion) {
                var foothold = scene.environment().geometry().findBelow(new Point(position.x, position.y - 1));
                if (foothold == null) {
                    if (motion.grounded || motion.foothold == null) continue;
                    foothold = motion.foothold;
                }
                if (motion.foothold == null || motion.controller != character
                        || position.x != motion.confirmedX || position.y != motion.confirmedY) {
                    if (motion.controller != character) motion.pendingRecoil.set(null);
                    motion.controller = character;
                    motion.x = position.x;
                    motion.y = position.y;
                    motion.speed = 0;
                    motion.verticalSpeed = 0;
                    motion.foothold = foothold;
                    motion.grounded = Math.abs(footing(foothold, motion.x) - motion.y) <= 4;
                    if (motion.grounded) motion.y = footing(foothold, motion.x);
                    motion.direction = (monster.getStance() & 1) == 0 ? 1 : -1;
                    motion.action = motion.grounded ? 2 : 6;
                    motion.elapsed = 0;
                    motion.hitUntil = 0;
                }
                var recoil = motion.pendingRecoil.getAndSet(null);
                if (recoil != null) {
                    motion.direction = recoil.direction();
                    motion.hitUntil = recoil.untilNanos();
                    motion.elapsed = 0;
                }
                double force = forces.computeIfAbsent(monster.getId(), id -> {
                    var data = DataProviderFactory.getDataProvider(WZFiles.MOB).getData(String.format("%07d.img", id));
                    // V17 walking samples reach 125 * (100 + WZ speed) / 100 px/s.
                    // The reference's ground drag is 0.2v per 8ms, so doubling its force matches that limit.
                    return (DataTool.getIntConvert("info/speed", data) + 100) * 0.002;
                });
                boolean immobilized = monster.isBuffed(MonsterStatus.STUN) || monster.isBuffed(MonsterStatus.FREEZE)
                        || monster.isBuffed(MonsterStatus.SHADOW_WEB) || monster.isBuffed(MonsterStatus.INERTMOB);
                var target = scene.map().getAllPlayers().stream()
                        .filter(candidate -> candidate.isAlive() && !candidate.isHidden()
                                && monster.getMapAggroCoordinator().isLeadingCharacterAggro(monster, candidate))
                        .findFirst().orElse(null);
                var targetPosition = target == null ? null : new Point(target.getPosition());
                var targetGround = targetPosition == null ? null : scene.environment().geometry()
                        .findBelow(new Point(targetPosition.x, targetPosition.y - 1));
                var platform = platform(motion.foothold);
                if (motion.hitUntil != 0 && System.nanoTime() >= motion.hitUntil) {
                    motion.hitUntil = 0;
                    motion.action = 2;
                    motion.elapsed = 0;
                }
                Point jumpVelocity = null;
                for (int elapsed = 0; elapsed < TICK_MS; elapsed += PHYSICS_MS) {
                    boolean hit = System.nanoTime() < motion.hitUntil;
                    boolean chasing = targetGround != null && platform.footholds().contains(targetGround);
                    if (motion.grounded && !hit && !immobilized) {
                        if (chasing) {
                            double distance = targetPosition.x - motion.x;
                            motion.action = Math.abs(distance) <= 8 ? 4 : 2;
                            if (motion.action == 2) motion.direction = distance > 0 ? 1 : -1;
                        }
                        int cycle = motion.action == 2 ? monster.getAnimationTime("move") : monster.getAnimationTime("stand");
                        if (elapsed == 0 && motion.elapsed > 1600
                                && motion.elapsed % Math.max(100, cycle) < TICK_MS) {
                            if (monster.getId() == 1210100 && motion.action == 2
                                    && ThreadLocalRandom.current().nextDouble() < 0.25) motion.action = 6;
                            else if (!chasing) {
                                int choice = ThreadLocalRandom.current().nextInt(3);
                                motion.action = choice == 0 ? 4 : 2;
                                if (choice != 0) motion.direction = choice == 1 ? -1 : 1;
                            }
                            motion.elapsed = 0;
                        }
                    }
                    if (immobilized) motion.speed = 0;
                    else if (motion.grounded) {
                        if (motion.action != 6 || hit) {
                            double input = hit ? -motion.direction * 0.2 : motion.action == 2 ? motion.direction * force : 0;
                            if (input == 0 && Math.abs(motion.speed) < 0.1) motion.speed = 0;
                            else {
                                double inertia = motion.speed / 3.0;
                                double slope = Math.clamp(motion.foothold.slope(), -0.5, 0.5);
                                motion.speed += input - (0.5 + 0.1 * (1.0 - slope * inertia)) * inertia;
                                if (!hit) motion.speed = Math.clamp(motion.speed, -force * 5, force * 5);
                            }
                        }
                        if (!hit && motion.action == 6) {
                            motion.verticalSpeed = JUMP_STEP_SPEED;
                            motion.grounded = false;
                            jumpVelocity = new Point((int) Math.round(motion.speed * 1000 / PHYSICS_MS), -555);
                        }
                    }
                    double nextX = motion.x + motion.speed;
                    if (motion.grounded && motion.speed != 0
                            && (nextX <= platform.left() || nextX >= platform.right())) {
                        nextX = Math.clamp(nextX, platform.left(), platform.right());
                        motion.speed = 0;
                        motion.direction = nextX <= platform.left() ? 1 : -1;
                    }
                    if (motion.grounded) {
                        double groundX = nextX;
                        motion.foothold = platform.footholds().stream()
                                .filter(part -> groundX >= Math.min(part.getX1(), part.getX2())
                                        && groundX <= Math.max(part.getX1(), part.getX2()))
                                .findFirst().orElseThrow();
                        motion.y = footing(motion.foothold, nextX);
                    } else {
                        double nextY = motion.y + motion.verticalSpeed + GRAVITY_STEP / 2;
                        motion.verticalSpeed += GRAVITY_STEP;
                        double boundedX = Math.clamp(nextX, geometry.getX1(), geometry.getX2());
                        double previousX = motion.x;
                        var wall = walls.stream()
                                .filter(part -> nextY >= Math.min(part.getY1(), part.getY2())
                                        && nextY <= Math.max(part.getY1(), part.getY2())
                                        && (previousX < part.getX1() && boundedX >= part.getX1()
                                                || previousX > part.getX1() && boundedX <= part.getX1()))
                                .min(Comparator.comparingDouble(part -> Math.abs(part.getX1() - previousX)))
                                .orElse(null);
                        if (wall != null || boundedX != nextX) {
                            nextX = wall == null ? boundedX : wall.getX1();
                            motion.direction = motion.speed < 0 ? 1 : -1;
                            motion.speed = 0;
                        }
                        var landing = scene.environment().geometry().findBelow(
                                new Point((int) Math.round(nextX), (int) Math.floor(motion.y) - 1));
                        if (motion.verticalSpeed >= 0 && landing != null
                                && motion.y <= footing(landing, nextX) && nextY >= footing(landing, nextX)) {
                            motion.foothold = landing;
                            platform = platform(landing);
                            motion.y = footing(landing, nextX);
                            motion.verticalSpeed = 0;
                            motion.grounded = true;
                            motion.action = 2;
                            motion.elapsed = 0;
                        } else motion.y = nextY;
                    }
                    motion.x = nextX;
                    motion.elapsed += PHYSICS_MS;
                }
                int x = (int) Math.round(motion.x);
                var destination = new Point(x, (int) Math.round(motion.y));
                int action = immobilized ? 4 : System.nanoTime() < motion.hitUntil ? 8 : !motion.grounded ? 6 : motion.action;
                int stance = action + (motion.direction < 0 ? 1 : 0);
                NativeMonsterMovementActions.MovementResult result;
                try {
                    result = NativeMonsterMovementActions.move(scene.server(), binding.nativeScene(), work, monster,
                            ++motion.moveId, destination, (int) Math.round(motion.speed * 1000 / PHYSICS_MS),
                            (int) Math.round(motion.verticalSpeed * 1000 / PHYSICS_MS), jumpVelocity, stance,
                            motion.grounded ? motion.foothold.getId() : 0, TICK_MS);
                } catch (NativeActionService.Invalid changed) {
                    if (!changed.reason().equals("monster_controller_changed")) throw changed;
                    motion.controller = null;
                    continue;
                }
                if (!result.mapReady()) {
                    motion.controller = null;
                    return;
                }
                motion.confirmedX = result.xAfter();
                motion.confirmedY = result.yAfter();
                if (result.dispatched()) nativeDispatches.incrementAndGet();
                if (result.positionConfirmed()) positionConfirmedFrames.incrementAndGet();
                lastFrame = new FrameFact(result.objectId(), result.dispatched(), result.positionConfirmed(), result.handlerError());
                if (result.handlerError() || !result.positionConfirmed()) {
                    throw new IllegalStateException("native_monster_movement_not_confirmed");
                }
            }
        }
    }

    /** Native damage remains authoritative; only the temporary reference-client recoil input is prepared. */
    public static void damaged(Monster monster, Character attacker, int damage) {
        var controller = monster.getController();
        if (controller == null) return;
        var binding = SceneRuntimeManager.find(controller);
        if (binding == null) return;
        var module = binding.scene().monsters();
        if (!module.hasDriver(monster, binding)) return;
        var data = DataProviderFactory.getDataProvider(WZFiles.MOB).getData(String.format("%07d.img", monster.getId()));
        if (damage < DataTool.getIntConvert("info/pushed", data)) return;
        var motion = module.motions.computeIfAbsent(monster, Motion::new);
        // Native damage holds the monster lock. Publish without waiting for the movement context,
        // whose owning lane may currently be inside the native movement handler.
        motion.pendingRecoil.set(new Recoil(attacker.getPosition().x > monster.getPosition().x ? 1 : -1,
                System.nanoTime() + 248_000_000L));
    }

    private static double footing(Foothold foothold, double x) {
        return foothold.getY1() + (x - foothold.getX1()) * foothold.slope();
    }
}
