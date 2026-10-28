package org.gms.soloscripting;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.gms.scripting.AbstractScriptManager;

import javax.script.ScriptEngine;

/** Reuses the native language fallback, UTF-8 loading and host-access initialization unchanged. */
final class NativeScriptLoader extends AbstractScriptManager {
    private static final NativeScriptLoader INSTANCE = new NativeScriptLoader();

    private NativeScriptLoader() { }

    static GraalJSScriptEngine load(String relativePath) {
        ScriptEngine engine = INSTANCE.getInvocableScriptEngine(relativePath);
        if (!(engine instanceof GraalJSScriptEngine graal)) {
            throw new IllegalStateException("native_script_not_found");
        }
        return graal;
    }
}
