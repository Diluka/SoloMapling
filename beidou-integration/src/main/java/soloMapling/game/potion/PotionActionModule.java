package soloMapling.game.potion;

import org.gms.client.Character;
import org.gms.client.Disease;
import org.gms.net.server.Server;
import soloMapling.game.context.NativeContextChecks;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.runtime.ActionModule;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleFunction;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/** Applies an explicit native potion effect on the role's lane and retains independent group cooldowns. */
public final class PotionActionModule implements ActionModule {
    public record Config(int restoreCooldownMs, int cleanseCooldownMs) { }
    public static final Config DEFAULT_CONFIG = new Config(5000, 5000);
    private static final Set<Disease> CURABLE_DISEASES = Set.of(Disease.POISON, Disease.SEAL, Disease.DARKNESS,
            Disease.WEAKEN, Disease.SLOW, Disease.CURSE);
    public static final ModuleFunction<PotionActionModule, PotionRequest, PotionResult> USE = new ModuleFunction<>(
            "potions.use", "对自身应用已适配道具的原生药效，不要求持有或消耗库存。",
            List.of(new ModuleFunction.Parameter("itemId", ModuleFunction.Type.INTEGER, true,
                    PotionItem.supportedDescription() + "；其他整数ID返回UNSUPPORTED")),
            "返回请求itemId、支持项与冷却组、实际HP/MP及异常前后态、恢复增量、解除/保留异常和两组剩余冷却。",
            "原生用药、后态确认及提供者短命令真实退出后返回；生效后的取消保留药效与对应组冷却。",
            (module, arguments) -> {
                var value = (Number) arguments.get("itemId");
                if (value.doubleValue() < Integer.MIN_VALUE || value.doubleValue() > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("potion_item_id_out_of_range");
                }
                return new PotionRequest(value.intValue());
            }, (module, context, work, input) -> module.use(input, context, work), PotionResult::toMap);
    public static final ModuleDefinition<Config, PotionActionModule> DEFINITION = new ModuleDefinition<>(
            "potions", Set.of("potions"), new ExecutionScope.Limits(8, 2, 2, 1),
            config -> new PotionActionModule(config, Server.getInstance()), List.of(USE));

    private final Config config;
    private final Server server;
    // Read and written only by this role's serial native lane; pause keeps this provider instance.
    private long nextRestoreAt;
    private long nextCleanseAt;

    public PotionActionModule(Config config, Server server) { this.config = config; this.server = server; }

    public static Config parseConfig(Map<String, Object> values) {
        if (values.keySet().stream().anyMatch(key -> !key.equals("restore_cooldown_ms")
                && !key.equals("cleanse_cooldown_ms"))) throw new IllegalArgumentException("unknown_potions_config");
        int restore = Integer.parseInt(values.getOrDefault("restore_cooldown_ms", DEFAULT_CONFIG.restoreCooldownMs()).toString());
        int cleanse = Integer.parseInt(values.getOrDefault("cleanse_cooldown_ms", DEFAULT_CONFIG.cleanseCooldownMs()).toString());
        if (restore < 0 || cleanse < 0) throw new IllegalArgumentException("invalid_potions_cooldown");
        return new Config(restore, cleanse);
    }

    public PotionResult use(PotionRequest input, CharacterRuntime.ModuleContext context, ExecutionScope.WorkHandle work) {
        var scene = context.prepareNative("potions", work, () -> {},
                (lane, permit, original, character) -> NativeContextChecks.captureScene(server, character));
        var result = new AtomicReference<PotionResult>();
        var failure = new AtomicReference<Throwable>();
        var step = context.acceptWork();
        try {
            context.submit("potions", step, () -> {}, () -> {
                try {
                    NativeContextChecks.requireSceneCurrent(server, scene);
                    var actor = scene.actor().character();
                    var item = PotionItem.find(input.itemId());
                    var before = state(actor);
                    var after = before;
                    long now = server.getCurrentTime();
                    PotionResult.Outcome outcome;
                    boolean dispatched = false;
                    if (work.cancelRequested()) outcome = PotionResult.Outcome.CANCELLED;
                    else if (item == null) outcome = PotionResult.Outcome.UNSUPPORTED;
                    else if (!before.alive()) outcome = PotionResult.Outcome.DEAD;
                    else if ((item.group() == PotionItem.Group.RESTORE ? nextRestoreAt : nextCleanseAt) > now) {
                        outcome = PotionResult.Outcome.COOLDOWN;
                    } else {
                        dispatched = true;
                        actor.getAbstractPlayerInteraction().useItem(item.itemId());
                        after = state(actor);
                        var remaining = after.diseases();
                        boolean effective = item.group() == PotionItem.Group.RESTORE
                                ? after.hp() > before.hp() || after.mp() > before.mp()
                                : before.diseases().stream().anyMatch(disease -> CURABLE_DISEASES.contains(disease)
                                        && !remaining.contains(disease));
                        outcome = effective ? PotionResult.Outcome.APPLIED : PotionResult.Outcome.NO_EFFECT;
                        now = server.getCurrentTime();
                        if (effective) {
                            if (item.group() == PotionItem.Group.RESTORE) nextRestoreAt = now + config.restoreCooldownMs();
                            else nextCleanseAt = now + config.cleanseCooldownMs();
                        }
                    }
                    var observedAfter = after;
                    result.set(new PotionResult(outcome, input.itemId(), item, before, after,
                            Math.max(0, after.hp() - before.hp()), Math.max(0, after.mp() - before.mp()),
                            before.diseases().stream().filter(disease -> !observedAfter.diseases().contains(disease)).toList(),
                            new PotionResult.Cooldowns(Math.max(0, nextRestoreAt - now), Math.max(0, nextCleanseAt - now)),
                            dispatched, work.cancelRequested()));
                } catch (RuntimeException | Error stopped) { failure.set(stopped); }
            }, () -> {});
        } catch (RuntimeException rejected) {
            step.reject();
            throw rejected;
        }
        step.completion().toCompletableFuture().join();
        if (failure.get() instanceof RuntimeException unavailable) throw unavailable;
        if (failure.get() instanceof Error fatal) throw fatal;
        if (result.get() == null) throw new CancellationException("potion_work_not_entered");
        return result.get();
    }

    private static PotionResult.State state(Character actor) {
        return new PotionResult.State(actor.isAlive(), actor.getHp(), actor.getMp(), actor.getCurrentMaxHp(), actor.getCurrentMaxMp(),
                actor.getAllDiseases().keySet().stream().filter(disease -> disease != Disease.NULL && disease != Disease.FISHABLE)
                        .sorted().toList());
    }
}
