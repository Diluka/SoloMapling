package org.gms.soloscripting;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.gms.client.Client;
import org.gms.scripting.event.EventManager;
import soloMapling.game.script.nativeenv.NativeScriptAdapter;
import soloMapling.game.script.nativeenv.NativeScriptRequest;
import soloMapling.game.script.nativeenv.NativeScriptSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** An independently owned native event manager, never a channel's shared EventManager. */
public final class NativeEventScripts implements NativeScriptAdapter {
    private final Client client;
    private final Function<String, GraalJSScriptEngine> loader;

    public NativeEventScripts(Client client) { this(client, NativeScriptLoader::load); }
    NativeEventScripts(Client client, Function<String, GraalJSScriptEngine> loader) {
        this.client = Objects.requireNonNull(client);
        this.loader = Objects.requireNonNull(loader);
    }

    @Override public AutoCloseable enter(NativeScriptSession session) {
        return NativeWorldScriptSupport.enterClient(client);
    }

    @Override public Object start(NativeScriptSession session, NativeScriptRequest request) throws Exception {
        if (!"event".equals(request.kind())) throw NativeWorldScriptSupport.invalid("native_script_kind_mismatch");
        if (request.options().size() != 1) throw NativeWorldScriptSupport.invalid("invalid_native_script_options");
        String name = NativeWorldScriptSupport.scriptName(request.options().get("script"));
        var role = NativeWorldScriptSupport.role(client);
        String path = "event/" + name + ".js";
        NativeWorldScriptSupport.load(session, loader, path);
        EventManager manager = new EventManager(role.channel(), session.invocable(), name);
        session.attachment(new Bound(role, manager, path));
        session.ownEventManager(manager);
        session.engine().put("em", manager);
        return invoke(session, request);
    }

    @Override public Object invoke(NativeScriptSession session, NativeScriptRequest request) throws Exception {
        if (!request.options().isEmpty() && !"start".equals(request.operation())) {
            throw NativeWorldScriptSupport.invalid("invalid_native_script_options");
        }
        bound(session);
        String entry = NativeWorldScriptSupport.entry(request, "init");
        // EventScriptManager's native initialization passes one null argument to init.
        Object[] args = request.args().isEmpty() && "init".equals(entry)
                ? new Object[]{null} : request.args().toArray();
        return session.invocable().invokeFunction(entry, args);
    }

    @Override public void validate(NativeScriptSession session) { bound(session); }

    @Override public void close(NativeScriptSession session) {
        Object attached = session.attachment();
        if (attached instanceof Bound bound) {
            // Core scheduler hooks retain the original scope's cleanup debt until native async disposal settles.
            // A script may have already used the original em.cancel(); do not call its non-idempotent body twice.
            if (bound.manager().getChannelServer() != null || bound.manager().getIv() != null) bound.manager().cancel();
            if (bound.manager().getChannelServer() != null || bound.manager().getIv() != null) {
                throw new IllegalStateException("native_event_cleanup_unverified");
            }
        }
        session.markNativeDisposed();
    }

    @Override public Map<String, Object> describe(NativeScriptSession session) {
        if (!(session.attachment() instanceof Bound bound)) return descriptor();
        Map<String, Object> value = new LinkedHashMap<>(descriptor());
        value.putAll(bound.role().describe());
        value.put("script_path", bound.path());
        value.put("event_name", bound.manager().getName());
        return Map.copyOf(value);
    }

    public static Map<String, Object> descriptor() {
        return Map.ofEntries(Map.entry("kind", "event"), Map.entry("globals", List.of("em")),
                Map.entry("object_types", List.of(EventManager.class.getName())),
                Map.entry("operations", List.of("start", "invoke", "close")),
                Map.entry("start_options", List.of("script(existing native event script basename)")),
                Map.entry("entrypoints", List.of("init")),
                Map.entry("invoke_entry", "existing function declared by the selected native event script"),
                Map.entry("entry_arguments", "original callback arguments; native init receives null by default"),
                Map.entry("source", "optional_code_in_the_same_real_native_engine"),
                Map.entry("ownership", "role_owned_native_event_manager_same_managed_invocable"),
                Map.entry("instance_creation_supported", false),
                Map.entry("unsupported_reason", "native_event_instances_not_supported"),
                Map.entry("limits", "em.schedule callbacks reuse the same environment; owned EIM/map-instance creation is unavailable"));
    }

    private Bound bound(NativeScriptSession session) {
        Bound bound = NativeWorldScriptSupport.attachment(session, Bound.class);
        bound.role().requireCurrent(false);
        if (bound.manager().getChannelServer() != bound.role().channel()) {
            throw NativeWorldScriptSupport.invalid("stale_native_event_manager");
        }
        return bound;
    }

    private record Bound(NativeWorldScriptSupport.BoundRole role, EventManager manager, String path) { }
}
