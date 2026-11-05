package org.gms.soloacceptance;

import org.gms.client.BuffStat;
import org.gms.client.Stat;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.Packet;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Value-only projections of the matching BeiDou 1.12 PacketCreator output, never a packet producer. */
final class SceneOutputPackets {
    private static final EnumSet<SendOpcode> OPCODES = EnumSet.of(
            SendOpcode.SET_FIELD, SendOpcode.STAT_CHANGED, SendOpcode.GIVE_BUFF, SendOpcode.CANCEL_BUFF,
            SendOpcode.SPAWN_PLAYER, SendOpcode.REMOVE_PLAYER_FROM_MAP, SendOpcode.MOVE_PLAYER,
            SendOpcode.CLOSE_RANGE_ATTACK, SendOpcode.RANGED_ATTACK, SendOpcode.MAGIC_ATTACK,
            SendOpcode.ENERGY_ATTACK, SendOpcode.SKILL_EFFECT, SendOpcode.CANCEL_SKILL_EFFECT,
            SendOpcode.DAMAGE_PLAYER, SendOpcode.UPDATE_CHAR_LOOK, SendOpcode.SHOW_FOREIGN_EFFECT,
            SendOpcode.GIVE_FOREIGN_BUFF, SendOpcode.CANCEL_FOREIGN_BUFF, SendOpcode.UPDATE_PARTYMEMBER_HP,
            SendOpcode.SPAWN_MONSTER, SendOpcode.KILL_MONSTER, SendOpcode.SPAWN_MONSTER_CONTROL,
            SendOpcode.MOVE_MONSTER, SendOpcode.MOVE_MONSTER_RESPONSE, SendOpcode.APPLY_MONSTER_STATUS,
            SendOpcode.CANCEL_MONSTER_STATUS, SendOpcode.RESET_MONSTER_ANIMATION,
            SendOpcode.DAMAGE_MONSTER, SendOpcode.SHOW_MONSTER_HP,
            SendOpcode.SPAWN_NPC, SendOpcode.REMOVE_NPC, SendOpcode.SPAWN_NPC_REQUEST_CONTROLLER,
            SendOpcode.NPC_ACTION, SendOpcode.DROP_ITEM_FROM_MAPOBJECT, SendOpcode.REMOVE_ITEM_FROM_MAP,
            SendOpcode.SPAWN_PET, SendOpcode.MOVE_PET, SendOpcode.SPAWN_SPECIAL_MAPOBJECT,
            SendOpcode.REMOVE_SPECIAL_MAPOBJECT, SendOpcode.MOVE_SUMMON, SendOpcode.SUMMON_ATTACK,
            SendOpcode.DAMAGE_SUMMON, SendOpcode.SUMMON_SKILL, SendOpcode.SPAWN_DRAGON,
            SendOpcode.MOVE_DRAGON, SendOpcode.REMOVE_DRAGON, SendOpcode.SPAWN_MIST,
            SendOpcode.REMOVE_MIST, SendOpcode.SPAWN_DOOR, SendOpcode.REMOVE_DOOR,
            SendOpcode.REACTOR_SPAWN, SendOpcode.REACTOR_HIT, SendOpcode.REACTOR_DESTROY);

    private SceneOutputPackets() { }

    static Map<String, Object> coverage() {
        return Map.of("protocol_source", "BeiDou_1.12_PacketCreator",
                "captured_opcodes", OPCODES.stream().map(Enum::name).toList(),
                "payload_scope", "complete_allowlisted_packet_including_opcode",
                "field_coverage", "per_message_decode_status_and_unparsed_byte_count",
                "not_captured", List.of("opcodes_outside_allowlist", "malformed_packet_body"));
    }

    static Map<String, Object> decode(Packet packet) {
        byte[] bytes = packet.getBytes();
        if (bytes.length < 2) return Map.of("decode_status", "malformed", "byte_count", bytes.length,
                "reason", "opcode_unavailable");
        var p = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int code = Short.toUnsignedInt(p.getShort());
        var opcode = OPCODES.stream().filter(value -> value.getValue() == code).findFirst().orElse(null);
        if (opcode == null) return null;
        var result = new LinkedHashMap<String, Object>();
        result.put("opcode", code);
        result.put("opcode_name", opcode.name());
        result.put("message_kind", kind(opcode));
        result.put("byte_count", bytes.length);
        var fields = new LinkedHashMap<String, Object>();
        boolean knownLayout = true;
        try {
            switch (opcode) {
                case CLOSE_RANGE_ATTACK, RANGED_ATTACK, MAGIC_ATTACK -> {
                    fields.put("character_id", p.getInt());
                    int counts = Byte.toUnsignedInt(p.get());
                    fields.put("target_count", counts >>> 4);
                    fields.put("hits_per_target", counts & 15);
                    fields.put("attack_marker", Byte.toUnsignedInt(p.get()));
                    int level = Byte.toUnsignedInt(p.get());
                    fields.put("skill_level", level);
                    int skill = level > 0 ? p.getInt() : 0;
                    fields.put("skill_id", skill);
                    fields.put("display", Byte.toUnsignedInt(p.get()));
                    fields.put("direction", Byte.toUnsignedInt(p.get()));
                    fields.put("stance", Byte.toUnsignedInt(p.get()));
                    fields.put("speed", Byte.toUnsignedInt(p.get()));
                    fields.put("attack_tail_marker", Byte.toUnsignedInt(p.get()));
                    fields.put("projectile_item_id", p.getInt());
                    var targets = new ArrayList<Map<String, Object>>();
                    for (int i = 0; i < counts >>> 4; i++) {
                        int oid = p.getInt();
                        int marker = Byte.toUnsignedInt(p.get());
                        int hits = skill == 4211006 ? Byte.toUnsignedInt(p.get()) : counts & 15;
                        var damage = new ArrayList<Integer>();
                        for (int hit = 0; hit < hits; hit++) damage.add(p.getInt());
                        targets.add(Map.of("monster_oid", oid, "target_marker", marker, "damage", damage));
                    }
                    fields.put("targets", targets);
                    if (opcode == SendOpcode.RANGED_ATTACK) fields.put("ranged_tail", p.getInt());
                    if (opcode == SendOpcode.MAGIC_ATTACK && p.remaining() == 4) fields.put("charge", p.getInt());
                }
                case MOVE_PLAYER, MOVE_MONSTER, MOVE_SUMMON, MOVE_DRAGON, MOVE_PET -> {
                    fields.put(opcode == SendOpcode.MOVE_MONSTER ? "monster_oid" : "character_id", p.getInt());
                    if (opcode == SendOpcode.MOVE_PLAYER) fields.put("movement_marker", p.getInt());
                    if (opcode == SendOpcode.MOVE_MONSTER) {
                        fields.put("movement_marker", Byte.toUnsignedInt(p.get()));
                        fields.put("skill_possible", p.get() != 0);
                        fields.put("action", Byte.toUnsignedInt(p.get()));
                        fields.put("skill_id", Byte.toUnsignedInt(p.get()));
                        fields.put("skill_level", Byte.toUnsignedInt(p.get()));
                        fields.put("option", Short.toUnsignedInt(p.getShort()));
                    }
                    if (opcode == SendOpcode.MOVE_SUMMON) fields.put("summon_oid", p.getInt());
                    if (opcode == SendOpcode.MOVE_PET) {
                        fields.put("pet_slot", Byte.toUnsignedInt(p.get()));
                        fields.put("pet_id", p.getInt());
                    } else if (opcode != SendOpcode.MOVE_PLAYER) fields.put("start_position", position(p));
                    knownLayout = movement(p, fields);
                }
                case STAT_CHANGED -> {
                    fields.put("enable_actions", p.get() != 0);
                    int mask = p.getInt();
                    fields.put("update_mask", Integer.toUnsignedLong(mask));
                    var stats = new LinkedHashMap<String, Integer>();
                    for (var stat : Stat.values()) {
                        if (stat == Stat.PET || (mask & stat.getValue()) == 0) continue;
                        // AVAILABLESP can have job-specific tables; the packet alone cannot identify its layout.
                        if (stat == Stat.AVAILABLESP) { knownLayout = false; break; }
                        int flag = stat.getValue();
                        int value = flag == 0x20000 ? p.getShort()
                                : flag == 1 || flag == 0x10 ? Byte.toUnsignedInt(p.get())
                                : flag <= 4 || flag > 0xffff && flag != 0x20000 ? p.getInt()
                                : Short.toUnsignedInt(p.getShort());
                        stats.put(stat.name(), value);
                    }
                    fields.put("changed_stats", stats);
                    fields.put("semantics", "selected_fields_and_new_values");
                }
                case GIVE_BUFF, CANCEL_BUFF, GIVE_FOREIGN_BUFF, CANCEL_FOREIGN_BUFF -> {
                    if (opcode == SendOpcode.GIVE_FOREIGN_BUFF || opcode == SendOpcode.CANCEL_FOREIGN_BUFF)
                        fields.put("character_id", p.getInt());
                    long firstMask = p.getLong();
                    long secondMask = p.getLong();
                    fields.put("first_mask", Long.toUnsignedString(firstMask));
                    fields.put("second_mask", Long.toUnsignedString(secondMask));
                    // Buff/debuff/mount/energy variants share an opcode but have different value layouts.
                    knownLayout = opcode == SendOpcode.CANCEL_FOREIGN_BUFF || opcode == SendOpcode.CANCEL_BUFF;
                    if (opcode == SendOpcode.CANCEL_BUFF) fields.put("cancel_marker", Byte.toUnsignedInt(p.get()));
                    long ordinaryMask = BuffStat.WDEF.getValue() | BuffStat.MDEF.getValue() | BuffStat.MAGIC_GUARD.getValue();
                    boolean ownBuff = opcode == SendOpcode.GIVE_BUFF;
                    if ((ownBuff || opcode == SendOpcode.GIVE_FOREIGN_BUFF) && firstMask == 0 && secondMask != 0
                            && (secondMask & ~ordinaryMask) == 0
                            && p.remaining() == Long.bitCount(secondMask) * (ownBuff ? 10 : 2) + (ownBuff ? 9 : 6)) {
                        var updates = new ArrayList<Map<String, Object>>();
                        for (var stat : List.of(BuffStat.WDEF, BuffStat.MDEF, BuffStat.MAGIC_GUARD)) {
                            if ((secondMask & stat.getValue()) == 0) continue;
                            var update = new LinkedHashMap<String, Object>();
                            update.put("stat", stat.name());
                            update.put("value", (int) p.getShort());
                            if (ownBuff) {
                                update.put("source_id", p.getInt());
                                update.put("duration_ms", p.getInt());
                            }
                            updates.add(Map.copyOf(update));
                        }
                        fields.put("updates", updates);
                        fields.put("buff_tail_marker", p.getInt());
                        if (ownBuff) {
                            fields.put("buff_tail_flag", Byte.toUnsignedInt(p.get()));
                            fields.put("buff_tail_value", p.getInt());
                        } else fields.put("buff_tail_flag", Short.toUnsignedInt(p.getShort()));
                        knownLayout = true;
                    }
                }
                case SET_FIELD -> {
                    fields.put("channel_index", p.getInt());
                    int first = Byte.toUnsignedInt(p.get());
                    int full = Byte.toUnsignedInt(p.get());
                    fields.put("field_marker", first);
                    fields.put("full_character_data", full != 0);
                    if (full != 0) {
                        fields.put("layout", "character_initialization");
                        knownLayout = false;
                    } else {
                        fields.put("layout", "map_transfer");
                        fields.put("transfer_marker", Short.toUnsignedInt(p.getShort()));
                        fields.put("transfer_flag", Byte.toUnsignedInt(p.get()));
                        fields.put("map_id", p.getInt());
                        fields.put("spawn_portal_id", Byte.toUnsignedInt(p.get()));
                        fields.put("hp", Short.toUnsignedInt(p.getShort()));
                        boolean chasing = p.get() != 0;
                        fields.put("chasing", chasing);
                        if (chasing) fields.put("chasing_position", Map.of("x", p.getInt(), "y", p.getInt()));
                        fields.put("server_time", Long.toUnsignedString(p.getLong()));
                    }
                }
                case SPAWN_PLAYER -> {
                    fields.put("character_id", p.getInt());
                    fields.put("level", Byte.toUnsignedInt(p.get()));
                    knownLayout = false;
                }
                case SPAWN_MONSTER, SPAWN_MONSTER_CONTROL -> {
                    int control = opcode == SendOpcode.SPAWN_MONSTER_CONTROL ? Byte.toUnsignedInt(p.get()) : -1;
                    if (control >= 0) fields.put("control_mode", control);
                    fields.put("monster_oid", p.getInt());
                    if (control != 0) {
                        fields.put("controller_marker", Byte.toUnsignedInt(p.get()));
                        fields.put("monster_template_id", p.getInt());
                        knownLayout = false;
                    }
                }
                case KILL_MONSTER -> {
                    fields.put("monster_oid", p.getInt());
                    fields.put("animation", Byte.toUnsignedInt(p.get()));
                    fields.put("animation_repeat", Byte.toUnsignedInt(p.get()));
                }
                case DAMAGE_MONSTER -> {
                    fields.put("monster_oid", p.getInt());
                    fields.put("damage_marker", Byte.toUnsignedInt(p.get()));
                    fields.put("damage", p.getInt());
                    fields.put("current_hp", p.getInt());
                    fields.put("max_hp", p.getInt());
                }
                case DAMAGE_PLAYER -> {
                    fields.put("character_id", p.getInt());
                    int source = p.get();
                    fields.put("damage_source", source);
                    if (source == -3) fields.put("source_marker", p.getInt());
                    fields.put("damage", p.getInt());
                    if (source != -4) {
                        fields.put("monster_template_id", p.getInt());
                        fields.put("direction", Byte.toUnsignedInt(p.get()));
                        knownLayout = false;
                    } else fields.put("damage_repeat", p.getInt());
                }
                case MOVE_MONSTER_RESPONSE -> {
                    fields.put("monster_oid", p.getInt());
                    fields.put("move_id", Short.toUnsignedInt(p.getShort()));
                    fields.put("use_skills", p.get() != 0);
                    fields.put("current_mp", Short.toUnsignedInt(p.getShort()));
                    fields.put("skill_id", Byte.toUnsignedInt(p.get()));
                    fields.put("skill_level", Byte.toUnsignedInt(p.get()));
                }
                case DROP_ITEM_FROM_MAPOBJECT -> {
                    fields.put("animation", Byte.toUnsignedInt(p.get()));
                    fields.put("item_oid", p.getInt());
                    fields.put("meso", p.get() != 0);
                    fields.put("item_id_or_meso", p.getInt());
                    fields.put("owner_id", p.getInt());
                    fields.put("drop_type", Byte.toUnsignedInt(p.get()));
                    fields.put("position", position(p));
                    fields.put("dropper_oid", p.getInt());
                    knownLayout = false;
                }
                case REMOVE_ITEM_FROM_MAP -> {
                    int animation = Byte.toUnsignedInt(p.get());
                    fields.put("animation", animation);
                    fields.put("item_oid", p.getInt());
                    if (animation >= 2) fields.put("character_id", p.getInt());
                    if (p.remaining() == 1) fields.put("pet_slot", Byte.toUnsignedInt(p.get()));
                }
                case SKILL_EFFECT, CANCEL_SKILL_EFFECT -> {
                    fields.put("character_id", p.getInt());
                    fields.put("skill_id", p.getInt());
                    if (opcode == SendOpcode.SKILL_EFFECT) {
                        fields.put("skill_level", Byte.toUnsignedInt(p.get()));
                        fields.put("flags", Byte.toUnsignedInt(p.get()));
                        fields.put("speed", Byte.toUnsignedInt(p.get()));
                        fields.put("direction", Byte.toUnsignedInt(p.get()));
                    }
                }
                case REMOVE_PLAYER_FROM_MAP, REMOVE_DRAGON -> fields.put("character_id", p.getInt());
                case REMOVE_NPC, REMOVE_MIST -> fields.put("object_oid", p.getInt());
                case SPAWN_NPC, SPAWN_NPC_REQUEST_CONTROLLER -> {
                    int control = opcode == SendOpcode.SPAWN_NPC_REQUEST_CONTROLLER ? Byte.toUnsignedInt(p.get()) : -1;
                    if (control >= 0) fields.put("control_mode", control);
                    fields.put("npc_oid", p.getInt());
                    if (control != 0) {
                        fields.put("npc_template_id", p.getInt());
                        fields.put("position", position(p));
                        fields.put("facing", Byte.toUnsignedInt(p.get()));
                        fields.put("foothold_id", Short.toUnsignedInt(p.getShort()));
                        fields.put("range_start_x", (int) p.getShort());
                        fields.put("range_end_x", (int) p.getShort());
                        fields.put("spawn_flag", Byte.toUnsignedInt(p.get()));
                    }
                }
                case REACTOR_SPAWN, REACTOR_HIT, REACTOR_DESTROY -> {
                    fields.put("reactor_oid", p.getInt());
                    if (opcode == SendOpcode.REACTOR_SPAWN) fields.put("reactor_template_id", p.getInt());
                    fields.put("state", Byte.toUnsignedInt(p.get()));
                    fields.put("position", position(p));
                    knownLayout = false;
                }
                case SPAWN_SPECIAL_MAPOBJECT, REMOVE_SPECIAL_MAPOBJECT, SUMMON_ATTACK, DAMAGE_SUMMON -> {
                    fields.put("character_id", p.getInt());
                    fields.put("summon_oid", p.getInt());
                    knownLayout = false;
                }
                case SUMMON_SKILL -> {
                    fields.put("character_id", p.getInt());
                    fields.put("skill_id", p.getInt());
                    fields.put("stance", Byte.toUnsignedInt(p.get()));
                }
                case SPAWN_PET -> {
                    fields.put("character_id", p.getInt());
                    fields.put("pet_slot", Byte.toUnsignedInt(p.get()));
                    knownLayout = false;
                }
                case SPAWN_DRAGON, UPDATE_CHAR_LOOK, SHOW_FOREIGN_EFFECT -> {
                    fields.put("character_id", p.getInt());
                    knownLayout = false;
                }
                case SPAWN_MIST -> {
                    fields.put("mist_oid", p.getInt());
                    fields.put("mist_type", p.getInt());
                    fields.put("owner_id", p.getInt());
                    fields.put("skill_id", p.getInt());
                    fields.put("skill_level", Byte.toUnsignedInt(p.get()));
                    fields.put("delay_ms", Short.toUnsignedInt(p.getShort()));
                    fields.put("bounds", Map.of("left", p.getInt(), "top", p.getInt(),
                            "right", p.getInt(), "bottom", p.getInt()));
                    fields.put("mist_marker", p.getInt());
                }
                case UPDATE_PARTYMEMBER_HP -> {
                    fields.put("character_id", p.getInt());
                    fields.put("hp", p.getInt());
                    fields.put("max_hp", p.getInt());
                }
                case SHOW_MONSTER_HP -> {
                    fields.put("monster_oid", p.getInt());
                    fields.put("hp_percent", Byte.toUnsignedInt(p.get()));
                }
                case APPLY_MONSTER_STATUS, CANCEL_MONSTER_STATUS -> {
                    fields.put("monster_oid", p.getInt());
                    fields.put("reserved_mask", Long.toUnsignedString(p.getLong()));
                    fields.put("first_status_mask", Integer.toUnsignedLong(p.getInt()));
                    fields.put("second_status_mask", Integer.toUnsignedLong(p.getInt()));
                    knownLayout = false;
                }
                default -> knownLayout = false;
            }
        } catch (BufferUnderflowException malformed) {
            result.put("decode_status", "malformed");
            result.put("reason", "truncated_known_packet_layout");
            return Map.copyOf(result);
        }
        result.put("decode_status", knownLayout && !p.hasRemaining() ? "complete" : "partial");
        result.put("protocol_fields", Map.copyOf(fields));
        result.put("decoded_byte_count", p.position());
        result.put("unparsed_byte_count", p.remaining());
        result.put("payload_base64", Base64.getEncoder().encodeToString(bytes));
        result.put("payload_scope", "full_packet_including_opcode");
        return Map.copyOf(result);
    }

    private static String kind(SendOpcode opcode) {
        return switch (opcode) {
            case SET_FIELD -> "scene_initialization";
            case CLOSE_RANGE_ATTACK, RANGED_ATTACK, MAGIC_ATTACK, ENERGY_ATTACK,
                    SUMMON_ATTACK, SKILL_EFFECT, CANCEL_SKILL_EFFECT -> "attack_or_skill";
            case MOVE_PLAYER, MOVE_MONSTER, MOVE_SUMMON, MOVE_PET, MOVE_DRAGON -> "movement";
            case DAMAGE_PLAYER, DAMAGE_MONSTER, DAMAGE_SUMMON -> "damage";
            case STAT_CHANGED, UPDATE_PARTYMEMBER_HP, SHOW_MONSTER_HP -> "attribute_update";
            case GIVE_BUFF, CANCEL_BUFF, GIVE_FOREIGN_BUFF, CANCEL_FOREIGN_BUFF,
                    APPLY_MONSTER_STATUS, CANCEL_MONSTER_STATUS -> "effect_update";
            default -> "scene_object_or_presentation";
        };
    }

    private static Map<String, Integer> position(ByteBuffer p) {
        return Map.of("x", (int) p.getShort(), "y", (int) p.getShort());
    }

    private static boolean movement(ByteBuffer p, Map<String, Object> fields) {
        int count = Byte.toUnsignedInt(p.get());
        fields.put("fragment_count", count);
        var fragments = new ArrayList<Map<String, Object>>();
        fields.put("fragments", fragments);
        for (int i = 0; i < count; i++) {
            var fragment = new LinkedHashMap<String, Object>();
            int command = Byte.toUnsignedInt(p.get());
            fragment.put("command", command);
            fragments.add(fragment);
            switch (command) {
                case 0, 5, 17, 15 -> {
                    fragment.put("position", position(p));
                    fragment.put("pixels_per_second", position(p));
                    fragment.put("foothold_id", Short.toUnsignedInt(p.getShort()));
                    if (command == 15) fragment.put("origin_foothold_id", Short.toUnsignedInt(p.getShort()));
                    fragment.put("stance", Byte.toUnsignedInt(p.get()));
                    fragment.put("duration_ms", Short.toUnsignedInt(p.getShort()));
                }
                case 1, 2, 6, 12, 13, 16, 18, 19, 20, 22 -> {
                    fragment.put("relative_position", position(p));
                    fragment.put("stance", Byte.toUnsignedInt(p.get()));
                    fragment.put("duration_ms", Short.toUnsignedInt(p.getShort()));
                }
                case 3, 4, 7, 8, 9, 11 -> {
                    fragment.put("position", position(p));
                    fragment.put("pixels_per_second", position(p));
                    fragment.put("stance", Byte.toUnsignedInt(p.get()));
                }
                case 10 -> fragment.put("equipment_marker", Byte.toUnsignedInt(p.get()));
                case 14, 21 -> {
                    int length = command == 14 ? 9 : 3;
                    byte[] opaque = new byte[length];
                    p.get(opaque);
                    fragment.put("body_base64", Base64.getEncoder().encodeToString(opaque));
                    fragment.put("field_coverage", "native_parser_skips_body");
                }
                default -> {
                    fragment.put("field_coverage", "unsupported_movement_command");
                    return false;
                }
            }
        }
        return true;
    }
}
