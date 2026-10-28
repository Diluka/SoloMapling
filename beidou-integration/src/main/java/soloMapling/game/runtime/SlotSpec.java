package soloMapling.game.runtime;

/** A role-runtime assembly responsibility and its instance capacity. */
public record SlotSpec(String id, int capacity) {
    public SlotSpec {
        ModuleDefinition.requireIdentifier(id);
        if (capacity < 1) throw new IllegalArgumentException("invalid_slot_capacity");
    }
}
