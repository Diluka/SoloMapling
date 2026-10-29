package soloMapling.game.navigation;

/** Target coordinates and this call's planning/movement budget in the role's current scene. */
public record MoveToRequest(int x, int y, int timeoutMs) {
    public static final int MAX_TIMEOUT_MS = 120_000;

    public MoveToRequest(int x, int y) { this(x, y, MAX_TIMEOUT_MS); }

    public MoveToRequest {
        if (x < Short.MIN_VALUE || x > Short.MAX_VALUE || y < Short.MIN_VALUE || y > Short.MAX_VALUE) {
            throw new IllegalArgumentException("navigation_coordinate_out_of_range");
        }
        if (timeoutMs < 1 || timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("navigation_timeout_out_of_range");
        }
    }
}
