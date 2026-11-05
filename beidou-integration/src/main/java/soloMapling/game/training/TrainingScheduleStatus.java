package soloMapling.game.training;

import org.gms.client.inventory.InventoryType;
import soloMapling.game.inventory.SaleItem;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded business progress; inventories, native role resources and action work stay with their owners. */
public record TrainingScheduleStatus(State state, Stage stage, String reason, long runId,
                                     long cyclesCompleted, Integer currentMapId, Integer destinationMapId,
                                     Cycle currentCycle, List<Cycle> recentCycles, ObservationAdmission observationAdmission) {
    public enum State { IDLE, RUNNING, BLOCKED, STOPPING, FAILED }
    public enum Stage { STARTING, TO_TRAINING, HUNTING, TO_SHOP, SELLING, RETURNING, STOPPED }

    public TrainingScheduleStatus { recentCycles = List.copyOf(recentCycles); }

    /** Last unentered read retains its actual admission and settled work, without native objects or error text. */
    public record ObservationAdmission(CharacterCommandLane.Status status, CharacterCommandLane.Rejection rejection,
                                        ExecutionScope.WorkSnapshot step, ExecutionScope.WorkSnapshot parent,
                                        boolean scopeActive) {
        public Map<String, Object> toMap() {
            return Map.of("status", status.name(), "rejection", rejection.name(), "step", work(step),
                    "parent", work(parent), "scope_active", scopeActive);
        }

        private static Map<String, Object> work(ExecutionScope.WorkSnapshot value) {
            return Map.of("id", value.id(), "cancel_requested", value.cancelRequested(), "rejected", value.rejected(),
                    "body_state", value.bodyState().name(), "skipped", value.skipped(),
                    "carrier_settled", value.carrierSettled(), "settled", value.settled());
        }
    }

    public record BagCapacity(InventoryType inventoryType, int slotLimit, int freeSlots) {
        public Map<String, Object> toMap() {
            return Map.of("inventory_type", inventoryType.name(), "slot_limit", slotLimit, "free_slots", freeSlots);
        }
    }

    public record InventorySummary(List<BagCapacity> bags, int meso, Map<Integer, Integer> quantities,
                                   boolean quantitiesTruncated) {
        public InventorySummary { bags = List.copyOf(bags); quantities = Map.copyOf(quantities); }

        public Map<String, Object> toMap() {
            return Map.of("bags", bags.stream().map(BagCapacity::toMap).toList(), "meso", meso,
                    "quantities", quantities, "quantities_truncated", quantitiesTruncated);
        }
    }

    public record HuntingSummary(long activityId, int kills, String endReason) {
        public Map<String, Object> toMap() {
            var result = new LinkedHashMap<String, Object>();
            result.put("activity_id", activityId);
            result.put("kills", kills);
            result.put("end_reason", endReason);
            return result;
        }
    }

    public record LootSummary(long baselineSequence, long latestSequence, long collectedObjects,
                               long collectedQuantity, boolean indeterminate) {
        public Map<String, Object> toMap() {
            return Map.of("baseline_sequence", baselineSequence, "latest_sequence", latestSequence,
                    "collected_objects", collectedObjects, "collected_quantity", collectedQuantity,
                    "indeterminate", indeterminate);
        }
    }

    public record PortalStep(int fromMapId, int exitPortalId, String exitPortalName,
                             int toMapId, int entryPortalId, String entryPortalName, int exitX, int exitY, int x, int y) {
        public Map<String, Object> toMap() {
            return Map.of("from_map_id", fromMapId, "exit_portal_id", exitPortalId,
                    "exit_portal_name", exitPortalName, "to_map_id", toMapId,
                    "entry_portal_id", entryPortalId, "entry_portal_name", entryPortalName,
                    "exit_x", exitX, "exit_y", exitY, "x", x, "y", y);
        }
    }

    public record SaleFact(SaleItem item, int soldQuantity, int mesosGained) {
        public Map<String, Object> toMap() {
            return Map.of("item", item.toMap(), "sold_quantity", soldQuantity, "mesos_gained", mesosGained);
        }
    }

    public record SaleSummary(String outcome, String reason, int soldEntries, int soldQuantity, int mesoBefore, int mesoAfter,
                              List<SaleFact> recentSales, boolean salesTruncated) {
        public SaleSummary { recentSales = List.copyOf(recentSales); }

        public Map<String, Object> toMap() {
            var value = new LinkedHashMap<String, Object>();
            value.put("outcome", outcome); value.put("reason", reason);
            value.put("sold_entries", soldEntries); value.put("sold_quantity", soldQuantity);
            value.put("meso_before", mesoBefore); value.put("meso_after", mesoAfter);
            value.put("recent_sales", recentSales.stream().map(SaleFact::toMap).toList());
            value.put("sales_truncated", salesTruncated);
            return value;
        }
    }

    public record Cycle(long index, InventorySummary huntingStarted, InventorySummary capacityTriggered,
                        HuntingSummary hunting, LootSummary loot, InventorySummary beforeSale, InventorySummary afterSale,
                        InventorySummary returned, SaleSummary sale, List<PortalStep> portalSteps,
                        boolean portalStepsTruncated, boolean complete) {
        public Cycle { portalSteps = List.copyOf(portalSteps); }

        public Map<String, Object> toMap() {
            var result = new LinkedHashMap<String, Object>();
            result.put("index", index);
            result.put("hunting_started", huntingStarted == null ? null : huntingStarted.toMap());
            result.put("capacity_triggered", capacityTriggered == null ? null : capacityTriggered.toMap());
            result.put("hunting", hunting == null ? null : hunting.toMap());
            result.put("loot", loot == null ? null : loot.toMap());
            result.put("before_sale", beforeSale == null ? null : beforeSale.toMap());
            result.put("after_sale", afterSale == null ? null : afterSale.toMap());
            result.put("returned", returned == null ? null : returned.toMap());
            result.put("sale", sale == null ? null : sale.toMap());
            result.put("portal_steps", portalSteps.stream().map(PortalStep::toMap).toList());
            result.put("portal_steps_truncated", portalStepsTruncated);
            result.put("complete", complete);
            return result;
        }
    }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("state", state.name());
        result.put("stage", stage.name());
        result.put("reason", reason);
        result.put("run_id", runId);
        result.put("cycles_completed", cyclesCompleted);
        result.put("current_map_id", currentMapId);
        result.put("destination_map_id", destinationMapId);
        result.put("current_cycle", currentCycle == null ? null : currentCycle.toMap());
        result.put("recent_cycles", recentCycles.stream().map(Cycle::toMap).toList());
        result.put("observation_admission", observationAdmission == null ? null : observationAdmission.toMap());
        return result;
    }
}
