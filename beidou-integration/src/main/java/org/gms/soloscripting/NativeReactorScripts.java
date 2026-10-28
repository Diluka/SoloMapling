package org.gms.soloscripting;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.gms.client.Client;
import org.gms.scripting.reactor.ReactorActionManager;
import org.gms.server.maps.Reactor;
import soloMapling.game.script.nativeenv.NativeScriptAdapter;
import soloMapling.game.script.nativeenv.NativeScriptRequest;
import soloMapling.game.script.nativeenv.NativeScriptSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Original reactor API and entries with a real reactor from the role's captured map. */
public final class NativeReactorScripts implements NativeScriptAdapter {
    private static final List<String> ENTRIES = List.of("hit", "act", "touch", "untouch");
    private final Client client;
    private final Function<String, GraalJSScriptEngine> loader;

    public NativeReactorScripts(Client client) { this(client, NativeScriptLoader::load); }
    NativeReactorScripts(Client client, Function<String, GraalJSScriptEngine> loader) {
        this.client = Objects.requireNonNull(client);
        this.loader = Objects.requireNonNull(loader);
    }

    @Override public AutoCloseable enter(NativeScriptSession session) {
        return NativeWorldScriptSupport.enterClient(client);
    }

    @Override public Object start(NativeScriptSession session, NativeScriptRequest request) throws Exception {
        if (!"reactor".equals(request.kind())) throw NativeWorldScriptSupport.invalid("native_script_kind_mismatch");
        NativeWorldScriptSupport.noArguments(request);
        requireEntry(request);
        var role = NativeWorldScriptSupport.role(client);
        int oid = NativeWorldScriptSupport.integerOption(request, "reactor_oid");
        if (oid == 0) throw NativeWorldScriptSupport.invalid("invalid_reactor_oid");
        Reactor reactor = role.map().getReactorByOid(oid);
        if (reactor == null || reactor.getMap() != role.map()) {
            throw NativeWorldScriptSupport.invalid("native_reactor_not_found");
        }
        String path = "reactor/" + reactor.getId() + ".js";
        session.attachment(new Bound(role, reactor, path));
        NativeWorldScriptSupport.load(session, loader, path);
        session.engine().put("rm", new ReactorActionManager(client, reactor, session.invocable()));
        return invoke(session, request);
    }

    @Override public Object invoke(NativeScriptSession session, NativeScriptRequest request) throws Exception {
        if (!request.options().isEmpty() && !"start".equals(request.operation())) {
            throw NativeWorldScriptSupport.invalid("invalid_native_script_options");
        }
        NativeWorldScriptSupport.noArguments(request);
        String entry = requireEntry(request);
        bound(session);
        try {
            return session.invocable().invokeFunction(entry);
        } catch (NoSuchMethodException absent) {
            // Native ReactorScriptManager.onHit treats hit as optional; the other callbacks remain errors.
            if ("hit".equals(entry)) return null;
            throw absent;
        }
    }

    @Override public void validate(NativeScriptSession session) { bound(session); }

    @Override public void close(NativeScriptSession session) {
        // No map/reactor is created or removed by this binding. Session engine closure belongs to the core.
        session.markNativeDisposed();
    }

    @Override public Map<String, Object> describe(NativeScriptSession session) {
        if (!(session.attachment() instanceof Bound bound)) return descriptor();
        Map<String, Object> value = new LinkedHashMap<>(descriptor());
        value.putAll(bound.role().describe());
        value.put("script_path", bound.path());
        value.put("reactor_oid", bound.reactor().getObjectId());
        value.put("reactor_id", bound.reactor().getId());
        return Map.copyOf(value);
    }

    public static Map<String, Object> descriptor() {
        return Map.ofEntries(Map.entry("kind", "reactor"), Map.entry("globals", List.of("rm")),
                Map.entry("object_types", List.of(ReactorActionManager.class.getName())),
                Map.entry("operations", List.of("start", "invoke", "close")),
                Map.entry("start_options", List.of("reactor_oid(actual current-map reactor object ID)")),
                Map.entry("entrypoints", ENTRIES), Map.entry("entry_arguments", "native no-argument reactor callback"),
                Map.entry("source", "optional_code_in_the_same_real_native_engine"),
                Map.entry("ownership", "role_owned_engine_borrowed_exact_reactor"),
                Map.entry("limits", "native script callback; not a client reactor-hit packet or reward guarantee"));
    }

    private String requireEntry(NativeScriptRequest request) {
        String entry = NativeWorldScriptSupport.entry(request, "act");
        if (!ENTRIES.contains(entry)) throw NativeWorldScriptSupport.invalid("invalid_reactor_script_entry");
        return entry;
    }

    private Bound bound(NativeScriptSession session) {
        Bound bound = NativeWorldScriptSupport.attachment(session, Bound.class);
        bound.role().requireCurrent(true);
        if (bound.reactor().getMap() != bound.role().map()
                || bound.role().map().getReactorByOid(bound.reactor().getObjectId()) != bound.reactor()) {
            throw NativeWorldScriptSupport.invalid("stale_native_reactor");
        }
        return bound;
    }

    private record Bound(NativeWorldScriptSupport.BoundRole role, Reactor reactor, String path) { }
}
