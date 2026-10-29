package soloMapling.game.runtime;

/** Directory availability is separate from the operation's own typed business result. */
public record ModuleCall<R>(Status status, R result, String reason) {
    public enum Status { SUCCEEDED, UNAVAILABLE }
}
