package soloMapling.game.scene;

import soloMapling.game.action.NativeContactDamageActions;

import java.util.concurrent.TimeUnit;

/** Role-owned client timing and bounded receipts; native objects remain the source of current state. */
public final class SceneCharacterState {
    public record ContactSnapshot(long count, NativeContactDamageActions.ContactResult latest) { }

    private long nextContactNanos;
    private volatile ContactSnapshot contacts = new ContactSnapshot(0, null);
    private Boolean lastAlive;

    public SceneCharacterState() { }

    long nextContactNanos() { return nextContactNanos; }

    void recordContact(NativeContactDamageActions.ContactResult result) {
        if (result.dispatched()) {
            nextContactNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            contacts = new ContactSnapshot(contacts.count() + 1, result);
        }
    }

    public ContactSnapshot contactSnapshot() { return contacts; }

    boolean observeLife(boolean alive) {
        boolean changed = lastAlive == null || lastAlive != alive;
        lastAlive = alive;
        return changed;
    }
}
