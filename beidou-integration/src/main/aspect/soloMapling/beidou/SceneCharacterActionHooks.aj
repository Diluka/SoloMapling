package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.net.packet.InPacket;
import org.gms.net.server.channel.handlers.AbstractDealDamageHandler;
import org.gms.net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import soloMapling.game.scene.SceneRuntimeManager;

/** The parsed input contains the one body action actually selected for this native dispatch. */
public aspect SceneCharacterActionHooks {
    after(Character actor) returning(AttackInfo attack):
            execution(AttackInfo AbstractDealDamageHandler.parseDamage(InPacket, Character, boolean, boolean))
            && args(*, actor, *, *) {
        SceneRuntimeManager.attackInput(actor, attack);
    }
}
