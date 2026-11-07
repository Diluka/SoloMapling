package soloMapling.game.scene;

import soloMapling.game.action.NativeContactDamageActions;
import soloMapling.game.context.GameContext;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleTask;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** One scene membership's passive client input; independent of hunting and engagement lifetime. */
public final class CharacterDamage {
    private final SceneActorBinding binding;
    private ModuleTask task;

    public CharacterDamage(SceneActorBinding binding) { this.binding = binding; }

    public void start() {
        task = binding.startTask("scene-contact-" + binding.characterRef().character().getId(), this::run);
    }

    public void stop() { if (task != null) task.cancel(); }

    /** Pure observation of bounded contact input; it never invokes TakeDamageHandler or spends the guard clock. */
    public IncomingDamageEstimate estimateIncoming(int windowMs, List<IncomingDamageEstimate.PathSample> path) {
        binding.requireCurrent();
        var actor = binding.characterRef().character();
        long now = System.nanoTime();
        var missing = new ArrayList<String>();
        var threats = new ArrayList<IncomingDamageEstimate.Threat>();
        int defense = NativeContactDamageActions.defense(actor).conservative();
        long remainingProtection = Math.max(0, binding.characterState().nextContactNanos() - now);
        int protectedMs = (int) Math.min(Integer.MAX_VALUE, (remainingProtection + 999_999) / 1_000_000);
        long count = windowMs < protectedMs ? 0 : 1L + (windowMs - protectedMs) / SceneCharacterState.CONTACT_INTERVAL_MS;
        int maximumLoss = 0;
        for (var monster : binding.nativeScene().map().getAllMonsters()) {
            if (!monster.isAlive() || monster.getStats().isFriendly() || monster.getPADamage() <= 0) continue;
            var model = binding.scene().monsters().contactReach(monster, windowMs);
            if (model == null) { missing.add("uncovered_monster_" + monster.getObjectId()); continue; }
            Rectangle possible = null;
            int firstPossible = -1;
            for (int i = 0; i < path.size(); i++) {
                var from = path.get(i);
                var to = i + 1 < path.size() ? path.get(i + 1)
                        : new IncomingDamageEstimate.PathSample(from.position(), windowMs);
                int low = Math.max(protectedMs, from.elapsedMs());
                int high = Math.min(windowMs, to.elapsedMs());
                if (high < low || low > windowMs) continue;
                var start = interpolate(from, to, low);
                var end = interpolate(from, to, high);
                var corridor = new Rectangle(start.x, start.y - 50, 1, 51)
                        .union(new Rectangle(end.x, end.y - 50, 1, 51));
                var reach = binding.scene().monsters().contactReach(monster, high);
                if (reach == null) { missing.add("monster_motion_changed_" + monster.getObjectId()); break; }
                if (!reach.positionUnbounded() && !reach.area().intersects(corridor)) continue;
                var contactArea = reach.positionUnbounded() ? corridor : reach.area();
                possible = possible == null ? contactArea : possible.union(contactArea);
                if (firstPossible < 0) firstPossible = low;
            }
            if (possible == null) continue;
            int damage = NativeContactDamageActions.physicalDamage(monster.getPADamage(), defense);
            maximumLoss = Math.max(maximumLoss, damage);
            threats.add(new IncomingDamageEstimate.Threat(monster.getObjectId(), monster.getId(), possible,
                    firstPossible, damage, model.model()));
        }
        int upper = actor.isHidden() ? 0 : (int) Math.min(Integer.MAX_VALUE, maximumLoss * count);
        // Native shields/deflection may reduce a hit; future medicine, kills and shield resources are never credited.
        var coverage = missing.isEmpty() ? IncomingDamageEstimate.Coverage.SUPPORTED
                : threats.isEmpty() ? IncomingDamageEstimate.Coverage.UNKNOWN : IncomingDamageEstimate.Coverage.PARTIAL;
        return new IncomingDamageEstimate(coverage, now, windowMs, 0, upper, threats, missing);
    }

    private static Point interpolate(IncomingDamageEstimate.PathSample from, IncomingDamageEstimate.PathSample to, int time) {
        if (to.elapsedMs() == from.elapsedMs()) return new Point(to.position());
        double fraction = (time - from.elapsedMs()) / (double) (to.elapsedMs() - from.elapsedMs());
        return new Point((int) Math.round(from.position().x + fraction * (to.position().x - from.position().x)),
                (int) Math.round(from.position().y + fraction * (to.position().y - from.position().y)));
    }

    public IncomingDamageEstimate estimateIncoming(int windowMs, Point position) {
        return estimateIncoming(windowMs, List.of(new IncomingDamageEstimate.PathSample(new Point(position), 0)));
    }

    private void run(ExecutionScope.WorkHandle work) {
        var context = binding.context();
        try {
            while (!work.cancelRequested()) {
                var failure = new AtomicReference<Throwable>();
                var step = context.acceptWork(work);
                try {
                    context.submit("scene-client", step, () -> {}, () -> {
                        try {
                            if (work.cancelRequested()) return;
                            binding.requireCurrent();
                            var server = binding.scene().server();
                            var scene = NativeContextChecks.captureScene(server, binding.characterRef());
                            var actor = scene.actor().character();
                            var state = binding.characterState();
                            if (state.observeLife(actor.isAlive())) {
                                context.recordActivity(work, actor.isAlive() ? "alive" : "dead", "SCENE_LIFE");
                            }
                            binding.scene().presentation().syncLifeState(server, scene, work);
                            if (System.nanoTime() >= state.nextContactNanos()) {
                                var hit = NativeContactDamageActions.contact(server, scene, work);
                                if (hit != null) {
                                    state.recordContact(hit);
                                    if (state.observeLife(actor.isAlive())) {
                                        context.recordActivity(work, actor.isAlive() ? "alive" : "dead", "SCENE_LIFE");
                                    }
                                    binding.scene().presentation().syncLifeState(server, scene, work);
                                    if (hit.handlerError()) throw new IllegalStateException("native_contact_handler_failed");
                                }
                            }
                        } catch (RuntimeException | Error problem) { failure.set(problem); }
                    }, () -> {});
                } catch (RuntimeException rejected) { step.reject(); throw rejected; }
                step.completion().toCompletableFuture().join();
                if (failure.get() instanceof Error fatal) throw fatal;
                if (failure.get() instanceof RuntimeException problem) throw problem;
                try { Thread.sleep(50); }
                catch (InterruptedException cancelled) { return; }
            }
        } catch (CancellationException | GameContext.Invalid | CharacterRuntime.Invalid | ExecutionScope.Invalid stopped) {
            if (binding.active() && !work.cancelRequested() && context.scope().isActive()) throw stopped;
        }
    }
}
