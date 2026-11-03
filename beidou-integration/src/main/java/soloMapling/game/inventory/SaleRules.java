package soloMapling.game.inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Candidate selection only; native shop rules retain final sale eligibility and price authority. */
public record SaleRules(Set<Integer> allowedItemIds, Set<Integer> retainedItemIds,
                        Map<Integer, Integer> retainedQuantities) {
    public SaleRules {
        allowedItemIds = Set.copyOf(allowedItemIds);
        retainedItemIds = Set.copyOf(retainedItemIds);
        retainedQuantities = Map.copyOf(retainedQuantities);
        if (allowedItemIds.stream().anyMatch(id -> id < 1) || retainedItemIds.stream().anyMatch(id -> id < 1)
                || retainedQuantities.entrySet().stream().anyMatch(entry -> entry.getKey() < 1 || entry.getValue() < 0)) {
            throw new IllegalArgumentException("invalid_sale_rules");
        }
    }

    public List<SaleItem> candidates(InventorySnapshot snapshot) {
        var reserve = new HashMap<>(retainedQuantities);
        var candidates = new ArrayList<SaleItem>();
        var slots = snapshot.bags().stream().flatMap(bag -> bag.items().stream()).toList();
        for (var item : slots) {
            if (!allowedItemIds.contains(item.itemId()) || retainedItemIds.contains(item.itemId())) continue;
            int kept = Math.min(item.quantity(), reserve.getOrDefault(item.itemId(), 0));
            reserve.put(item.itemId(), reserve.getOrDefault(item.itemId(), 0) - kept);
            int quantity = item.quantity() - kept;
            if (quantity > 0) candidates.add(new SaleItem(item.inventoryType(), item.slot(), item.itemId(), quantity));
        }
        return List.copyOf(candidates);
    }
}
