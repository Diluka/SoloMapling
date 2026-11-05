/*
 * Ground force integration and bounded roaming are adapted from the continued Journey MMORPG client.
 * Copyright (C) 2015-2019 Daniel Allendorf, Ryan Payton.
 * GNU Affero General Public License, version 3 or later. See LICENSE and NOTICE.
 * Source: ryantpayton/MapleStory-Client, cbb0fe27cf9683a12eca0a569121d8033cdc2a4d,
 * Gameplay/MapleMap/Mob.cpp and Gameplay/Physics/Physics.cpp.
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
import java.awt.Rectangle;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.runtime.CharacterRuntime;

import java.awt.Point;
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
    public static final String POLICY_ID = "journey_ground_reference_v1";
    private static final int TICK_MS = 96;
    private static final int PHYSICS_MS = 8;
    private final SceneRuntime scene;
    private final Set<SceneActorBinding> drivers = ConcurrentHashMap.newKeySet();
    private final Map<Monster, Motion> motions = new ConcurrentHashMap<>();
    private final Map<Integer, Double> forces = new ConcurrentHashMap<>();
    private final AtomicLong nativeDispatches = new AtomicLong();
    private final AtomicLong positionConfirmedFrames = new AtomicLong();
    private volatile FrameFact lastFrame;
    private volatile boolean closed;

    private record Recoil(int direction, long untilNanos) { }
    private record FrameFact(int objectId, boolean dispatched, boolean positionConfirmed, boolean handlerError) { }

    private static final class Motion {
        Character controller;
        double x, speed;
        int confirmedX, confirmedY;
        int direction;
        int action = 2;
        int elapsed;
        short moveId;
        long hitUntil;
        final AtomicReference<Recoil> pendingRecoil = new AtomicReference<>();
        Motion(Monster monster) {
            var position = monster.getPosition();
            x = confirmedX = position.x;
            confirmedY = position.y;
            direction = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        }
    }

    public MonsterSceneModule(SceneRuntime scene) { this.scene = scene; }

    public Map<String, Object> snapshot() {
        var last = lastFrame;
        return Map.of("policy", POLICY_ID, "supported_monster_ids", java.util.List.of(100101, 120100, 130101),
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

    /** Conservative reach of the actual finite driver, including its batched clock and recoil input. */
    record ContactReach(Rectangle area, String model) { }

    ContactReach contactReach(Monster monster, int windowMs) {
        var body = NativeContactDamageActions.contactBody(monster);
        if (body == null) return null;
        var position = monster.getPosition();
        body = body.union(new Rectangle(2 * position.x - body.x - body.width, body.y, body.width, body.height));
        // This ordinary-map object has no legal headless input producer and no autonomous native motion loop.
        // Its body still damages a role entering it; jump AI remains unimplemented rather than zero-risk.
        if (monster.getId() == 1210100 && monster.getController() == null && scene.map().getEventInstance() == null
                && scene.map().getFootholdSpeed() == 1.0f) return new ContactReach(body, "undriven_stationary_body_v1");
        var owner = SceneRuntimeManager.find(monster.getController());
        if (owner == null || owner.scene() != scene || !hasDriver(monster, owner)) return null;
        var foothold = scene.environment().geometry().findBelow(new Point(position.x, position.y - 1));
        double force = Math.max(0.2, forces.computeIfAbsent(monster.getId(), id -> {
            var data = DataProviderFactory.getDataProvider(WZFiles.MOB).getData(String.format("%07d.img", id));
            return (DataTool.getIntConvert("info/speed", data) + 100) * 0.001;
        }));
        // v'=0.8v+u+s*v²/90, |s|<=0.5: this invariant contains normal, stop and recoil branches.
        double maximumStep = 18.0 - Math.sqrt(324.0 - 180.0 * force);
        int radius = (int) Math.ceil((TICK_MS / PHYSICS_MS) * maximumStep * (1 + windowMs / TICK_MS)) + 1;
        int left = Math.max(Math.min(foothold.getX1(), foothold.getX2()), position.x - radius);
        int right = Math.min(Math.max(foothold.getX1(), foothold.getX2()), position.x + radius);
        var first = new Rectangle(body); first.translate(left - position.x, footing(foothold, left) - position.y);
        var last = new Rectangle(body); last.translate(right - position.x, footing(foothold, right) - position.y);
        return new ContactReach(first.union(last), POLICY_ID);
    }

    private boolean hasDriver(Monster monster, SceneActorBinding binding) {
        var world = WorldEpoch.snapshot();
        return binding.active() && !closed && !scene.closed() && drivers.contains(binding)
                && world.active() && world.token() == scene.epoch()
                && !binding.characterRef().character().isChangingMaps()
                && binding.context().scope().isActive() && supports(monster);
    }

    private boolean supports(Monster monster) {
        int id = monster.getId();
        if ((id != 100101 && id != 120100 && id != 130101) || monster.getMap() != scene.map()
                || scene.map().getFootholdSpeed() != 1.0f
                || monster.isBuffed(MonsterStatus.SPEED) || monster.isBuffed(MonsterStatus.DOOM)) return false;
        var position = monster.getPosition();
        var foothold = scene.environment().geometry().findBelow(new Point(position.x, position.y - 1));
        return foothold != null && !foothold.isWall() && Math.abs(footing(foothold, position.x) - position.y) <= 4;
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
            var position = monster.getPosition();
            var foothold = scene.environment().geometry().findBelow(new Point(position.x, position.y - 1));
            if (foothold == null || foothold.isWall() || Math.abs(footing(foothold, position.x) - position.y) > 4) continue;
            var motion = motions.computeIfAbsent(monster, Motion::new);
            synchronized (motion) {
                var recoil = motion.pendingRecoil.getAndSet(null);
                if (recoil != null) {
                    motion.direction = recoil.direction();
                    motion.hitUntil = recoil.untilNanos();
                    motion.elapsed = 0;
                }
                if (motion.controller != character || position.x != motion.confirmedX || position.y != motion.confirmedY) {
                    motion.controller = character;
                    motion.x = position.x;
                    motion.speed = 0;
                }
                double force = forces.computeIfAbsent(monster.getId(), id -> {
                    var data = DataProviderFactory.getDataProvider(WZFiles.MOB).getData(String.format("%07d.img", id));
                    return (DataTool.getIntConvert("info/speed", data) + 100) * 0.001;
                });
                boolean immobilized = monster.isBuffed(MonsterStatus.STUN) || monster.isBuffed(MonsterStatus.FREEZE)
                        || monster.isBuffed(MonsterStatus.SHADOW_WEB) || monster.isBuffed(MonsterStatus.INERTMOB);
                int left = Math.min(foothold.getX1(), foothold.getX2());
                int right = Math.max(foothold.getX1(), foothold.getX2());
                double slope = Math.max(-0.5, Math.min(0.5,
                        (foothold.getY2() - foothold.getY1()) / (double) (foothold.getX2() - foothold.getX1())));
                if (motion.hitUntil != 0 && System.nanoTime() >= motion.hitUntil) {
                    motion.hitUntil = 0;
                    motion.action = 2;
                    motion.direction = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
                    motion.elapsed = 0;
                }
                for (int elapsed = 0; elapsed < TICK_MS; elapsed += PHYSICS_MS) {
                    boolean hit = System.nanoTime() < motion.hitUntil;
                    if (immobilized) motion.speed = 0;
                    else {
                        double input = hit ? -motion.direction * 0.2 : motion.action == 2 ? motion.direction * force : 0;
                        if (input == 0 && Math.abs(motion.speed) < 0.1) motion.speed = 0;
                        else {
                            double inertia = motion.speed / 3.0;
                            motion.speed += input - (0.5 + 0.1 * (1.0 - slope * inertia)) * inertia;
                        }
                    }
                    motion.x += motion.speed;
                    if (motion.x <= left || motion.x >= right) {
                        motion.x = Math.max(left, Math.min(right, motion.x));
                        motion.speed = 0;
                        motion.direction = motion.x <= left ? 1 : -1;
                    }
                    motion.elapsed += PHYSICS_MS;
                    int cycle = motion.action == 2 ? monster.getAnimationTime("move") : monster.getAnimationTime("stand");
                    if (!hit && motion.elapsed > 1600 && motion.elapsed % Math.max(100, cycle) < PHYSICS_MS) {
                        int choice = ThreadLocalRandom.current().nextInt(3);
                        motion.action = choice == 0 ? 4 : 2;
                        if (choice != 0) motion.direction = choice == 1 ? -1 : 1;
                        motion.elapsed = 0;
                    }
                }
                int x = (int) Math.round(motion.x);
                var destination = new Point(x, footing(foothold, x));
                int action = immobilized ? 4 : System.nanoTime() < motion.hitUntil ? 8 : motion.action;
                int stance = action + (motion.direction < 0 ? 1 : 0);
                NativeMonsterMovementActions.MovementResult result;
                try {
                    result = NativeMonsterMovementActions.move(scene.server(), binding.nativeScene(), work, monster,
                            ++motion.moveId, destination, (int) Math.round(motion.speed * 1000 / PHYSICS_MS), stance,
                            foothold.getId(), TICK_MS);
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

    private static int footing(Foothold foothold, int x) {
        return (int) Math.round(foothold.getY1() + (x - foothold.getX1())
                * (foothold.getY2() - foothold.getY1()) / (double) (foothold.getX2() - foothold.getX1()));
    }
}
