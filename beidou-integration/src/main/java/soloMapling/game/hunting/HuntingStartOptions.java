package soloMapling.game.hunting;

/** Conditions for one hunting activity; omitted values use the mounted module's style and no deadline. */
public record HuntingStartOptions(Style style, Long durationMs) {
    public enum Style { AUTO, CAMP, PATROL, ROAM, STACK }
    public static final HuntingStartOptions DEFAULT = new HuntingStartOptions(null, null);

    public HuntingStartOptions {
        if (durationMs != null && durationMs < 1) throw new IllegalArgumentException("invalid_hunting_duration");
    }
}
