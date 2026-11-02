package soloMapling.game.potion;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** The potion module's adapted items; native WZ remains the source of their effects. */
public enum PotionItem {
    ELIXIR(2000004, Group.RESTORE, "特殊药水，原生HP/MP基础恢复比例各50%"),
    ALL_CURE(2050004, Group.CLEANSE, "万能疗伤药，原生解除六类可解异常");

    public enum Group {
        RESTORE, CLEANSE;

        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    private final int itemId;
    private final Group group;
    private final String description;

    PotionItem(int itemId, Group group, String description) {
        this.itemId = itemId;
        this.group = group;
        this.description = description;
    }

    public int itemId() { return itemId; }
    public Group group() { return group; }

    public static PotionItem find(int itemId) {
        return Arrays.stream(values()).filter(item -> item.itemId == itemId).findFirst().orElse(null);
    }

    public static String supportedDescription() {
        return Arrays.stream(values()).map(item -> item.itemId + "=" + item.name() + "（" + item.description
                + "，冷却组" + item.group.id() + "）").collect(Collectors.joining("；"));
    }
}
