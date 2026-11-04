package soloMapling.game.runtime;

import soloMapling.game.context.GameContext;
import soloMapling.game.execution.ScopeResources;

import java.util.Map;
import java.util.Optional;
import org.gms.net.server.Server;

/** Trusted origin adapter: ordinary account and environment-bot identity policies stay separate. */
public interface RoleBinding {
    String kind();
    Object identity();
    GameContext.CharacterRef characterRef();
    void requireCurrent();

    /** Native headless sources supply their existing host when client behavior needs scene simulation. */
    default Optional<Server> clientSimulationServer() { return Optional.empty(); }

    /** Fresh bounded native facts, not another authoritative character model or transport session. */
    Map<String, Object> observe();

    /** The adapter must verify its native cleanup/persistence policy before returning released=true. */
    ReleaseProof release(ScopeResources.DrainPermit permit);

    record ReleaseProof(boolean released) { }
}
