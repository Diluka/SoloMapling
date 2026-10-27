package soloMapling.beidou;

import org.gms.net.server.Server;
import soloMapling.game.action.ChatEvidenceSource;
import soloMapling.game.action.NativeActionService;
import soloMapling.game.action.NativeChatAction;
import soloMapling.game.context.GameContext;
import soloMapling.game.execution.CharacterCommandLane;
import soloMapling.game.execution.ExecutionScope;

import java.util.Objects;

/** Bot identity adapter over the shared action service; module grants remain caller-owned. */
public final class BotNativeActions {
    private final NativeActionService actions;

    public BotNativeActions(NativeActionService actions) {
        this.actions = Objects.requireNonNull(actions);
    }

    /** A BotClient has no receive history, so dispatch alone cannot prove a broadcast. */
    public NativeActionService.ActionHandle prepareChat(Server server, BotGameContexts.Binding binding,
                                                       CharacterCommandLane lane, CharacterCommandLane.Grant grant,
                                                       ExecutionScope.WorkHandle work, GameContext.SceneRef scene,
                                                       NativeChatAction action) {
        return prepareChat(server, binding, lane, grant, work, scene, action, ChatEvidenceSource.none());
    }

    /** The optional bounded evidence source is caller-owned; no subscription or executor is installed. */
    public NativeActionService.ActionHandle prepareChat(Server server, BotGameContexts.Binding binding,
                                                       CharacterCommandLane lane, CharacterCommandLane.Grant grant,
                                                       ExecutionScope.WorkHandle work, GameContext.SceneRef scene,
                                                       NativeChatAction action, ChatEvidenceSource evidence) {
        Objects.requireNonNull(lane);
        Objects.requireNonNull(work);
        BotGameContexts.requireSceneCurrent(server, binding, scene);
        CharacterCommandLane.ControlPermit permit = lane.bindGrantedPermit(work.scope(), binding.characterRef(), grant,
                () -> BotGameContexts.requireSceneCurrent(server, binding, scene));
        return actions.prepareChat(server, lane, permit, work, scene, action, evidence);
    }

    public NativeActionService.SubmissionReceipt submitChat(NativeActionService.ActionHandle handle) {
        return actions.submitChat(handle);
    }
}
