package org.gms.soloscripting;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.scripting.AbstractScriptManager;
import org.gms.scripting.npc.NPCConversationManager;
import org.gms.scripting.npc.NPCScriptManager;
import org.gms.scripting.quest.QuestActionManager;
import org.gms.scripting.quest.QuestScriptManager;
import org.gms.util.ThreadLocalUtil;
import soloMapling.game.script.nativeenv.NativeScriptAdapter;
import soloMapling.game.script.nativeenv.NativeScriptRequest;
import soloMapling.game.script.nativeenv.NativeScriptSession;

import javax.script.ScriptEngine;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native dialogue managers keep their one real CM/QM, cached engine and callback state. */
public final class NativeDialogueScripts implements NativeScriptAdapter {
    private final Client client;
    private final Character character;
    private final String kind;

    public NativeDialogueScripts(Client client, String kind) {
        if (client == null || !List.of("npc", "quest").contains(kind)) {
            throw new IllegalArgumentException("invalid_native_dialogue_binding");
        }
        this.client = client;
        this.character = client.getPlayer();
        if (character == null) throw new IllegalStateException("native_character_unavailable");
        this.kind = kind;
    }

    @Override
    public void validate(NativeScriptSession session) {
        requireRole();
        // Before start there is no attachment yet. Later source evaluation must keep the same real dialogue.
        if (session.attachment() instanceof Dialogue binding && binding.conversation != null) {
            requireConversation(binding);
        }
    }

    @Override
    public AutoCloseable enter(NativeScriptSession session) {
        Client previous = ThreadLocalUtil.getCurrentClient();
        ThreadLocalUtil.setCurrentClient(client);
        return () -> {
            if (previous == null) ThreadLocalUtil.removeCurrentClient();
            else ThreadLocalUtil.setCurrentClient(previous);
        };
    }

    @Override
    public Object start(NativeScriptSession session, NativeScriptRequest request) {
        requireRole();
        if (!kind.equals(request.kind())) throw new IllegalArgumentException("native_script_kind_mismatch");
        if (client.getCM() != null || client.getQM() != null) {
            throw new IllegalStateException("native_dialogue_already_active");
        }
        if (request.entry() != null || !request.args().isEmpty()) {
            throw new IllegalArgumentException("invalid_native_dialogue_start");
        }
        Map<String, Object> options = request.options();
        int npc = integer(options.get("npc_id"), 1, Integer.MAX_VALUE, "invalid_native_npc_id");
        Dialogue binding;
        if ("npc".equals(kind)) {
            requireKeys(options, Set.of("npc_id", "script_name"));
            String script = optionalScriptName(options.get("script_name"));
            binding = new Dialogue(client, character, kind, npc, 0, "start", script);
            session.attachment(binding);
            if (!NPCScriptManager.getInstance().start(client, npc, script, character)) {
                throw new IllegalStateException("native_script_start_failed");
            }
        } else {
            requireKeys(options, Set.of("npc_id", "quest_id", "phase"));
            int quest = integer(options.get("quest_id"), 1, Short.MAX_VALUE, "invalid_native_quest_id");
            String phase = options.getOrDefault("phase", "start") instanceof String value ? value : "";
            if (!List.of("start", "end").contains(phase)) throw new IllegalArgumentException("invalid_native_quest_phase");
            binding = new Dialogue(client, character, kind, npc, quest, phase, null);
            session.attachment(binding);
            if ("start".equals(phase)) QuestScriptManager.getInstance().start(client, (short) quest, npc);
            else QuestScriptManager.getInstance().end(client, (short) quest, npc);
        }
        // The before-put advice captures the manager-created exact binding, including scripts which dispose immediately.
        if (binding.conversation == null || session.engine() == null) {
            throw new IllegalStateException("native_script_start_unavailable");
        }
        return describe(session);
    }

    @Override
    public Object invoke(NativeScriptSession session, NativeScriptRequest request) {
        Dialogue binding = binding(session);
        requireRole();
        requireConversation(binding);
        if (!request.options().isEmpty() || !"reply".equals(request.entry()) || request.args().size() != 3) {
            throw new IllegalArgumentException("invalid_native_dialogue_reply");
        }
        byte mode = (byte) integer(request.args().get(0), -1, 1, "invalid_native_dialogue_mode");
        byte type = (byte) integer(request.args().get(1), 0, Byte.MAX_VALUE, "invalid_native_dialogue_type");
        int selection = integer(request.args().get(2), -1, Integer.MAX_VALUE, "invalid_native_dialogue_selection");
        if ("quest".equals(kind)) {
            QuestActionManager quest = (QuestActionManager) binding.conversation;
            if (quest.isStart()) QuestScriptManager.getInstance().start(client, mode, type, selection);
            else QuestScriptManager.getInstance().end(client, mode, type, selection);
        } else if (binding.conversation.getNextLevelContext().getLevelType() == null) {
            NPCScriptManager.getInstance().action(client, mode, type, selection);
        } else {
            NPCScriptManager.getInstance().nextLevel(client, mode, type, selection);
        }
        return describe(session);
    }

    @Override
    public void close(NativeScriptSession session) {
        if (session.attachment() == null) {
            // Rejected starts never acquired a native conversation and must not touch an external one.
            session.markNativeDisposed();
            return;
        }
        Dialogue binding = binding(session);
        // A late close may release only its original engine; it must never dispose a replacement dialogue.
        if (client.getPlayer() == binding.character) {
            if (binding.conversation != null && "quest".equals(kind) && client.getQM() == binding.conversation) {
                binding.conversation.dispose();
            } else if (binding.conversation != null && "npc".equals(kind) && client.getCM() == binding.conversation) {
                binding.conversation.dispose();
            }
            if (binding.path != null && client.getScriptEngine("scripts/" + binding.path) == session.engine()) {
                client.removeScriptEngine("scripts/" + binding.path);
            }
        }
        session.markNativeDisposed();
    }

    @Override
    public Map<String, Object> describe(NativeScriptSession session) {
        Dialogue binding = binding(session);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", kind);
        value.put("global", "quest".equals(kind) ? "qm" : "cm");
        value.put("binding_type", "quest".equals(kind) ? QuestActionManager.class.getName() : NPCConversationManager.class.getName());
        value.put("npc_id", binding.npc);
        if (binding.quest > 0) { value.put("quest_id", binding.quest); value.put("phase", binding.phase); }
        value.put("script_path", binding.path);
        value.put("native_dialogue_alive", client.getPlayer() == binding.character
                && ("quest".equals(kind) ? client.getQM() == binding.conversation : client.getCM() == binding.conversation));
        value.put("invocation_semantics", "native_script_manager_entry_not_nearby_player_npc_click");
        return java.util.Collections.unmodifiableMap(value);
    }

    public static Map<String, Object> descriptor(String kind) {
        if (!List.of("npc", "quest").contains(kind)) throw new IllegalArgumentException("invalid_native_script_kind");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", kind);
        value.put("globals", Map.of("quest".equals(kind) ? "qm" : "cm",
                "quest".equals(kind) ? QuestActionManager.class.getName() : NPCConversationManager.class.getName()));
        value.put("operations", List.of("start", "invoke", "close"));
        value.put("start_options", "quest".equals(kind) ? List.of("quest_id", "npc_id", "phase(start|end, default start)") : List.of("npc_id", "script_name(optional)"));
        value.put("reply", Map.of("entry", "reply", "args", List.of("mode(-1..1)", "type(0..127)", "selection(-1..2147483647)"),
                "routing", "current_native_legacy_nextlevel_or_quest_start_end"));
        value.put("source", "optional_code_in_the_same_real_native_engine");
        value.put("context_requirement", "no_existing_cm_or_qm_on_start_exact_original_client_and_binding_on_invoke");
        return Map.copyOf(value);
    }

    /** Captures only the original loader selected by the current managed environment. */
    public static void captureEngine(AbstractScriptManager manager, GraalJSScriptEngine engine, String path) {
        NativeScriptSession session = NativeScriptSession.current();
        if (session == null) return;
        boolean owned = switch (session.kind()) {
            case "npc" -> manager == NPCScriptManager.getInstance();
            case "quest" -> manager == QuestScriptManager.getInstance();
            default -> manager instanceof NativeScriptLoader;
        };
        if (!owned) return;
        session.captureEngine(engine, path);
        if (session.attachment() instanceof Dialogue binding) binding.path = path;
    }

    /** Records the exact real manager-created object before its original start function runs. */
    public static void captureBinding(ScriptEngine engine, String symbol, Object object) {
        NativeScriptSession session = NativeScriptSession.current();
        if (session == null || !(session.attachment() instanceof Dialogue binding)) return;
        if (!(engine instanceof GraalJSScriptEngine graal)) throw new IllegalStateException("native_script_engine_unavailable");
        boolean accepted = "npc".equals(binding.kind) && "cm".equals(symbol)
                && object instanceof NPCConversationManager && !(object instanceof QuestActionManager)
                || "quest".equals(binding.kind) && "qm".equals(symbol) && object instanceof QuestActionManager;
        if (!accepted || ((NPCConversationManager) object).getClient() != binding.client) {
            throw new IllegalStateException("native_script_binding_mismatch");
        }
        binding.conversation = (NPCConversationManager) object;
        String path = binding.path != null ? binding.path : "quest".equals(binding.kind) ? "quest/" + binding.quest + ".js"
                : "npc/" + (binding.script == null ? binding.npc : binding.script) + ".js";
        session.captureEngine(graal, path);
        binding.path = path;
    }

    /** Capture before source loading can fail or loop, even before the native manager puts cm/qm into its engine. */
    public static void captureConversation(NPCConversationManager conversation) {
        NativeScriptSession session = NativeScriptSession.current();
        if (session == null || !(session.attachment() instanceof Dialogue binding)
                || conversation.getClient() != binding.client || conversation.getNpc() != binding.npc) return;
        boolean correctKind = "npc".equals(binding.kind) && !(conversation instanceof QuestActionManager)
                || "quest".equals(binding.kind) && conversation instanceof QuestActionManager quest
                && quest.getQuest() == binding.quest && quest.isStart() == "start".equals(binding.phase);
        if (correctKind && binding.conversation == null) binding.conversation = conversation;
    }

    public static void removedEngine(ScriptEngine exactEngine) {
        if (exactEngine instanceof GraalJSScriptEngine graal) {
            NativeScriptSession session = NativeScriptSession.forEngine(graal);
            if (session != null) session.markNativeDisposed();
        }
    }

    private Dialogue binding(NativeScriptSession session) {
        if (!(session.attachment() instanceof Dialogue binding) || binding.client != client || !binding.kind.equals(kind)) {
            throw new IllegalStateException("native_script_binding_mismatch");
        }
        return binding;
    }

    private void requireRole() {
        if (client.getPlayer() != character || character.getClient() != client) {
            throw new IllegalStateException("native_character_replaced");
        }
    }

    private void requireConversation(Dialogue binding) {
        if (binding.conversation == null || ("quest".equals(kind) ? client.getQM() : client.getCM()) != binding.conversation
                || ("quest".equals(kind) ? client.getCM() : client.getQM()) != null) {
            throw new IllegalStateException("native_dialogue_replaced");
        }
    }

    private static int integer(Object value, int minimum, int maximum, String reason) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.longValue() || number.longValue() < minimum || number.longValue() > maximum) {
            throw new IllegalArgumentException(reason);
        }
        return number.intValue();
    }

    private static String optionalScriptName(Object value) {
        if (value == null) return null;
        if (!(value instanceof String name) || !name.matches("[\\p{L}\\p{N}_ -]{1,128}")) {
            throw new IllegalArgumentException("invalid_native_script_name");
        }
        return name;
    }

    private static void requireKeys(Map<String, Object> options, Set<String> allowed) {
        if (!allowed.containsAll(options.keySet())) throw new IllegalArgumentException("invalid_native_script_options");
    }

    private static final class Dialogue {
        final Client client;
        final Character character;
        final String kind;
        final int npc;
        final int quest;
        final String phase;
        final String script;
        NPCConversationManager conversation;
        String path;
        Dialogue(Client client, Character character, String kind, int npc, int quest, String phase, String script) {
            this.client = client; this.character = character; this.kind = kind; this.npc = npc;
            this.quest = quest; this.phase = phase; this.script = script;
        }
    }
}
