package soloMapling.game.runtime;

import soloMapling.game.context.GameContext;
import soloMapling.game.execution.ScopeResources;

import java.util.Map;

/** Trusted origin adapter: ordinary account and environment-bot identity policies stay separate. */
public interface RoleBinding {
    String kind();
    Object identity();
    GameContext.CharacterRef characterRef();
    void requireCurrent();

    /** Fresh bounded native facts, not another authoritative character model or transport session. */
    Map<String, Object> observe();

    /** The adapter must verify its native cleanup/persistence policy before returning released=true. */
    ReleaseProof release(ScopeResources.DrainPermit permit);

    record ReleaseProof(boolean released) { }
}
