package org.gms.soloscripting;

import org.gms.client.Client;
import org.gms.scripting.portal.PortalPlayerInteraction;
import org.gms.server.maps.Portal;
import soloMapling.game.script.nativeenv.NativeScriptAdapter;
import soloMapling.game.script.nativeenv.NativeScriptRequest;
import soloMapling.game.script.nativeenv.NativeScriptSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A role-owned original portal engine bound to an exact portal from the role's actual map. */
public final class NativePortalScripts implements NativeScriptAdapter {
    private final Client client;

    public NativePortalScripts(Client client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override public AutoCloseable enter(NativeScriptSession session) {
        return NativeWorldScriptSupport.enterClient(client);
    }

    @Override public Object start(NativeScriptSession session, NativeScriptRequest request) throws Exception {
        if (!"portal".equals(request.kind())) throw NativeWorldScriptSupport.invalid("native_script_kind_mismatch");
        NativeWorldScriptSupport.noArguments(request);
        if (!"enter".equals(NativeWorldScriptSupport.entry(request, "enter"))) {
            throw NativeWorldScriptSupport.invalid("invalid_portal_script_entry");
        }
        var role = NativeWorldScriptSupport.role(client);
        int id = NativeWorldScriptSupport.integerOption(request, "portal_id");
        Portal portal = role.map().getPortal(id);
        if (portal == null) throw NativeWorldScriptSupport.invalid("native_portal_not_found");
        String name = NativeWorldScriptSupport.scriptName(portal.getScriptName());
        Bound bound = new Bound(role, portal, "portal/" + name + ".js", new PortalPlayerInteraction(client, portal));
        session.attachment(bound);
        NativeWorldScriptSupport.load(session, bound.path());
        session.engine().put("pi", bound.interaction());
        return invoke(session, request);
    }

    @Override public Object invoke(NativeScriptSession session, NativeScriptRequest request) throws Exception {
        if (!request.options().isEmpty() && !"start".equals(request.operation())) {
            throw NativeWorldScriptSupport.invalid("invalid_native_script_options");
        }
        NativeWorldScriptSupport.noArguments(request);
        if (!"enter".equals(NativeWorldScriptSupport.entry(request, "enter"))) {
            throw NativeWorldScriptSupport.invalid("invalid_portal_script_entry");
        }
        Bound bound = bound(session);
        return session.invocable().invokeFunction("enter", bound.interaction());
    }

    @Override public void validate(NativeScriptSession session) { bound(session); }

    @Override public void close(NativeScriptSession session) {
        // The portal and map are borrowed. Only this session's independently loaded engine is owned.
        session.markNativeDisposed();
    }

    @Override public Map<String, Object> describe(NativeScriptSession session) {
        if (!(session.attachment() instanceof Bound bound)) return descriptor();
        Map<String, Object> value = new LinkedHashMap<>(descriptor());
        value.putAll(bound.role().describe());
        value.put("script_path", bound.path());
        value.put("portal_id", bound.portal().getId());
        value.put("portal_name", bound.portal().getName());
        return Map.copyOf(value);
    }

    public static Map<String, Object> descriptor() {
        return Map.ofEntries(Map.entry("kind", "portal"), Map.entry("globals", List.of("pi")),
                Map.entry("object_types", List.of(PortalPlayerInteraction.class.getName())),
                Map.entry("operations", List.of("start", "invoke", "close")),
                Map.entry("start_options", List.of("portal_id(actual current-map portal ID)")),
                Map.entry("entrypoints", List.of("enter")),
                Map.entry("entry_arguments", "enter(pi) receives the same actual pi object as the pi global"),
                Map.entry("source", "optional_code_in_the_same_real_native_engine"),
                Map.entry("ownership", "role_owned_engine_borrowed_exact_portal"),
                Map.entry("limits", "native script execution; not the client portal-distance or packet admission flow"));
    }

    private Bound bound(NativeScriptSession session) {
        Bound bound = NativeWorldScriptSupport.attachment(session, Bound.class);
        bound.role().requireCurrent(true);
        if (bound.role().map().getPortal(bound.portal().getId()) != bound.portal()) {
            throw NativeWorldScriptSupport.invalid("stale_native_portal");
        }
        if (!bound.path().equals("portal/" + bound.portal().getScriptName() + ".js")) {
            throw NativeWorldScriptSupport.invalid("stale_native_portal_script");
        }
        return bound;
    }

    private record Bound(NativeWorldScriptSupport.BoundRole role, Portal portal, String path,
                         PortalPlayerInteraction interaction) { }
}
