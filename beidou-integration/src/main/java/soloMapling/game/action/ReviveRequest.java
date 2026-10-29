package soloMapling.game.action;

/** A normal native revive click; destination and effects are decided by the native handler. */
public record ReviveRequest() {
    public static final ReviveRequest NORMAL = new ReviveRequest();
}
