package soloMapling.game.runtime;

import java.util.Map;

/** The initial assembly layout shared by every character runtime. */
public final class ModuleSlots {
    public static final String CONTROLLER = "controller";
    public static final String NAVIGATION = "navigation";
    public static final String COMBAT = "combat";
    public static final String CONVERSATION = "conversation";
    public static final String PARTY_COORDINATION = "party_coordination";
    public static final String TRADE = "trade";
    public static final String SHOP_MANAGEMENT = "shop_management";
    public static final String SHOP_INTERACTION = "shop_interaction";
    public static final String INTERACTIVE_SERVICE = "interactive_service";

    private static final Map<String, SlotSpec> STANDARD = Map.of(
            CONTROLLER, new SlotSpec(CONTROLLER, 1),
            NAVIGATION, new SlotSpec(NAVIGATION, 1),
            COMBAT, new SlotSpec(COMBAT, 1),
            CONVERSATION, new SlotSpec(CONVERSATION, 1),
            PARTY_COORDINATION, new SlotSpec(PARTY_COORDINATION, 1),
            TRADE, new SlotSpec(TRADE, 1),
            SHOP_MANAGEMENT, new SlotSpec(SHOP_MANAGEMENT, 1),
            SHOP_INTERACTION, new SlotSpec(SHOP_INTERACTION, 1),
            INTERACTIVE_SERVICE, new SlotSpec(INTERACTIVE_SERVICE, 1));

    private ModuleSlots() { }

    public static Map<String, SlotSpec> standard() { return STANDARD; }
}
