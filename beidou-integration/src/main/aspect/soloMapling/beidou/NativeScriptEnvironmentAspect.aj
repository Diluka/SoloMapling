package soloMapling.beidou;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.gms.client.Client;
import org.gms.scripting.AbstractScriptManager;
import org.gms.scripting.npc.NPCScriptManager;
import org.gms.scripting.npc.NPCConversationManager;
import org.gms.scripting.quest.QuestScriptManager;
import org.gms.scripting.quest.QuestActionManager;
import org.gms.soloscripting.NativeDialogueScripts;
import soloMapling.game.script.nativeenv.NativeScriptSession;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.io.Reader;

/** Only runtime-owned native engines are attached; ordinary server scripts keep their original path. */
public aspect NativeScriptEnvironmentAspect {
    after(NPCConversationManager conversation) returning:
            execution(NPCConversationManager.new(Client, int, int, String, boolean)) && this(conversation) {
        NativeDialogueScripts.captureConversation(conversation);
    }

    after(QuestActionManager conversation) returning:
            execution(QuestActionManager.new(Client, int, int, boolean)) && this(conversation) {
        NativeDialogueScripts.captureConversation(conversation);
    }

    before(AbstractScriptManager manager, ScriptEngine engine, Reader reader, String path):
            call(Object ScriptEngine.eval(Reader)) && this(manager) && target(engine) && args(reader)
            && withincode(ScriptEngine AbstractScriptManager.getInvocableScriptEngine(String))
            && cflow(execution(ScriptEngine AbstractScriptManager.getInvocableScriptEngine(String)) && args(path)) {
        if (engine instanceof GraalJSScriptEngine graal) NativeDialogueScripts.captureEngine(manager, graal, path);
    }

    after(ScriptEngine engine) throwing(Throwable failure):
            call(Object ScriptEngine.eval(Reader)) && target(engine)
            && withincode(ScriptEngine AbstractScriptManager.getInvocableScriptEngine(String)) {
        if (engine instanceof GraalJSScriptEngine graal) {
            NativeScriptSession session = NativeScriptSession.forEngine(graal);
            if (session != null) session.recordFailure(failure);
        }
    }

    before(ScriptEngine engine, String symbol, Object binding):
            call(void ScriptEngine.put(String, Object)) && target(engine) && args(symbol, binding)
            && (within(NPCScriptManager) || within(QuestScriptManager)) {
        NativeDialogueScripts.captureBinding(engine, symbol, binding);
    }

    Object around(Invocable invocable, String function, Object[] arguments) throws javax.script.ScriptException, NoSuchMethodException:
            call(Object Invocable.invokeFunction(String, Object...)) && target(invocable) && args(function, arguments)
            && (within(org.gms.scripting..*) || within(org.gms.server.maps..*)) {
        NativeScriptSession session = NativeScriptSession.forEngine(invocable);
        if (session != null && NativeScriptSession.current() != session) {
            return session.invocable().invokeFunction(function, arguments);
        }
        try {
            return proceed(invocable, function, arguments);
        } catch (javax.script.ScriptException failure) {
            if (session != null) session.recordFailure(failure);
            throw failure;
        } catch (NoSuchMethodException failure) {
            // Native managers may recover missing optional entries or retry a documented fallback.
            // Only the final unhandled call may become this invocation's failure.
            throw failure;
        } catch (RuntimeException failure) {
            if (session != null) session.recordFailure(failure);
            throw failure;
        } catch (Error failure) {
            if (session != null) session.recordFailure(failure);
            throw failure;
        }
    }

    void around(Client client, String key):
            execution(void Client.removeScriptEngine(String)) && this(client) && args(key) {
        ScriptEngine exact = client.getScriptEngine(key);
        proceed(client, key);
        if (exact != null && client.getScriptEngine(key) != exact) NativeDialogueScripts.removedEngine(exact);
    }
}
