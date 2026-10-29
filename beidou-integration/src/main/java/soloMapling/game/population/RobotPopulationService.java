package soloMapling.game.population;

import org.gms.soloacceptance.actor.McpControlModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import soloMapling.game.context.WorldEpoch;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.navigation.NavigationModule;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.RuntimeModule;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns complete environment robot instances; attaching an existing MCP role only applies its type. */
public final class RobotPopulationService {
    private static final Logger LOG = LoggerFactory.getLogger(RobotPopulationService.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);
    private final List<String> configPaths;
    private final RobotModuleRegistry registry = new RobotModuleRegistry();
    private final Map<String, Instance> instances = new LinkedHashMap<>();
    private volatile RobotPopulationConfig configuration;
    private WorldEpoch.Token epoch;

    public RobotPopulationService(Environment environment) {
        configPaths = Binder.get(environment).bind("solomapling.robot-population-config", Bindable.listOf(String.class))
                .orElse(List.of());
        registry.register(McpControlModule.DEFINITION, Map::copyOf);
        registry.register(NavigationModule.DEFINITION, NavigationModule::parseConfig);
        registry.register(RobotPopulationModule.definition(this), ignored -> null);
    }

    public RobotPopulationConfig configuration() { return configuration; }

    public void onWorldStarted() {
        var loaded = RobotPopulationConfig.load(configPaths, registry);
        synchronized (instances) {
            configuration = loaded;
            epoch = WorldEpoch.snapshot().token();
        }
        loaded.initialPopulation().forEach((type, count) -> {
            for (int created = 0; created < count; created++) create(type);
            LOG.info("Robot population type={} initialized={} source={}", type, count, loaded.loadedFrom());
        });
    }

    public Assembly assemble(String typeId, CharacterRuntime runtime) {
        var type = configuration.types().get(typeId);
        if (type == null) throw new IllegalArgumentException("unknown_robot_type:" + typeId);
        return assemble(type, runtime);
    }

    private static Assembly assemble(RobotPopulationConfig.RobotType type, CharacterRuntime runtime) {
        Map<String, CharacterRuntime.MountedModule<?>> modules = new LinkedHashMap<>();
        for (var module : type.modules()) modules.put(module.instanceId(), module.mount().apply(runtime));
        return new Assembly(Collections.unmodifiableMap(modules));
    }

    public record Assembly(Map<String, CharacterRuntime.MountedModule<?>> modules) {
        public <M extends RuntimeModule> Optional<CharacterRuntime.MountedModule<M>> find(Class<M> type) {
            return modules.values().stream().filter(module -> type.isInstance(module.instance())).findFirst()
                    .map(module -> new CharacterRuntime.MountedModule<>(module.reference(), type.cast(module.instance())));
        }
    }

    public Map<String, Object> create(String typeId) {
        final Instance instance;
        final RobotPopulationConfig.RobotType type;
        synchronized (instances) {
            var world = WorldEpoch.snapshot();
            if (!world.active() || world.token() != epoch) throw new IllegalStateException("robot_population_world_unavailable");
            type = configuration.types().get(typeId);
            if (type == null) throw new IllegalArgumentException("unknown_robot_type:" + typeId);
            if (type.character() == null) throw new IllegalArgumentException("robot_type_has_no_creation_source:" + typeId);
            var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8),
                    body -> { var thread = new Thread(body, "robot-character-lane"); thread.setDaemon(true); return thread; });
            var runtime = new CharacterRuntime(epoch,
                    new CharacterRuntime.Options(8, 1, new ExecutionScope.Limits(16, 2, 16, 2)), executor);
            instance = new Instance(typeId, runtime);
            instances.put(runtime.id(), instance);
        }
        var outcome = new CompletableFuture<Void>();
        var failure = new AtomicReference<Throwable>();
        var initialized = new AtomicBoolean();
        var opening = instance.runtime().acceptOpeningWork();
        instance.runtime().submitOpening(opening, () -> {
            try {
                EnvironmentRobotOrigin.receive(instance.runtime(), opening, type.character());
                instance.runtime().bindAdoptedOrigin();
                assemble(type, instance.runtime());
                initialized.set(true);
            } catch (RuntimeException | Error exception) {
                failure.set(exception);
            }
        }, () -> {
            if (failure.get() != null) outcome.completeExceptionally(failure.get());
            else if (initialized.get()) outcome.complete(null);
            else outcome.completeExceptionally(new IllegalStateException("robot_creation_cancelled"));
        });
        try {
            await(outcome, "robot_creation_not_settled");
        } catch (RuntimeException | Error creationFailure) {
            try { close(instance, Optional.empty()); }
            catch (RuntimeException cleanupFailure) { creationFailure.addSuppressed(cleanupFailure); }
            throw creationFailure;
        }
        return Map.of("instance_id", instance.runtime().id(), "type_id", typeId,
                "runtime_id", instance.runtime().id(), "character_id", instance.runtime().snapshot().characterId());
    }

    public Map<String, Object> remove(String instanceId) {
        Instance instance;
        synchronized (instances) { instance = instances.get(instanceId); }
        if (instance == null) throw new IllegalArgumentException("unknown_robot_instance:" + instanceId);
        close(instance, Optional.empty());
        return Map.of("instance_id", instanceId, "type_id", instance.typeId(), "removed", true);
    }

    public void onWorldStopping(Optional<ExecutionScope.WorldStopTicket> stop) {
        Map<String, Instance> closing;
        synchronized (instances) { epoch = null; closing = new LinkedHashMap<>(instances); }
        RuntimeException firstFailure = null;
        for (var instance : closing.values()) {
            try { close(instance, stop); }
            catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure != null) throw firstFailure;
    }

    private void close(Instance instance, Optional<ExecutionScope.WorldStopTicket> stop) {
        var runtime = instance.runtime();
        runtime.beginClose(stop);
        await(runtime.whenModulesDrained(), "robot_modules_not_drained");
        if (!runtime.originReleased()) {
            var cleanup = runtime.acquireOriginCleanup();
            var done = new CompletableFuture<Void>();
            var failure = new AtomicReference<RuntimeException>();
            runtime.submitOriginCleanup(cleanup, () -> {
                try { runtime.releaseOrigin(cleanup); }
                catch (RuntimeException exception) { failure.set(exception); }
            }, () -> {
                if (failure.get() == null) done.complete(null);
                else done.completeExceptionally(failure.get());
            });
            await(done, "robot_origin_not_released");
        }
        if (runtime.awaitClosed(CLOSE_TIMEOUT) != ExecutionScope.DrainResult.DRAINED
                || runtime.snapshot().state() != CharacterRuntime.State.CLOSED) {
            throw new IllegalStateException("robot_close_unverified");
        }
        synchronized (instances) { instances.remove(runtime.id(), instance); }
    }

    private static <T> T await(CompletionStage<T> stage, String reason) {
        try { return stage.toCompletableFuture().get(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(reason, interrupted);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(reason, failure.getCause());
        } catch (TimeoutException timeout) { throw new IllegalStateException(reason, timeout); }
    }

    private record Instance(String typeId, CharacterRuntime runtime) { }
}
