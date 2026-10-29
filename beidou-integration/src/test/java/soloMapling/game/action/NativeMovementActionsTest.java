package soloMapling.game.action;

import org.gms.client.BuffStat;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.Disease;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.constants.inventory.ItemConstants;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.gms.server.maps.MapleMap;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.execution.ScopeResources;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleSelection;
import soloMapling.game.runtime.RoleBinding;
import soloMapling.game.runtime.RuntimeModule;

import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static soloMapling.game.action.NativeActionResult.Outcome.*;

/** Real MovePlayerHandler and footholds, with a manually clocked host-owned lane. */
class NativeMovementActionsTest {
    private Server server;
    private Character character;
    private Client client;
    private MapleMap map;
    private CharacterRuntime runtime;
    private CharacterRuntime.ModuleContext context;
    private GameContext.SceneRef scene;
    private ManualExecutor lane;
    private ManualClock clock;
    private NativeMovementActions service;
    private AtomicReference<Point> position;
    private AtomicInteger stance;
    private final AtomicReference<Item> shoe = new AtomicReference<>();

    @BeforeEach void setup() {
        CharacterTestContext.install(); WorldEpoch.onWorldStarted();
        server = mock(Server.class); when(server.isOnline()).thenReturn(true);
        World world = mock(World.class); Channel channel = mock(Channel.class);
        when(channel.getId()).thenReturn(1); when(server.getWorld(0)).thenReturn(world);
        when(world.getChannel(1)).thenReturn(channel);
        PlayerStorage worldPlayers = mock(PlayerStorage.class), channelPlayers = mock(PlayerStorage.class);
        when(world.getPlayerStorage()).thenReturn(worldPlayers); when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        character = mock(Character.class); client = mock(Client.class);
        when(character.getId()).thenReturn(41); when(character.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(character); when(client.getChannel()).thenReturn(1);
        when(client.isLoggedIn()).thenReturn(true); when(character.getHp()).thenReturn(50);
        when(character.getChair()).thenReturn(-1);
        when(character.getTotalMoveSpeedStat()).thenReturn(100); when(character.getTotalJumpStat()).thenReturn(100);
        var equipped = mock(Inventory.class);
        when(equipped.getItem((short) -7)).thenAnswer(call -> shoe.get());
        when(character.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(character.getBuffedValue(BuffStat.MONSTER_RIDING)).thenReturn(null);
        when(worldPlayers.getCharacterById(41)).thenReturn(character); when(channelPlayers.getCharacterById(41)).thenReturn(character);
        map = mock(MapleMap.class); when(map.getId()).thenReturn(10000);
        when(character.getMap()).thenReturn(map); when(map.getCharacterById(41)).thenReturn(character);
        when(map.getFootholdSpeed()).thenReturn(1.0f); when(map.getMapArea()).thenReturn(new Rectangle(-600,-600,1200,1200));
        FootholdTree footholds = new FootholdTree(new Point(-500,-500), new Point(500,500));
        footholds.insert(new Foothold(new Point(-400,0), new Point(400,0), 1)); when(map.getFootholds()).thenReturn(footholds);
        position = new AtomicReference<>(new Point(0,0)); stance = new AtomicInteger(4);
        when(character.getPosition()).thenAnswer(call -> new Point(position.get()));
        when(character.getStance()).thenAnswer(call -> stance.get());
        doAnswer(call -> { position.set(new Point(call.getArgument(0))); return null; }).when(character).setPosition(any());
        doAnswer(call -> { stance.set(((Number) call.getArgument(0)).intValue()); return null; }).when(character).setStance(anyInt());
        lane = new ManualExecutor(); clock = new ManualClock(); service = new NativeMovementActions(clock, clock.now::get);
        runtime = new CharacterRuntime(WorldEpoch.snapshot().token(), new CharacterRuntime.Options(8,1,
                new ExecutionScope.Limits(16,2,16,2)),lane);
        GameContext.CharacterRef actor = new GameContext.CharacterRef(runtime.acceptedEpoch(), character,client,world,channel);
        RoleBinding binding = new RoleBinding() {
            public String kind() { return "movement-test"; }
            public Object identity() { return character; }
            public GameContext.CharacterRef characterRef() { return actor; }
            public void requireCurrent() { }
            public Map<String,Object> observe() { return Map.of("character_id",41); }
            public ReleaseProof release(ScopeResources.DrainPermit permit) { permit.requireExecuting(character); return new ReleaseProof(true); }
        };
        var open = runtime.acceptOpeningWork();
        runtime.submitOpening(open, () -> { runtime.reserveOrigin(open).adopt(binding); runtime.bindAdoptedOrigin(); }, () -> { });
        lane.all();
        var definition = new ModuleDefinition<Void, RuntimeModule>("movement-test", Set.of("movement"),
                new ExecutionScope.Limits(16, 2, 16, 2), config -> new RuntimeModule() {}, List.of());
        var ref = runtime.mount(definition, new ModuleSelection<>("move", null, true)).reference();
        context = runtime.context(ref); scene = new GameContext.SceneRef(actor,map,null,null);
    }

    @AfterEach void cleanup() {
        if (runtime != null && WorldEpoch.snapshot().active()) {
            runtime.beginClose(Optional.empty()); drain();
            if (runtime.snapshot().modules().isEmpty() && !runtime.originReleased()) {
                var cleanup = runtime.acquireOriginCleanup();
                runtime.submitOriginCleanup(cleanup, () -> runtime.releaseOrigin(cleanup), () -> {}); lane.all();
            }
        }
        ThreadLocalUtil.removeCurrentClient(); ExecutionScope.tryBeginWorldStop();
        if (lane != null) lane.shutdown();
    }
    private NativeMovementActions.MoveHandle move(int ms) {
        return move("RIGHT", ms);
    }
    private NativeMovementActions.MoveHandle move(String direction, int ms) {
        var work = context.acceptWork(); var handle = service.prepareMove(server,context,work,scene,new NativeMovementAction(direction,ms),() -> {});
        service.submitMove(handle); return handle;
    }
    private ExecutionScope.WorkHandle work(NativeMovementActions.MoveHandle handle) {
        try {
            Field pending = handle.getClass().getDeclaredField("pending"); pending.setAccessible(true);
            Object value = ((AtomicReference<?>)pending.get(handle)).get();
            var method = value.getClass().getDeclaredMethod("work"); method.setAccessible(true);
            return (ExecutionScope.WorkHandle) method.invoke(value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private NativeBasicResult result(NativeMovementActions.MoveHandle handle) { return handle.result().toCompletableFuture().join(); }

    private void advance() { clock.next(); lane.all(); }
    private void drain() {
        int remaining = 2000;
        while ((clock.size() != 0 || lane.size() != 0) && remaining-- > 0) {
            lane.all();
            if (clock.size() != 0) clock.next();
        }
        assertTrue(remaining > 0, "movement and cleanup must reach physical settlement");
    }
    private static Item spikedShoe() {
        var shoe = mock(Item.class);
        when(shoe.getFlag()).thenReturn(ItemConstants.SPIKES);
        return shoe;
    }

    @Test void realNativeWalkingUsesTimedShortCommandsAndCompletesAfterIdleAndResourceDrain() {
        var handle = move(137); var parent = work(handle);
        assertFalse(parent.isSettled()); assertEquals(0,lane.size()); assertEquals(1,clock.size());
        clock.next();
        assertEquals(0,position.get().x); assertEquals(1,lane.size()); assertFalse(handle.dispatchStarted());
        lane.all(); assertTrue(position.get().x > 0); assertFalse(parent.isSettled());
        drain();
        assertEquals(SUCCEEDED,result(handle).outcome()); assertEquals(4,stance.get());
        assertTrue(parent.isSettled()); assertEquals(0,context.scope().resources().snapshot().outstanding());
        assertTrue((Boolean)result(handle).facts().get("native_idle_observed"));
        assertEquals(137,result(handle).facts().get("simulated_duration_ms"));
        assertTrue((Boolean)result(handle).facts().get("completed_duration"));
        assertNull(ThreadLocalUtil.getCurrentClient());
    }
    @Test void stopRetainsThePhysicalSlideAndReturnsOnlyAfterItActuallySettles() {
        when(map.getFootholdSpeed()).thenReturn(0.2f);
        var moving = move(2000); var parent = work(moving);
        for (int i = 0; i < 8; i++) advance();
        int releasedAt = position.get().x;
        var stopWork = context.acceptWork(); var stop = service.prepareStop(server,context,stopWork,scene,() -> {});
        service.submitMove(stop);
        assertFalse(parent.isSettled()); assertFalse(stopWork.isSettled());
        advance(); assertTrue(position.get().x > releasedAt); assertFalse(parent.isSettled());
        drain();
        assertEquals(SUCCEEDED,result(stop).outcome()); assertEquals(CANCELLED,result(moving).outcome());
        assertEquals("stop_requested",result(moving).facts().get("stop_reason"));
        assertFalse((Boolean)result(moving).facts().get("completed_duration"));
        assertTrue((Boolean)result(stop).facts().get("native_idle_observed"));
        assertTrue(parent.isSettled()); assertTrue(stopWork.isSettled()); assertEquals(0,clock.size());
        int settledAt = position.get().x; lane.all(); assertEquals(settledAt,position.get().x);
    }
    @Test void cancelledBeforeTheFirstTickDoesNotSendAnyNativePacket() {
        var moving = move(1000); var parent = work(moving); context.cancel(parent);
        assertFalse(parent.isSettled()); drain();
        assertEquals(CANCELLED,result(moving).outcome()); assertFalse(moving.dispatchStarted()); assertEquals(0,position.get().x);
        verify(map,never()).movePlayer(any(),any()); assertTrue(parent.isSettled());
    }
    @Test void cancellationAfterProgressRetainsMovementAndWaitsForTheObservedIdle() {
        var moving = move(1000); var parent = work(moving); advance();
        int progressedAt = position.get().x; assertTrue(progressedAt > 0);
        context.cancel(parent); assertFalse(parent.isSettled()); drain();
        assertEquals(CANCELLED,result(moving).outcome());
        assertTrue(result(moving).cancelRequested()); assertTrue(position.get().x >= progressedAt);
        assertEquals("movement_cancelled",result(moving).facts().get("stop_reason"));
        assertFalse((Boolean)result(moving).facts().get("completed_duration"));
        assertTrue((Boolean)result(moving).facts().get("native_idle_observed"));
        assertEquals(0,clock.size()); assertTrue(parent.isSettled());
    }
    @Test void moduleUnloadWaitsForCleanupFramesAndTheClockToActuallyExit() {
        when(map.getFootholdSpeed()).thenReturn(0.2f);
        var moving = move(1000); var parent = work(moving);
        for (int i = 0; i < 6; i++) advance();
        int progressedAt = position.get().x;
        var stop = runtime.stop(context.reference(),CharacterRuntime.StopMode.UNLOAD);
        assertFalse(stop.complete()); assertFalse(parent.isSettled());
        drain();
        assertTrue(parent.isSettled()); assertTrue(stop.complete()); assertTrue(position.get().x > progressedAt);
        assertTrue((Boolean)result(moving).facts().get("native_idle_observed"));
        assertEquals(0,context.scope().resources().snapshot().outstanding());
    }
    @Test void queuedNativeTickCannotRunAfterTheOriginalWorldStops() {
        var moving = move(1000); var parent = work(moving); clock.next();
        runtime.beginClose(Optional.of(ExecutionScope.tryBeginWorldStop().orElseThrow()));
        drain();
        assertTrue(parent.isSettled()); assertFalse(moving.dispatchStarted()); assertEquals(0,position.get().x);
        verify(map,never()).movePlayer(any(),any());
    }
    @Test void aSecondMoveIsRefusedWhileTheFirstOwnsTheMovementDomain() {
        var first = move(1000); var parent = work(first);
        var next = move(100);
        assertEquals(FAILED,result(next).outcome()); assertEquals("movement_busy",result(next).fixedReason());
        assertFalse(parent.isSettled()); assertEquals(1,clock.size());
        context.cancel(parent); drain();
    }
    @Test void duplicateHandleSubmissionDoesNotCreateAnotherClockOrNativeCarrier() {
        var handle = move(100);
        assertSame(handle.receipt(),service.submitMove(handle)); assertEquals(1,clock.size());
        drain(); assertTrue(position.get().x > 0); assertEquals(SUCCEEDED,result(handle).outcome());
        assertSame(handle.receipt(),service.submitMove(handle)); assertEquals(0,clock.size());
    }
    @Test void timerRejectsReleaseTheOwnedClockWithoutPretendingMovementHappened() {
        service = new NativeMovementActions((body,delay) -> { throw new RejectedExecutionException(); },clock.now::get);
        var handle = move(100);
        assertEquals(FAILED,result(handle).outcome()); assertFalse(handle.dispatchStarted());
        assertEquals(0,context.scope().resources().snapshot().outstanding());
    }
    @Test void pureStopUsesNativeIdleButDoesNotCreateATimerOrChangePosition() {
        var parent = context.acceptWork(); var handle = service.prepareStop(server,context,parent,scene,() -> {});
        service.submitMove(handle); assertEquals(0,clock.size()); lane.all();
        assertEquals(SUCCEEDED,result(handle).outcome()); assertEquals(new Point(0,0),position.get()); assertEquals(4,stance.get());
        assertTrue(parent.isSettled()); assertEquals(0,context.scope().resources().snapshot().outstanding());
    }
    @Test void lateTimerNeverCatchesUpByTeleportingAcrossItsExpiredDuration() {
        var handle = move(100); clock.now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1000)); drain();
        assertEquals(FAILED,result(handle).outcome()); assertEquals(0,position.get().x);
        assertEquals("movement_duration_elapsed",result(handle).facts().get("stop_reason"));
    }
    @Test void spawnSettlementUsesBoundedFramesBeforeTheRequestedHorizontalPeriod() {
        position.set(new Point(0,-19));
        var handle = move(137); var parent = work(handle);
        advance(); assertTrue(position.get().y > -19); assertEquals(0,position.get().x); assertFalse(parent.isSettled());
        drain();
        assertEquals(SUCCEEDED,result(handle).outcome()); assertEquals(0,position.get().y); assertTrue(position.get().x > 0);
        int settling = ((Number)result(handle).facts().get("spawn_settlement_ticks")).intValue();
        assertTrue(settling > 0 && settling <= 4);
        assertEquals(137,result(handle).facts().get("simulated_duration_ms")); assertTrue(parent.isSettled());
    }
    @Test void walkingUsesNativeSpeedSlopeAndTheRemainingShortFrameInBothDirections() {
        FootholdTree slope = new FootholdTree(new Point(-500,-500),new Point(500,500));
        slope.insert(new Foothold(new Point(-100,-20),new Point(100,20),42));
        when(map.getFootholds()).thenReturn(slope);
        when(character.getTotalMoveSpeedStat()).thenReturn(140);
        var right = move(137); drain();
        assertEquals(SUCCEEDED,result(right).outcome()); assertTrue(position.get().x > 0);
        assertTrue(Math.abs(position.get().y-position.get().x*0.2) <= 1);
        assertEquals(137,result(right).facts().get("simulated_duration_ms"));
        int rightX = position.get().x;
        var left = move("LEFT",100); drain();
        assertEquals(SUCCEEDED,result(left).outcome()); assertTrue(position.get().x < rightX);
        assertTrue(Math.abs(position.get().y-position.get().x*0.2) <= 1);
    }
    @Test void eitherWallOrientationPreventsWalkingAcrossTheWall() {
        FootholdTree tree = map.getFootholds();
        tree.insert(new Foothold(new Point(8,-50),new Point(8,50),43));
        tree.insert(new Foothold(new Point(-8,50),new Point(-8,-50),44));
        var right = move(100); drain(); assertTrue(position.get().x < 8, result(right)+" at "+position.get());
        position.set(new Point(0,0));
        var left = move("LEFT",100); drain(); assertTrue(position.get().x > -8, result(left)+" at "+position.get());
        assertTrue(right.result().toCompletableFuture().isDone()); assertTrue(left.result().toCompletableFuture().isDone());
    }
    @Test void walkingCannotLeaveItsFootholdMapOrNativePacketCoordinateRange() {
        position.set(new Point(395,0)); var edge = move(100); drain();
        assertTrue(position.get().x <= 400); assertEquals(FAILED,result(edge).outcome());
        assertEquals("movement_foothold_edge",result(edge).facts().get("stop_reason"));
        assertFalse((Boolean)result(edge).facts().get("completed_duration"));
        position.set(new Point(0,0)); when(map.getMapArea()).thenReturn(new Rectangle(-10,-10,20,20));
        var boundary = move(100); drain(); assertTrue(map.getMapArea().contains(position.get()));
        assertEquals(FAILED,result(boundary).outcome(), result(boundary)+" at "+position.get());
        position.set(new Point(40000,0)); assertMoveRejected("RIGHT","movement_position_out_of_range");
    }
    @Test void unsupportedTerrainAndNativeRoleStatesCannotStartWalkingOrSpawnSettlement() {
        FootholdTree steep = new FootholdTree(new Point(-500,-500),new Point(500,500));
        steep.insert(new Foothold(new Point(-100,-100),new Point(100,100),42));
        FootholdTree ordinary = map.getFootholds(); when(map.getFootholds()).thenReturn(steep);
        assertMoveRejected("RIGHT","movement_slope_unsupported");
        when(map.getFootholds()).thenReturn(ordinary); position.set(new Point(0,-25));
        assertMoveRejected("RIGHT","movement_spawn_settle_unsupported");
        position.set(new Point(0,-19)); when(character.hasDisease(Disease.SLOW)).thenReturn(true);
        assertMoveRejected("RIGHT","movement_slow_unsupported");
        when(character.hasDisease(Disease.SLOW)).thenReturn(false); when(character.hasDisease(Disease.STUN)).thenReturn(true);
        assertMoveRejected("RIGHT","movement_actor_immobilized");
        when(character.hasDisease(Disease.STUN)).thenReturn(false); when(map.isSwim()).thenReturn(true);
        assertMoveRejected("RIGHT","movement_mode_unsupported");
        when(map.isSwim()).thenReturn(false); when(character.getBuffedValue(BuffStat.MONSTER_RIDING)).thenReturn(1);
        assertMoveRejected("RIGHT","movement_mode_unsupported");
        when(character.getBuffedValue(BuffStat.MONSTER_RIDING)).thenReturn(null); stance.set(14);
        assertMoveRejected("RIGHT","movement_mode_unsupported");
        stance.set(4); when(map.getFootholdSpeed()).thenReturn(Float.NaN);
        assertMoveRejected("RIGHT","movement_surface_unsupported");
        when(map.getFootholdSpeed()).thenReturn(1.0f); when(character.getHp()).thenReturn(0);
        assertMoveRejected("RIGHT","movement_actor_dead");
        verify(map,never()).movePlayer(any(),any());
    }
    private void assertMoveRejected(String direction, String reason) {
        var parent = context.acceptWork();
        var handle = service.prepareMove(server,context,parent,scene,new NativeMovementAction(direction,100),() -> {});
        service.submitMove(handle); drain();
        assertEquals(FAILED,result(handle).outcome()); assertEquals(reason,result(handle).fixedReason());
        assertFalse(handle.dispatchStarted()); assertTrue(parent.isSettled());
        assertEquals(0,context.scope().resources().snapshot().outstanding());
    }
    @Test void aLaterStopGetsItsOwnRealIdleDispatchInsteadOfBorrowingAnOlderStopCheckpoint() {
        when(map.getFootholdSpeed()).thenReturn(0.2f);
        var moving = move(1000); for (int i = 0; i < 8; i++) advance();
        var firstWork = context.acceptWork(); var first = service.prepareStop(server,context,firstWork,scene,() -> {});
        service.submitMove(first); advance(); assertTrue(first.dispatchStarted()); assertFalse(firstWork.isSettled());
        var secondWork = context.acceptWork(); var second = service.prepareStop(server,context,secondWork,scene,() -> {});
        service.submitMove(second); assertFalse(second.dispatchStarted()); advance(); assertTrue(second.dispatchStarted());
        drain();
        assertEquals(SUCCEEDED,result(first).outcome()); assertEquals(SUCCEEDED,result(second).outcome());
        assertEquals(CANCELLED,result(moving).outcome());
    }
    @Test void aRegisteredWorldStopTicketAllowsExactLocalClockCleanupBeforeTheScopeExplicitlyJoins() {
        var moving = move(1000); var parent = work(moving); advance();
        int observedAt = position.get().x;
        var stop = ExecutionScope.tryBeginWorldStop().orElseThrow();
        drain();
        assertTrue(parent.isSettled()); assertEquals(0,context.scope().resources().snapshot().outstanding());
        runtime.beginClose(Optional.of(stop)); assertTrue(runtime.snapshot().modules().isEmpty());
        assertEquals(observedAt,position.get().x);
    }
    @Test void aTemporarilyUnavailableCleanupHandshakeCanBeRetriedByTheActualModuleStopSignal() throws Exception {
        var moving = move(1000); var parent = work(moving); advance();
        Field active = NativeMovementActions.class.getDeclaredField("active"); active.setAccessible(true);
        Object job = active.get(service); Field registration = job.getClass().getDeclaredField("registration"); registration.setAccessible(true);
        ScopeResources.Registration original = (ScopeResources.Registration)registration.get(job);
        ScopeResources.Registration unavailable = mock(ScopeResources.Registration.class,delegatesTo(original));
        doThrow(new ExecutionScope.Invalid("resource_cleanup_window_expired"))
                .doAnswer(call -> original.acquireCleanup()).when(unavailable).acquireCleanup();
        registration.set(job,unavailable);
        context.cancel(parent); advance();
        assertFalse(parent.isSettled()); assertFalse(moving.result().toCompletableFuture().isDone());
        assertSame(job,active.get(service)); assertEquals(1,context.scope().resources().snapshot().outstanding());
        var stopped = runtime.stop(context.reference(),CharacterRuntime.StopMode.UNLOAD); drain();
        assertEquals(CANCELLED,result(moving).outcome()); assertTrue(parent.isSettled()); assertTrue(stopped.complete());
        assertEquals(0,context.scope().resources().snapshot().outstanding()); assertNull(active.get(service));
    }
    @Test void resultCallbacksCannotStartAnotherMoveBeforeTheOriginalParentCarrierReallySettles() {
        var moving = move(100); var parent = work(moving);
        var assertion = moving.result().thenAccept(result -> {
            assertFalse(parent.isSettled());
            var reentrant = move(100);
            assertEquals(FAILED,result(reentrant).outcome()); assertEquals("movement_busy",result(reentrant).fixedReason());
        });
        drain(); assertion.toCompletableFuture().join(); assertTrue(parent.isSettled());
        int firstX = position.get().x;
        var next = move(100); drain();
        assertEquals(SUCCEEDED,result(next).outcome()); assertTrue(position.get().x > firstX);
        assertEquals(Map.of("x",firstX,"y",0),result(moving).facts().get("after"));
    }
    @Test void completedDurationWithoutDisplacementRemainsAFailureEvenWhenCancellationArrivesAtDispatch() {
        when(character.getTotalMoveSpeedStat()).thenReturn(1);
        var moving = move(100); var parent = work(moving); var packets = new AtomicInteger();
        doAnswer(call -> { if (packets.incrementAndGet() == 2) context.cancel(parent); return null; }).when(map).movePlayer(eq(character),any());
        drain();
        assertEquals(FAILED,result(moving).outcome()); assertEquals(new Point(0,0),position.get());
        assertTrue((Boolean)result(moving).facts().get("completed_duration"));
        assertTrue(parent.isSettled()); assertEquals(0,context.scope().resources().snapshot().outstanding());
        var after = move(100); drain(); assertEquals(FAILED,result(after).outcome());
    }
    @Test void aResultReductionFailureCannotStrandAnAlreadyReleasedClockAndRealParentCarrier() throws Exception {
        var moving = move(1000); var parent = work(moving); advance();
        Field active = NativeMovementActions.class.getDeclaredField("active"); active.setAccessible(true);
        Object job = active.get(service); Field reason = job.getClass().getDeclaredField("reason"); reason.setAccessible(true);
        reason.set(job,"invalid internal reason"); context.cancel(parent); drain();
        assertThrows(java.util.concurrent.CompletionException.class, () -> result(moving));
        assertTrue(parent.isSettled()); assertEquals(0,context.scope().resources().snapshot().outstanding());
        assertNull(active.get(service));
        var after = move(100); drain(); assertEquals(SUCCEEDED,result(after).outcome());
    }
    @Test void iceAcceleratesThenSlidesAfterTheRequestedInputUntilNativeIdleAndDrain() {
        when(map.getFootholdSpeed()).thenReturn(0.2f);
        var moving = move(400); var parent = work(moving);
        int previous = 0, firstDelta = 0, finalDelta = 0;
        for (int i = 0; i < 8; i++) {
            advance(); int delta = position.get().x-previous;
            if (i == 0) firstDelta = delta;
            finalDelta = delta; previous = position.get().x;
        }
        int releasedAt = position.get().x;
        assertTrue(finalDelta > firstDelta); assertFalse(parent.isSettled());
        advance(); assertTrue(position.get().x > releasedAt); assertFalse(parent.isSettled());
        drain();
        assertEquals(SUCCEEDED,result(moving).outcome()); assertEquals(4,stance.get());
        assertTrue((Boolean)result(moving).facts().get("native_idle_observed"));
        assertTrue(parent.isSettled()); assertEquals(0,clock.size());
        assertEquals(0,context.scope().resources().snapshot().outstanding());
    }
    @Test void spikedShoesUseOrdinaryRulesAndChangingShoesStopsTheOldIceTask() {
        var ordinary = move(400); drain();
        var ordinaryAfter = result(ordinary).facts().get("after");
        long ordinaryElapsed = ((Number)result(ordinary).facts().get("actual_elapsed_ms")).longValue();
        position.set(new Point(0,0));
        when(map.getFootholdSpeed()).thenReturn(0.2f);
        var ice = move(400); drain(); long iceElapsed = ((Number)result(ice).facts().get("actual_elapsed_ms")).longValue();
        position.set(new Point(0,0)); shoe.set(spikedShoe());
        var spiked = move(400); drain();
        assertEquals(SUCCEEDED,result(spiked).outcome());
        long spikedElapsed = ((Number)result(spiked).facts().get("actual_elapsed_ms")).longValue();
        assertTrue(spikedElapsed < iceElapsed);
        assertEquals(ordinaryElapsed,spikedElapsed);
        assertEquals(ordinaryAfter,result(spiked).facts().get("after"));
        position.set(new Point(0,0)); shoe.set(null);
        var changing = move(2000); var parent = work(changing);
        for (int i = 0; i < 6; i++) advance();
        shoe.set(spikedShoe()); drain();
        assertEquals(FAILED,result(changing).outcome()); assertEquals("attributes_changed",result(changing).fixedReason());
        assertFalse((Boolean)result(changing).facts().get("completed_duration"));
        assertTrue((Boolean)result(changing).facts().get("native_idle_observed"));
        assertTrue(parent.isSettled()); assertEquals(0,context.scope().resources().snapshot().outstanding());
    }

    @Test void removingSpikesNearTheEdgeRetainsMomentumUntilTheNewIceRulesLandAndStop() {
        when(map.getFootholdSpeed()).thenReturn(0.2f);
        map.getFootholds().insert(new Foothold(new Point(400,80),new Point(590,80),2));
        position.set(new Point(350,0)); shoe.set(spikedShoe());
        var moving = move(1000); var parent = work(moving);
        int inputFrames = 0;
        while (position.get().x < 388 && !parent.isSettled() && inputFrames++ < 12) advance();
        int changedAt = position.get().x;
        assertTrue(changedAt >= 388 && changedAt < 400, "held input must remain on its original foothold");
        assertEquals(0,position.get().y); assertFalse(parent.isSettled());
        shoe.set(null);
        drain();
        var stopped = result(moving);
        assertEquals(FAILED,stopped.outcome()); assertEquals("attributes_changed",stopped.fixedReason());
        assertFalse((Boolean)stopped.facts().get("completed_duration"));
        assertTrue(position.get().x >= 400 && position.get().x <= 590);
        assertEquals(80,position.get().y, "cleanup must complete the actual fall onto the lower foothold");
        assertTrue((Boolean)stopped.facts().get("native_idle_observed"));
        assertTrue((Boolean)stopped.facts().get("movement_stopped"));
        assertTrue(parent.isSettled()); assertEquals(0,clock.size()); assertEquals(0,lane.size());
        assertEquals(0,context.scope().resources().snapshot().outstanding());
    }

    private static final class ManualClock implements NativeMovementActions.Wakeups {
        final AtomicLong now = new AtomicLong(); final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        public void schedule(Runnable body,int delayMs) { assertEquals(50,delayMs); queue.add(body); }
        int size() { return queue.size(); }
        void next() { now.addAndGet(TimeUnit.MILLISECONDS.toNanos(50)); queue.remove().run(); }
        void all() { int limit=100; while(!queue.isEmpty() && limit-->0)next(); assertTrue(limit>0); }
    }
    private static final class ManualExecutor extends AbstractExecutorService implements CharacterCommandLane.SerialBoundedExecutor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>(); boolean shutdown;
        public int commandQueueCapacity() { return 8; }
        public void execute(Runnable body) { if(shutdown || queue.size()>=8)throw new RejectedExecutionException(); queue.add(body); }
        int size() { return queue.size(); }
        void all() { int limit=100; while(!queue.isEmpty() && limit-->0)queue.remove().run(); assertTrue(limit>0); }
        public void shutdown(){shutdown=true;} public List<Runnable> shutdownNow(){throw new UnsupportedOperationException();}
        public boolean isShutdown(){return shutdown;} public boolean isTerminated(){return shutdown&&queue.isEmpty();}
        public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
    }
}
