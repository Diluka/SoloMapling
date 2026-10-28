package soloMapling.game.action;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.gms.client.Client;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.Item;
import org.gms.net.packet.ByteBufInPacket;
import org.gms.net.server.Server;
import org.gms.net.server.channel.handlers.ItemMoveHandler;
import org.gms.util.ThreadLocalUtil;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static soloMapling.game.action.NativeActionResult.Dispatch.*;
import static soloMapling.game.action.NativeActionResult.Outcome.*;
import static soloMapling.game.action.NativeActionService.SubmissionRejection.*;
import static soloMapling.game.action.NativeActionService.SubmissionStatus.*;

/** Native inventory moves on the runtime's existing lane, without protocol, executor or owner policy. */
public final class NativeInventoryActions {
    private record Pending(Server server, CharacterCommandLane lane, CharacterCommandLane.ControlPermit permit,
                           ExecutionScope.WorkHandle work, GameContext.SceneRef scene, NativeInventoryMoveAction action) { }

    public static final class MoveHandle {
        private final int sender;
        private final String epoch;
        private final WeakReference<ExecutionScope.WorkHandle> work;
        private final NativeActionService.SubmissionReceipt receipt;
        private final AtomicReference<Pending> pending;
        private final CompletableFuture<InventoryMoveResult> result = new CompletableFuture<>();
        private volatile boolean dispatchStarted;

        private MoveHandle(Pending pending) {
            sender = pending.scene.actor().character().getId();
            epoch = pending.scene.actor().epoch().value();
            work = new WeakReference<>(pending.work);
            receipt = new NativeActionService.SubmissionReceipt(pending.work.snapshot().id());
            this.pending = new AtomicReference<>(pending);
        }
        public CompletionStage<InventoryMoveResult> result() { return result.minimalCompletionStage(); }
        public boolean dispatchStarted() { return dispatchStarted; }
        public NativeActionService.SubmissionReceipt receipt() { return receipt; }
    }

    public MoveHandle prepareMove(Server server, CharacterCommandLane lane, CharacterCommandLane.ControlPermit permit,
                                  ExecutionScope.WorkHandle work, GameContext.SceneRef scene, NativeInventoryMoveAction action) {
        Objects.requireNonNull(server); Objects.requireNonNull(lane); Objects.requireNonNull(permit);
        Objects.requireNonNull(work); Objects.requireNonNull(scene); Objects.requireNonNull(action);
        if (work.isCleanup()) throw new NativeActionService.Invalid("cleanup_work_not_an_action");
        if (scene.actor() != permit.characterRef() || scene.actor().epoch() != work.acceptedEpoch()) {
            throw new NativeActionService.Invalid("action_context_mismatch");
        }
        return new MoveHandle(new Pending(server, lane, permit, work, scene, action));
    }

    /** One physical carrier; re-entry and repeated submission return the same nonblocking receipt. */
    public NativeActionService.SubmissionReceipt submitMove(MoveHandle handle) {
        Objects.requireNonNull(handle);
        if (!handle.receipt.begin()) return handle.receipt;
        Pending pending = handle.pending.get();
        CharacterCommandLane.Submission submitted;
        try {
            submitted = pending.lane.submit(pending.work, pending.permit, () -> execute(handle), () -> settled(handle));
        } catch (RuntimeException unavailable) {
            handle.receipt.decide(REJECTED, EXECUTOR);
            finish(handle, unstarted(handle, pending.work.cancelRequested(), "native_action_submission_failed"));
            return handle.receipt;
        }
        if (submitted.status() == CharacterCommandLane.Status.DUPLICATE) {
            handle.receipt.decide(REJECTED, WORK_ALREADY_BOUND);
            finish(handle, unstarted(handle, false, "native_action_work_already_bound"));
        } else if (submitted.status() == CharacterCommandLane.Status.ENQUEUED) {
            handle.receipt.decide(ENQUEUED, NONE);
        } else {
            handle.receipt.decide(REJECTED, NativeActionService.SubmissionRejection.valueOf(submitted.rejection().name()));
            if (!handle.result.isDone()) settled(handle);
        }
        return handle.receipt;
    }

    private static void execute(MoveHandle handle) {
        Pending pending = handle.pending.get();
        if (pending == null) return;
        InventoryMoveResult result;
        try {
            NativeContextChecks.requireSceneCurrent(pending.server, pending.scene);
            Inventory inventory = pending.scene.actor().character().getInventory(pending.action.nativeType());
            if (inventory == null) throw new NativeActionService.Invalid("native_inventory_unavailable");
            inventory.lockInventory();
            try { result = moveLocked(handle, pending, inventory); }
            finally { inventory.unlockInventory(); }
        } catch (NativeActionService.Invalid rejected) {
            result = unstarted(handle, pending.work.cancelRequested(), rejected.reason());
        } catch (Throwable unavailable) {
            result = handle.dispatchStarted ? unknown(handle, pending.work.cancelRequested(), false,
                    InventoryMoveResult.Evidence.none(handle.epoch))
                    : cancelled(pending.work) ? cancelled(handle)
                    : unstarted(handle, false, "native_inventory_validation_failed");
        }
        finish(handle, result);
    }

    private static InventoryMoveResult moveLocked(MoveHandle handle, Pending pending, Inventory inventory) {
        NativeInventoryMoveAction action = pending.action;
        short src = (short) action.sourceSlot(), dst = (short) action.targetSlot();
        if (src > inventory.getSlotLimit() || dst > inventory.getSlotLimit()) {
            throw new NativeActionService.Invalid("inventory_slot_out_of_range");
        }
        Item source = inventory.getItem(src), target = inventory.getItem(dst);
        if (source == null) throw new NativeActionService.Invalid("inventory_source_empty");
        InventoryMoveResult.Slots before = slots(inventory, src, dst);
        boolean beforeValid = validSlot(source, src) && validSlot(target, dst) && source != target;
        if (!beforeValid) throw new NativeActionService.Invalid("native_inventory_inconsistent");
        boolean handlerError = false;
        Client client = pending.scene.actor().client();
        Client previous = ThreadLocalUtil.getCurrentClient();
        ByteBuf packet = Unpooled.buffer(11);
        try {
            packet.writeIntLE(0).writeByte(action.nativeType().getType()).writeShortLE(src).writeShortLE(dst).writeShortLE(0);
            ThreadLocalUtil.setCurrentClient(client);
            ItemMoveHandler handler = new ItemMoveHandler();
            if (!handler.validateState(client)) throw new NativeActionService.Invalid("native_packet_state_rejected");
            NativeContextChecks.requireSceneCurrent(pending.server, pending.scene);
            if (cancelled(pending.work)) return cancelled(handle);
            handle.dispatchStarted = true;
            handler.handlePacket(new ByteBufInPacket(packet), client);
        } catch (Throwable failure) {
            if (!handle.dispatchStarted) {
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new NativeActionService.Invalid("native_inventory_validation_failed");
            }
            handlerError = true;
        } finally {
            if (previous == null) ThreadLocalUtil.removeCurrentClient();
            else ThreadLocalUtil.setCurrentClient(previous);
            packet.release();
        }
        try {
            Item afterSource = inventory.getItem(src), afterTarget = inventory.getItem(dst);
            InventoryMoveResult.Slots after = slots(inventory, src, dst);
            boolean identity = pending.scene.actor().character().getInventory(action.nativeType()) == inventory
                    && validSlot(afterSource, src) && validSlot(afterTarget, dst);
            boolean changed = identity && transition(source, target, afterSource, afterTarget, before, after);
            var evidence = new InventoryMoveResult.Evidence(InventoryMoveResult.Source.NATIVE_INVENTORY_SLOTS,
                    handle.epoch, action.inventoryType(), before, after, identity, changed);
            if (changed) return new InventoryMoveResult(SUCCEEDED, STARTED, handle.sender,
                    pending.work.cancelRequested(), handlerError, null, evidence);
            if (identity && !handlerError && before.equals(after) && source == afterSource && target == afterTarget) {
                return new InventoryMoveResult(FAILED, STARTED, handle.sender, pending.work.cancelRequested(), false,
                        "native_inventory_unchanged", evidence);
            }
            return unknown(handle, pending.work.cancelRequested(), handlerError, evidence);
        } catch (Throwable unavailable) {
            return unknown(handle, pending.work.cancelRequested(), handlerError, InventoryMoveResult.Evidence.none(handle.epoch));
        }
    }

    /** Prove a native move/swap/merge using exact Item identities and conserved quantities under its lock. */
    private static boolean transition(Item source, Item target, Item afterSource, Item afterTarget,
                                      InventoryMoveResult.Slots before, InventoryMoveResult.Slots after) {
        int sourceQty = before.source().quantity(), targetQty = before.target().quantity();
        if (afterTarget == source && afterSource == target) {
            return after.target().quantity() == sourceQty && after.source().quantity() == targetQty;
        }
        return target != null && afterTarget == target && (afterSource == source || afterSource == null)
                && source.getItemId() == target.getItemId()
                && after.source().quantity() + after.target().quantity() == sourceQty + targetQty
                && after.target().quantity() > targetQty;
    }

    private static boolean validSlot(Item item, short slot) { return item == null || item.getPosition() == slot && item.getQuantity() >= 0; }
    private static InventoryMoveResult.Slot slot(Inventory inventory, short position) {
        Item item = inventory.getItem(position);
        return new InventoryMoveResult.Slot(position, item != null, item == null ? 0 : item.getItemId(), item == null ? 0 : item.getQuantity());
    }
    private static InventoryMoveResult.Slots slots(Inventory inventory, short src, short dst) {
        return new InventoryMoveResult.Slots(slot(inventory, src), slot(inventory, dst));
    }

    private static void settled(MoveHandle handle) {
        if (handle.result.isDone()) { handle.pending.set(null); return; }
        Pending pending = handle.pending.get();
        ExecutionScope.WorkHandle work = pending == null ? handle.work.get() : pending.work;
        boolean cancelled = work != null && cancelled(work);
        finish(handle, handle.dispatchStarted ? unknown(handle, cancelled, true, InventoryMoveResult.Evidence.none(handle.epoch))
                : cancelled ? cancelled(handle) : unstarted(handle, false,
                work != null && work.snapshot().rejected() ? "native_action_queue_rejected" : "native_action_not_dispatched"));
    }
    private static boolean cancelled(ExecutionScope.WorkHandle work) {
        WorldEpoch.State world = WorldEpoch.snapshot();
        return work.cancelRequested() || !world.active() || world.token() != work.acceptedEpoch();
    }
    private static InventoryMoveResult cancelled(MoveHandle handle) {
        return new InventoryMoveResult(CANCELLED, NOT_STARTED, handle.sender, true, false,
                "inventory_move_cancelled_before_dispatch", InventoryMoveResult.Evidence.none(handle.epoch));
    }
    private static InventoryMoveResult unstarted(MoveHandle handle, boolean cancelled, String reason) {
        return new InventoryMoveResult(FAILED, NOT_STARTED, handle.sender, cancelled, false, reason,
                InventoryMoveResult.Evidence.none(handle.epoch));
    }
    private static InventoryMoveResult unknown(MoveHandle handle, boolean cancelled, boolean handlerError, InventoryMoveResult.Evidence evidence) {
        return new InventoryMoveResult(INDETERMINATE, STARTED, handle.sender, cancelled, handlerError,
                "native_inventory_outcome_unknown", evidence);
    }
    private static void finish(MoveHandle handle, InventoryMoveResult result) {
        handle.pending.set(null);
        handle.result.complete(result);
    }
}
