package org.gms.soloscripting;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.channel.Channel;
import org.gms.server.maps.MapleMap;
import org.gms.util.ThreadLocalUtil;
import soloMapling.game.script.nativeenv.NativeScriptRequest;
import soloMapling.game.script.nativeenv.NativeScriptSession;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Source checks shared only by the three native world-script adapters. */
final class NativeWorldScriptSupport {
    private NativeWorldScriptSupport() { }

    static AutoCloseable enterClient(Client client) {
        Client previous = ThreadLocalUtil.getCurrentClient();
        ThreadLocalUtil.setCurrentClient(client);
        return () -> {
            if (previous == null) ThreadLocalUtil.removeCurrentClient();
            else ThreadLocalUtil.setCurrentClient(previous);
        };
    }

    static BoundRole role(Client client) {
        Character player = client.getPlayer();
        if (player == null || player.getClient() != client || player.getMap() == null
                || client.getChannelServer() == null) throw invalid("native_script_role_unavailable");
        return new BoundRole(client, player, player.getMap(), client.getChannelServer());
    }

    static int integerOption(NativeScriptRequest request, String name) {
        if (request.options().size() != 1) throw invalid("invalid_native_script_options");
        Object value = request.options().get(name);
        if (!(value instanceof Number number) || number.longValue() < 0 || number.longValue() > Integer.MAX_VALUE
                || number.doubleValue() != number.longValue()) throw invalid("invalid_" + name);
        return number.intValue();
    }

    static String scriptName(Object name) {
        if (!(name instanceof String text) || !text.matches("[A-Za-z0-9_-]{1,128}")) {
            throw invalid("invalid_native_script_name");
        }
        return text;
    }

    static String entry(NativeScriptRequest request, String fallback) {
        return request.entry() == null ? fallback : request.entry();
    }

    static void noArguments(NativeScriptRequest request) {
        if (!request.args().isEmpty()) throw invalid("invalid_native_script_arguments");
    }

    static void load(NativeScriptSession session, Function<String, GraalJSScriptEngine> loader, String path) {
        GraalJSScriptEngine engine = loader.apply(path);
        if (engine == null) throw invalid("native_script_not_found");
        // The loader's Solo capture hook registers the real engine before its first eval.
        if (session.engine() != engine) throw invalid("native_script_engine_not_captured");
    }

    static <T> T attachment(NativeScriptSession session, Class<T> type) {
        Object value = session.attachment();
        if (!type.isInstance(value)) throw invalid("native_script_context_unavailable");
        return type.cast(value);
    }

    static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }

    record BoundRole(Client client, Character player, MapleMap map, Channel channel) {
        BoundRole { Objects.requireNonNull(client); Objects.requireNonNull(player); Objects.requireNonNull(map); Objects.requireNonNull(channel); }
        void requireCurrent(boolean requireMap) {
            if (client.getPlayer() != player || player.getClient() != client || client.getChannelServer() != channel
                    || requireMap && player.getMap() != map) throw invalid("stale_native_script_context");
        }
        Map<String, Object> describe() {
            return Map.of("character_id", player.getId(), "map_id", map.getId(), "channel_id", channel.getId());
        }
    }
}
