package soloMapling.game.travel;

import org.gms.provider.Data;
import org.gms.provider.DataDirectoryEntry;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import org.gms.server.maps.Portal;
import org.gms.server.maps.PortalFactory;
import soloMapling.game.execution.ExecutionScope;
import soloMapling.game.runtime.CharacterRuntime;
import soloMapling.game.runtime.ModuleDefinition;
import soloMapling.game.runtime.ModuleFunction;
import soloMapling.game.runtime.RuntimeModule;
import soloMapling.server.MapleVersionManager;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Reads versioned WZ portal topology; native maps and character state remain untouched. */
public final class TravelPlanModule implements RuntimeModule {
    public record Config(int timeoutMs) {
        public Config {
            if (timeoutMs < 1 || timeoutMs > 120_000) throw new IllegalArgumentException("invalid_travel_timeout_ms");
        }
    }
    public static final Config DEFAULT_CONFIG = new Config(30_000);
    public static final ModuleFunction<TravelPlanModule, TravelPlanRequest, TravelPlanResult> PLAN = new ModuleFunction<>(
            "travel.plan", "按当前版本静态地图与门户定义规划地图间的最少过图路线。",
            List.of(new ModuleFunction.Parameter("fromMapId", ModuleFunction.Type.INTEGER, true, "起点地图ID"),
                    new ModuleFunction.Parameter("toMapId", ModuleFunction.Type.INTEGER, true, "终点地图ID")),
            "返回FOUND/NO_ROUTE/UNSUPPORTED及有序路段；每段包含来源地图、出口ID/名称、目标地图和入口名称。",
            "本次只读规划及其原作用域工作真实退出后返回；移动、原生准入与实际到达由执行方确认。",
            (module, arguments) -> new TravelPlanRequest(
                    new BigDecimal(arguments.get("fromMapId").toString()).intValueExact(),
                    new BigDecimal(arguments.get("toMapId").toString()).intValueExact()),
            (module, context, work, input) -> module.plan(input, context, work), TravelPlanResult::toMap);
    public static final ModuleDefinition<Config, TravelPlanModule> DEFINITION = new ModuleDefinition<>(
            "travel", Set.of(), new ExecutionScope.Limits(8, 0, 4, 0),
            config -> new TravelPlanModule(config, DataProviderFactory.getDataProvider(WZFiles.MAP)), List.of(PLAN));

    private final Config config;
    private final DataProvider maps;
    private final Set<Integer> mapIds;
    private final Map<Integer, Definition> definitions = new ConcurrentHashMap<>();

    public TravelPlanModule(Config config, DataProvider maps) {
        this.config = config;
        this.maps = maps;
        var directory = maps.getRoot().getEntry("Map");
        if (!(directory instanceof DataDirectoryEntry root)) throw new IllegalStateException("travel_map_directory_unavailable");
        var ids = new HashSet<Integer>();
        for (int area = 0; area <= 9; area++) {
            var entry = root.getEntry("Map" + area);
            if (entry == null) continue;
            if (!(entry instanceof DataDirectoryEntry region)) throw new IllegalStateException("travel_map_directory_invalid");
            region.getFiles().stream().map(file -> file.getName()).filter(name -> name.matches("[0-9]{9}\\.img"))
                    .map(name -> Integer.parseInt(name.substring(0, 9))).forEach(ids::add);
        }
        mapIds = Set.copyOf(ids);
    }

    public static Config parseConfig(Map<String, Object> values) {
        return new Config(Integer.parseInt(values.getOrDefault("timeout_ms", DEFAULT_CONFIG.timeoutMs()).toString()));
    }

    /** Java consumers and the shared function use the provider's same already-admitted work. */
    public TravelPlanResult plan(TravelPlanRequest request, CharacterRuntime.ModuleContext context,
                                 ExecutionScope.WorkHandle work) {
        long started = System.nanoTime();
        Runnable checkpoint = () -> {
            if (work.cancelRequested() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("travel_plan_cancelled");
            }
            context.scope().requireActive();
            if (System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(config.timeoutMs())) {
                throw new CancellationException("travel_plan_timed_out");
            }
        };
        checkpoint.run();
        if (!available(request.fromMapId()) || !available(request.toMapId())) {
            return new TravelPlanResult(TravelPlanResult.Outcome.UNSUPPORTED, request.fromMapId(), request.toMapId(), List.of());
        }
        definition(request.fromMapId(), checkpoint);
        definition(request.toMapId(), checkpoint);
        if (request.fromMapId() == request.toMapId()) {
            return new TravelPlanResult(TravelPlanResult.Outcome.FOUND, request.fromMapId(), request.toMapId(), List.of());
        }
        var route = route(request, false, checkpoint);
        if (route != null) return new TravelPlanResult(TravelPlanResult.Outcome.FOUND,
                request.fromMapId(), request.toMapId(), route);
        // Only explicit static targets are inspected; a script's unknown destination is never guessed.
        boolean requiresUnsupportedPortal = route(request, true, checkpoint) != null;
        return new TravelPlanResult(requiresUnsupportedPortal ? TravelPlanResult.Outcome.UNSUPPORTED
                : TravelPlanResult.Outcome.NO_ROUTE, request.fromMapId(), request.toMapId(), List.of());
    }

    private List<TravelPlanResult.Leg> route(TravelPlanRequest request, boolean includeUnsupported, Runnable checkpoint) {
        var previous = new HashMap<Integer, TravelPlanResult.Leg>();
        var visited = new HashSet<Integer>();
        var frontier = new ArrayDeque<Integer>();
        visited.add(request.fromMapId());
        frontier.add(request.fromMapId());
        while (!frontier.isEmpty()) {
            checkpoint.run();
            int current = frontier.removeFirst();
            for (var portal : definition(current, checkpoint).portals()) {
                checkpoint.run();
                int target = portal.toMapId();
                if (target == current || !available(target) || visited.contains(target)) continue;
                if (!includeUnsupported && (portal.type() != Portal.MAP_PORTAL || portal.script() != null
                        || !definition(target, checkpoint).entries().contains(portal.entryName()))) continue;
                visited.add(target);
                previous.put(target, new TravelPlanResult.Leg(current, portal.id(), portal.name(), target, portal.entryName()));
                if (target == request.toMapId()) {
                    var result = new ArrayList<TravelPlanResult.Leg>();
                    for (int at = target; at != request.fromMapId();) {
                        var leg = previous.get(at);
                        result.add(leg);
                        at = leg.fromMapId();
                    }
                    Collections.reverse(result);
                    return List.copyOf(result);
                }
                frontier.addLast(target);
            }
        }
        return null;
    }

    private boolean available(int mapId) {
        return mapIds.contains(mapId) && MapleVersionManager.isPortalinCurrentVersion(mapId);
    }

    private Definition definition(int mapId, Runnable checkpoint) {
        checkpoint.run();
        var result = definitions.computeIfAbsent(mapId, id -> {
            Data data = maps.getData(path(id));
            if (data == null) throw new IllegalStateException("travel_map_data_unavailable");
            String link = DataTool.getString("info/link", data, "");
            if (!link.isEmpty()) {
                data = maps.getData(path(Integer.parseInt(link)));
                if (data == null) throw new IllegalStateException("travel_map_link_unavailable");
            }
            var portalData = data.getChildByPath("portal");
            var portals = new ArrayList<PortalEdge>();
            var entries = new HashSet<String>();
            var factory = new PortalFactory();
            if (portalData != null) for (Data node : portalData) {
                checkpoint.run();
                var portal = factory.makePortal(DataTool.getInt("pt", node), node);
                entries.add(portal.getName());
                portals.add(new PortalEdge(portal.getId(), portal.getName(), portal.getTargetMapId(),
                        portal.getTarget(), portal.getType(), portal.getScriptName()));
            }
            portals.sort(Comparator.comparingInt(PortalEdge::toMapId).thenComparingInt(PortalEdge::id));
            return new Definition(List.copyOf(portals), Set.copyOf(entries));
        });
        checkpoint.run();
        return result;
    }

    private static String path(int mapId) {
        return "Map/Map" + mapId / 100_000_000 + "/" + String.format("%09d", mapId) + ".img";
    }

    private record PortalEdge(int id, String name, int toMapId, String entryName, int type, String script) { }
    private record Definition(List<PortalEdge> portals, Set<String> entries) { }
}
