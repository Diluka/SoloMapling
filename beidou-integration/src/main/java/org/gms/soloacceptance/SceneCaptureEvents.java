package org.gms.soloacceptance;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.packet.Packet;
import org.gms.net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import org.gms.server.life.Monster;
import org.gms.server.maps.AbstractAnimatedMapObject;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapleMap;
import org.gms.soloacceptance.actor.AcceptanceClient;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Read-only native callbacks, retained only while a capture subscribes to the exact loaded map. */
public final class SceneCaptureEvents {
    private static final int CAPACITY = 4096;
    private static final Map<MapleMap, Journal> JOURNALS = new IdentityHashMap<>();
    private static final Map<MapleMap, Boolean> DISPOSED = new WeakHashMap<>();
    private static final List<String> SOURCES = List.of(
            "native.monster.controller_assignment", "native.monster.hp_accounting", "native.monster.damage_applied",
            "native.character.hp_change_action", "native.animated.stance_assignment",
            "native.map.object_add", "native.map.object_remove", "native.map.player_membership",
            "native.attack.parse_damage", "native.attack.apply_attack", "native.damage.handler",
            "server_output.map_broadcast", "server_output.client_send", "native.map.lifecycle");

    private SceneCaptureEvents() { }

    public static Subscription open(MapleMap map) {
        synchronized (JOURNALS) {
            var journal = JOURNALS.computeIfAbsent(map, ignored -> new Journal());
            var subscription = new Subscription(map, journal);
            subscription.disposed = DISPOSED.containsKey(map);
            journal.subscriptions.add(subscription);
            return subscription;
        }
    }

    public static Map<String, Object> coverage() {
        return Map.of("state", "partial", "time_type", "observed", "ordering", "source_observation_order",
                "capacity_per_subscription", CAPACITY, "sources", SOURCES,
                "output_paths", List.of("native_client_netty_submission_inside_announcer_lock",
                        "acceptance_and_bot_scene_dispatch_without_socket"),
                "recipient_ordering", "native_transport_submission_order_for_one_client_and_capture_journal",
                "message_projection", SceneOutputPackets.coverage(),
                "not_covered", List.of("direct_mutations_without_the_listed_native_callbacks",
                        "client_network_completion_receipt_or_playback",
                        "packets_outside_the_declared_scene_opcode_set",
                        "headless_transport_order_or_acceptance_packet_buffer_order",
                        "events_before_subscription_or_after_unsubscription",
                        "outputs_without_a_bound_native_character_or_exact_scene"));
    }

    public static boolean interested(MapleMap map) {
        synchronized (JOURNALS) { return JOURNALS.containsKey(map); }
    }

    public static boolean isDisposed(MapleMap map) {
        synchronized (JOURNALS) { return DISPOSED.containsKey(map); }
    }

    public static void controller(Monster monster, Character previous, Character next) {
        if (previous == next) return;
        capture(monster.getMap(), "native.monster.controller_assignment", "controller_changed", () -> Map.of(
                "monster_oid", monster.getObjectId(), "monster_id", monster.getId(),
                "controller_before", character(previous), "controller_after", character(next)));
    }

    public static void monsterHp(Monster monster, int hpBefore, int requestedDelta, Integer accounted) {
        if (accounted == null) return;
        capture(monster.getMap(), "native.monster.hp_accounting", "monster_hp_accounted", () -> Map.of(
                "monster_oid", monster.getObjectId(), "monster_id", monster.getId(),
                "hp_before", hpBefore, "hp_after_observed", monster.getHp(),
                "requested_delta", requestedDelta, "accounted_amount", accounted,
                "alive_after_observed", monster.isAlive()));
    }

    public static void characterHp(Character actor, int oldHp) {
        capture(actor.getMap(), "native.character.hp_change_action", "character_hp_change_action", () -> {
            var values = new LinkedHashMap<String, Object>();
            values.put("character_id", actor.getId());
            values.put("hp_before_known", oldHp >= 0);
            if (oldHp >= 0) values.put("hp_before", oldHp);
            values.put("hp_after_observed", actor.getHp());
            values.put("mp_after_observed", actor.getMp());
            values.put("alive_after_observed", actor.isAlive());
            values.put("stance_after_observed", actor.getStance());
            return Map.copyOf(values);
        });
    }

    public static void monsterDamaged(Monster monster, Character attacker, int accountedDamage) {
        capture(monster.getMap(), "native.monster.damage_applied", "monster_damage_applied", () -> Map.of(
                "character_id", attacker.getId(), "monster_oid", monster.getObjectId(), "monster_id", monster.getId(),
                "accounted_damage", accountedDamage, "hp_after_observed", monster.getHp(),
                "alive_after_observed", monster.isAlive(), "skill_source", "separate_native_attack_input_event"));
    }

    public static void stance(AbstractAnimatedMapObject object, int before) {
        MapleMap map;
        if (object instanceof Character actor) map = actor.getMap();
        else if (object instanceof Monster monster) map = monster.getMap();
        else return;
        if (before == object.getStance()) return;
        capture(map, "native.animated.stance_assignment", "stance_changed", () -> Map.of(
                "object_oid", object.getObjectId(), "object_type", object.getType().name(),
                "stance_before", before, "stance_after_observed", object.getStance(),
                "facing_left_after_observed", object.isFacingLeft()));
    }

    public static void added(MapleMap map, MapObject object, String source) {
        capture(map, source, "map_object_added", () -> object(object));
    }

    public static void removed(MapleMap map, MapObject object, int requestedOid) {
        capture(map, "native.map.object_remove", "map_object_remove_returned", () -> {
            var values = new LinkedHashMap<String, Object>();
            values.put("requested_oid", requestedOid);
            values.put("object_observed_before", object == null ? Map.of("available", false) : object(object));
            values.put("present_after_observed", map.getMapObject(requestedOid) != null);
            return Map.copyOf(values);
        });
    }

    public static void player(MapleMap map, Character actor, boolean entered) {
        capture(map, "native.map.player_membership", entered ? "player_enter_returned" : "player_leave_returned",
                () -> Map.of("character_id", actor.getId(), "object_oid", actor.getObjectId(),
                        "client_source", source(actor)));
    }

    public static void disposed(MapleMap map) {
        synchronized (JOURNALS) {
            DISPOSED.put(map, true);
            var journal = JOURNALS.get(map);
            if (journal == null) return;
            journal.subscriptions.forEach(subscription -> subscription.disposed = true);
        }
        capture(map, "native.map.lifecycle", "map_dispose_returned", () -> Map.of("disposed", true));
    }

    public static void attackParsed(Character actor, AttackInfo attack) {
        capture(actor.getMap(), "native.attack.parse_damage", "attack_input_parsed", () -> attack(actor, attack));
    }

    public static ActionProbe action(Character actor, String source, String kind) {
        var map = actor.getMap();
        if (!interested(map)) return null;
        ActionProbe probe;
        try { probe = new ActionProbe(map, actor, source, kind, actor.getHp(), actor.getMp(), actor.getStance()); }
        catch (RuntimeException failure) {
            capture(map, source, "probe_read_failed", () -> Map.of("failure_type", failure.getClass().getSimpleName()));
            return null;
        }
        capture(map, source, kind + "_entered", () -> Map.of("character_id", actor.getId(),
                "hp_before", probe.hpBefore(), "mp_before", probe.mpBefore(), "stance_before", probe.stanceBefore()));
        return probe;
    }

    public static void attackReturned(ActionProbe probe, AttackInfo attack) {
        if (probe == null) return;
        capture(probe.map(), probe.source(), "attack_apply_returned", () -> {
            var values = new LinkedHashMap<>(attack(probe.actor(), attack));
            values.put("hp_before", probe.hpBefore());
            values.put("mp_before", probe.mpBefore());
            values.put("hp_after_observed", probe.actor().getHp());
            values.put("mp_after_observed", probe.actor().getMp());
            values.put("stance_before", probe.stanceBefore());
            values.put("stance_after_observed", probe.actor().getStance());
            values.put("completion", "native_method_returned_not_damage_confirmation");
            return Map.copyOf(values);
        });
    }

    public static void actionReturned(ActionProbe probe) {
        if (probe == null) return;
        capture(probe.map(), probe.source(), probe.kind() + "_returned", () -> Map.of(
                "character_id", probe.actor().getId(), "hp_before", probe.hpBefore(), "mp_before", probe.mpBefore(),
                "hp_after_observed", probe.actor().getHp(), "mp_after_observed", probe.actor().getMp(),
                "stance_before", probe.stanceBefore(), "stance_after_observed", probe.actor().getStance(),
                "completion", "native_method_returned"));
    }

    public static void actionFailed(ActionProbe probe) {
        if (probe == null) return;
        capture(probe.map(), probe.source(), probe.kind() + "_threw", () -> Map.of(
                "character_id", probe.actor().getId(), "completion", "native_method_exited_exceptionally"));
    }

    public static void broadcast(MapleMap map, Character excluded, Packet packet, double range, java.awt.Point from) {
        capture(map, "server_output.map_broadcast", "map_broadcast_entered", () -> {
            byte[] bytes = packet.getBytes();
            var values = new LinkedHashMap<String, Object>();
            values.put("byte_count", bytes.length);
            values.put("opcode_available", bytes.length >= 2);
            if (bytes.length >= 2) values.put("opcode", (bytes[0] & 255) | (bytes[1] & 255) << 8);
            values.put("excluded_character", character(excluded));
            values.put("ranged", Double.isFinite(range));
            if (from != null) values.put("ranged_from", Map.of("x", from.x, "y", from.y));
            values.put("evidence", "server_broadcast_path_entered_not_client_receipt");
            return Map.copyOf(values);
        });
    }

    /** Called at an existing transport boundary, after native recipient filtering has finished. */
    public static void output(Client client, Packet packet, boolean nativeTransport, MapleMap initializationTarget) {
        Character actor = client.getPlayer();
        if (actor == null) return;
        var map = initializationTarget == null ? actor.getMap() : initializationTarget;
        capture(map, "server_output.client_send", "scene_packet_output", () -> {
            var message = SceneOutputPackets.decode(packet);
            if (message == null) return null;
            var values = new LinkedHashMap<String, Object>(message);
            synchronized (JOURNALS) {
                var journal = JOURNALS.get(map);
                if (journal == null) return null;
                var recipient = journal.recipients.computeIfAbsent(client,
                        ignored -> new Recipient("recipient-" + (journal.recipients.size() + 1)));
                values.put("recipient", Map.of("character_id", actor.getId(),
                        "object_oid", actor.getObjectId(), "client_source", source(client),
                        "transport_ref", recipient.id));
                values.put("recipient_seq", ++recipient.sequence);
            }
            values.put("recipient_sequence_scope", "declared_scene_messages_in_this_capture_journal");
            values.put("scene_association", initializationTarget == null ? "recipient_current_native_map_at_send"
                    : "exact_native_warp_destination_for_this_packet");
            values.put("send_boundary", nativeTransport ? "native_client_netty_write_and_flush_returned"
                    : "headless_scene_dispatch_returned");
            values.put("recipient_ordering", nativeTransport ? "native_announcer_lock_submission_order"
                    : "callback_observation_order_not_transport_or_packet_buffer_order");
            values.put("network_completion", nativeTransport ? "unobserved" : "no_socket");
            values.put("client_receipt_or_playback_verified", false);
            return Map.copyOf(values);
        });
    }

    private static Map<String, Object> attack(Character actor, AttackInfo attack) {
        return Map.of("character_id", actor.getId(), "skill_id", attack.skill, "skill_level", attack.skilllevel,
                "input_stance", attack.stance, "input_direction", attack.direction, "input_speed", attack.speed,
                "input_display", attack.display, "target_oids", List.copyOf(attack.allDamage.keySet()),
                "ranged", attack.ranged, "magic", attack.magic);
    }

    private static Map<String, Object> object(MapObject object) {
        var position = object.getPosition();
        return Map.of("object_oid", object.getObjectId(), "object_type", object.getType().name(),
                "position", Map.of("x", position.x, "y", position.y), "lifecycle_identity", "unknown");
    }

    private static Map<String, Object> character(Character actor) {
        return actor == null ? Map.of("available", false)
                : Map.of("available", true, "character_id", actor.getId(), "client_source", source(actor));
    }

    private static String source(Character actor) {
        return source(actor.getClient());
    }

    private static String source(Client client) {
        if (client instanceof AcceptanceClient) return "acceptance_client";
        return client instanceof BotClient ? "bot_client" : "native_client";
    }

    /** Projection failures are capture coverage failures; native gameplay keeps its original result. */
    private static void capture(MapleMap map, String source, String kind, Supplier<Map<String, Object>> projection) {
        if (!interested(map)) return;
        Map<String, Object> values;
        boolean readFailed = kind.equals("probe_read_failed");
        try { values = projection.get(); }
        catch (RuntimeException failure) {
            values = Map.of("failure_type", failure.getClass().getSimpleName());
            kind = "probe_read_failed";
            readFailed = true;
        }
        if (values == null) return;
        String observedAt = Instant.now().toString();
        long observedNanos = System.nanoTime();
        synchronized (JOURNALS) {
            var journal = JOURNALS.get(map);
            if (journal == null) return;
            long sourceSeq = journal.sourceSequences.merge(source, 1L, Long::sum);
            var event = Map.<String, Object>of("source", source, "source_seq", sourceSeq, "kind", kind,
                    "category", source.equals("server_output.client_send") && !readFailed ? "output" : "diagnostic",
                    "observed_at", observedAt, "observed_monotonic_ns", observedNanos, "time_type", "observed",
                    "values", values);
            for (var subscription : journal.subscriptions) {
                subscription.total++;
                if (readFailed) subscription.readFailures++;
                if (subscription.events.size() == CAPACITY) {
                    subscription.events.removeFirst();
                    subscription.dropped++;
                }
                subscription.events.addLast(event);
            }
        }
    }

    public record ActionProbe(MapleMap map, Character actor, String source, String kind,
                              int hpBefore, int mpBefore, int stanceBefore) { }

    private static final class Journal {
        private final Set<Subscription> subscriptions = new LinkedHashSet<>();
        private final Map<String, Long> sourceSequences = new LinkedHashMap<>();
        private final Map<Client, Recipient> recipients = new IdentityHashMap<>();
    }

    private static final class Recipient {
        private final String id;
        private long sequence;

        private Recipient(String id) { this.id = id; }
    }

    public static final class Subscription implements AutoCloseable {
        private MapleMap map;
        private Journal journal;
        private final ArrayDeque<Map<String, Object>> events = new ArrayDeque<>();
        private long total, dropped, readFailures;
        private boolean disposed;

        private Subscription(MapleMap map, Journal journal) { this.map = map; this.journal = journal; }

        public boolean sceneDisposed() {
            synchronized (JOURNALS) { return disposed; }
        }

        public Map<String, Object> drain() {
            synchronized (JOURNALS) {
                var result = Map.<String, Object>of("items", List.copyOf(events), "coverage", coverage(),
                        "total_since_last", total, "dropped", dropped, "gap", dropped != 0,
                        "probe_read_failures", readFailures, "subscription_closed", map == null);
                events.clear();
                total = dropped = readFailures = 0;
                return result;
            }
        }

        @Override public void close() {
            synchronized (JOURNALS) {
                if (map == null) return;
                journal.subscriptions.remove(this);
                if (journal.subscriptions.isEmpty()) JOURNALS.remove(map, journal);
                map = null;
                journal = null;
            }
        }
    }
}
