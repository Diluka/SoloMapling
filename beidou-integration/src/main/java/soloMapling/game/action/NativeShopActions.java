package soloMapling.game.action;

import io.netty.buffer.Unpooled;
import org.gms.client.Character;
import org.gms.config.GameConfig;
import org.gms.constants.id.MapId;
import org.gms.constants.inventory.ItemConstants;
import org.gms.manager.ServerManager;
import org.gms.net.server.Server;
import org.gms.net.server.channel.handlers.NPCShopHandler;
import org.gms.property.ServiceProperty;
import org.gms.server.Shop;
import org.gms.server.life.NPC;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.inventory.SaleItem;
import soloMapling.game.shop.ShopResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Finite ordinary-shop adapters; callers own lane admission and the resulting shop interaction. */
public final class NativeShopActions {
    private NativeShopActions() { }

    public record Opened(ShopResult.Outcome outcome, String reason, NPC npc, Shop shop,
                         int mesoBefore, int mesoAfter, boolean dispatched, boolean handlerError) { }

    /** The native NPC cooldown stores the last disposal time, compared against the configured interval. */
    public static boolean cooldownReady(Server server, Character actor) {
        return server.getCurrentTime() - actor.getNpcCooldown() >= GameConfig.getServerInt("block_npc_race_condition");
    }

    /** Uses the same native sendShop path as an ordinary NPC and cm.openShopNPC, preserving any caller CM. */
    public static Opened open(Server server, GameContext.SceneRef scene, int npcId, ExecutionScope.WorkHandle work) {
        NativeContextChecks.requireSceneCurrent(server, scene);
        Character actor = scene.actor().character();
        int meso = actor.getMeso();
        NPC npc = scene.map().getNPCById(npcId);
        String rejected = null;
        if (work.cancelRequested()) rejected = "shop_open_cancelled";
        else if (!actor.isAlive()) rejected = "shop_actor_dead";
        else if (scene.map().getId() == MapId.JAIL) rejected = "shop_map_restricted";
        else if (actor.isChangingMaps()) rejected = "shop_map_transition_incomplete";
        else if (!cooldownReady(server, actor)) rejected = "shop_npc_cooldown";
        else if (actor.getShop() != null) rejected = "shop_opened_elsewhere";
        else if (npc == null || scene.map().getMapObject(npc.getObjectId()) != npc) rejected = "shop_npc_not_found";
        else if (actor.getPosition().distanceSq(npc.getPosition()) > 100 * 100) rejected = "shop_npc_out_of_range";
        else if (!npc.hasShop()) rejected = "shop_npc_has_no_shop";
        if (rejected == null) {
            // The native loader checks these two paths and then evals; only inspect its file metadata here.
            String language = ServerManager.getApplicationContext().getBean(ServiceProperty.class).getLanguage();
            String file = "npc/" + npcId + ".js";
            if (Files.exists(Path.of("scripts-" + language, file)) || Files.exists(Path.of("scripts", file))) {
                rejected = "shop_scripted_npc_unsupported";
            }
        }
        if (rejected != null) return new Opened(work.cancelRequested() ? ShopResult.Outcome.CANCELLED
                : ShopResult.Outcome.REJECTED, rejected, npc, null, meso, actor.getMeso(), false, false);

        if (NativeBasicActions.cancelled(work)) return new Opened(ShopResult.Outcome.CANCELLED,
                "shop_open_cancelled", npc, null, meso, actor.getMeso(), false, false);
        NativeContextChecks.requireSceneCurrent(server, scene);

        boolean handlerError = false;
        try { npc.sendShop(scene.actor().client()); }
        catch (RuntimeException | Error failed) {
            // Binding can precede a send failure. Return that real binding so its provider can own and clean it.
            handlerError = true;
        }
        Shop shop = actor.getShop();
        boolean opened = shop != null && shop.getNpcId() == npcId;
        return new Opened(opened && !handlerError ? ShopResult.Outcome.OPENED : ShopResult.Outcome.REJECTED,
                handlerError ? "native_shop_open_error" : opened ? null : "native_shop_open_not_observed",
                npc, opened ? shop : null, meso, actor.getMeso(), true, handlerError);
    }

    /** Handler return is separate from confirmed quantity and currency changes. */
    public static ShopResult.Sale sell(Server server, GameContext.SceneRef scene, Shop exactShop,
                                       SaleItem input, ExecutionScope.WorkHandle work, BooleanSupplier ownsInteraction) {
        NativeContextChecks.requireSceneCurrent(server, scene);
        Character actor = scene.actor().character();
        var inventory = actor.getInventory(input.inventoryType());
        var item = inventory.getItem(input.slot());
        int beforeId = item == null ? 0 : item.getItemId();
        int beforeQuantity = item == null ? 0 : item.getQuantity();
        int beforeMeso = actor.getMeso();
        ShopResult.SaleOutcome outcome = null;
        String reason = null;
        if (work.cancelRequested()) { outcome = ShopResult.SaleOutcome.CANCELLED; reason = "shop_sale_cancelled"; }
        else if (!actor.isAlive()) { outcome = ShopResult.SaleOutcome.NOT_ATTEMPTED; reason = "shop_actor_dead"; }
        else if (!ownsInteraction.getAsBoolean() || actor.getShop() != exactShop) {
            outcome = ShopResult.SaleOutcome.NOT_ATTEMPTED; reason = "shop_interaction_changed";
        }
        else if (input.slot() > Byte.MAX_VALUE) { outcome = ShopResult.SaleOutcome.NATIVE_REJECTED; reason = "shop_native_slot_unsupported"; }
        else if (beforeId != input.expectedItemId() || ItemConstants.getInventoryType(input.expectedItemId()) != input.inventoryType()) {
            outcome = ShopResult.SaleOutcome.ITEM_CHANGED; reason = "shop_sale_item_changed";
        } else if (beforeQuantity < input.quantity()) {
            outcome = ShopResult.SaleOutcome.INSUFFICIENT_QUANTITY; reason = "shop_sale_quantity_insufficient";
        } else if (ItemConstants.isRechargeable(beforeId) && beforeQuantity != input.quantity()) {
            outcome = ShopResult.SaleOutcome.NATIVE_WHOLE_STACK_REQUIRED; reason = "shop_native_whole_stack_only";
        }
        boolean dispatched = false;
        boolean handlerError = false;
        if (outcome == null) {
            var started = new boolean[1];
            try {
                handlerError = NativePacketDispatch.dispatch(scene.actor().client(), new NPCShopHandler(),
                        Unpooled.buffer(9).writeByte(1).writeShortLE(input.slot()).writeIntLE(input.expectedItemId())
                                .writeShortLE(input.quantity()), () -> {
                            if (NativeBasicActions.cancelled(work)) throw new CancellationException();
                            NativeContextChecks.requireSceneCurrent(server, scene);
                            if (!ownsInteraction.getAsBoolean() || actor.getShop() != exactShop) {
                                throw new NativeActionService.Invalid("shop_interaction_changed");
                            }
                        }, () -> started[0] = true);
            } catch (CancellationException cancelled) {
                outcome = ShopResult.SaleOutcome.CANCELLED; reason = "shop_sale_cancelled";
            } catch (NativeActionService.Invalid rejected) {
                outcome = ShopResult.SaleOutcome.NOT_ATTEMPTED; reason = rejected.reason();
            }
            dispatched = started[0];
        }
        var after = inventory.getItem(input.slot());
        int afterId = after == null ? 0 : after.getItemId();
        int afterQuantity = after != null && afterId == beforeId ? after.getQuantity() : 0;
        if (outcome == null) {
            outcome = beforeQuantity - afterQuantity == input.quantity() ? ShopResult.SaleOutcome.SOLD
                    : ShopResult.SaleOutcome.NATIVE_REJECTED;
            if (outcome != ShopResult.SaleOutcome.SOLD) reason = "native_shop_sale_not_observed";
            else if (handlerError) reason = "native_shop_sale_error_after_effect";
        }
        return new ShopResult.Sale(input, outcome, beforeId, afterId, beforeQuantity, afterQuantity,
                beforeMeso, actor.getMeso(), dispatched, handlerError, reason);
    }

    /** Exact resource cleanup does not use the old map as a new-action qualification. */
    public static boolean close(Character actor, Shop exactShop, Runnable requireOwned) {
        requireOwned.run();
        if (actor.getShop() != exactShop) return false;
        NativePacketDispatch.dispatch(actor.getClient(), new NPCShopHandler(), Unpooled.buffer(1).writeByte(3),
                requireOwned, () -> { });
        if (actor.getShop() == exactShop) throw new IllegalStateException("native_shop_close_not_observed");
        return true;
    }
}
