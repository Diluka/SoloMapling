package org.gms.soloscripting;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.gms.scripting.AbstractScriptManager;

import javax.script.ScriptEngine;

/** Reuses the native language fallback, UTF-8 loading and host-access initialization unchanged. */
public final class NativeScriptLoader extends AbstractScriptManager {
    private static final NativeScriptLoader INSTANCE = new NativeScriptLoader();

    private NativeScriptLoader() { }

    public static GraalJSScriptEngine load(String relativePath) {
        if (relativePath == null || relativePath.startsWith("/") || relativePath.contains("\\")
                || relativePath.contains("..") || !relativePath.endsWith(".js")) {
            throw new IllegalArgumentException("invalid_native_script_path");
        }
        ScriptEngine engine = INSTANCE.getInvocableScriptEngine(relativePath);
        if (!(engine instanceof GraalJSScriptEngine graal)) {
            throw new IllegalStateException("native_script_not_found");
        }
        return graal;
    }
}
