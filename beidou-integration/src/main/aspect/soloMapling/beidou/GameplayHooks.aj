package soloMapling.beidou;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.net.server.Server;
import org.gms.net.server.coordinator.world.InviteCoordinator;
import org.gms.net.server.coordinator.world.InviteCoordinator.InviteType;
import org.gms.server.ChatLogger;
import org.gms.server.Trade;
import org.gms.server.maps.MapleMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeQueue;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventFactory;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;

/** Business hooks woven into the immutable official host binaries at build time. */
public privileged aspect GameplayHooks {
    private static final Logger LOG = LoggerFactory.getLogger(GameplayHooks.class);
    after() returning: execution(void Server.init()) {
        BeiDouRuntime.onServerReady();
    }

    before(): execution(void Server.shutdownInternal(boolean)) {
        BeiDouRuntime.onServerStopping();
    }

    // ChatLogger is invoked only after packet validation, mute checks and actual
    // broadcast. Advising handlePacket's exit would also observe rejected input.
    after(Client client, String kind, String message) returning:
            call(void ChatLogger.log(Client, String, String))
            && withincode(void org.gms.net.server.channel.handlers.GeneralChatHandler.handlePacket(..))
            && args(client, kind, message) {
        if (HostCompatibility.isGeneralChatKind(kind) && !BotRegistry.isBot(client.getPlayer())) {
            try {
                MessageQueue.getInstance().addMessage("primary", new ChatMessage(client.getPlayer(), message));
            } catch (RuntimeException failure) {
                LOG.warn("SoloMapling chat observer failed after native chat delivery", failure);
            }
        }
    }

    after(InviteType type, Character inviter, Object referenceId, int invitedId) returning(boolean created):
            execution(boolean InviteCoordinator.createInvite(..))
            && args(type, inviter, referenceId, invitedId, *) {
        if (!created) {
            return;
        }
        Character invited = inviter.getWorldServer().getPlayerStorage().getCharacterById(invitedId);
        if (!BotRegistry.isBot(invited)) {
            return;
        }
        if (type == InviteType.PARTY && referenceId instanceof Integer) {
            try {
                BotPartyQueue.getInstance().addPartyInvite(invited, inviter, ((Integer) referenceId).intValue());
            } catch (RuntimeException failure) {
                LOG.warn("SoloMapling party observer failed after native invitation", failure);
            }
        }
    }

    after(Character inviter, Character invited) returning:
            execution(void Trade.inviteTrade(Character, Character)) && args(inviter, invited) {
        if (BotRegistry.isBot(invited) && inviter.getTrade() != null && invited.getTrade() != null
                && inviter.getTrade().getPartner() == invited.getTrade()
                && invited.getTrade().getPartner() == inviter.getTrade()) {
            try {
                BotTradeQueue.getInstance().addTradeRequest(invited, inviter);
            } catch (RuntimeException failure) {
                LOG.warn("SoloMapling trade observer failed after native invitation", failure);
            }
        }
    }

    after(MapleMap map, Character character) returning:
            execution(void MapleMap.addPlayer(Character)) && this(map) && args(character) {
        if (!BotRegistry.isBot(character)) {
            try {
                EventBus.getInstance().publish(new GameEvent(character, EventType.MAP_ENTERED, null, null, null));
            } catch (RuntimeException failure) {
                LOG.warn("SoloMapling map observer failed after native map entry", failure);
            }
        }
    }

    void around(Character character): execution(void Character.gainExpInternal(..))
            && this(character) && !cflowbelow(execution(void Character.gainExpInternal(..))) {
        int previousLevel = character.getLevel();
        proceed(character);
        if (character.getLevel() > previousLevel && character.getMap() != null) {
            try {
                EventBus.getInstance().publish(EventFactory.createLevelUpEvent(character));
            } catch (RuntimeException failure) {
                LOG.warn("SoloMapling level observer failed after native experience gain", failure);
            }
        }
    }

    // A headless character must not run player-only entry scripts.
    boolean around(Client client, String script, boolean first):
            call(boolean org.gms.scripting.map.MapScriptManager.runMapScript(Client, String, boolean))
            && withincode(void MapleMap.addPlayer(Character)) && args(client, script, first) {
        if (!(client instanceof org.gms.client.BotClient)) {
            return proceed(client, script, first);
        }
        return false;
    }

    void around(MapleMap map, Client client):
            call(void MapleMap.sendObjectPlacement(Client))
            && withincode(void MapleMap.addPlayer(Character)) && target(map) && args(client) {
        if (!(client instanceof org.gms.client.BotClient)) {
            proceed(map, client);
        }
    }

    // The base engine excludes isAwayFromWorld() characters as ghosts. The
    // integration intentionally marks its headless characters present before add.
    before(Character character): execution(void MapleMap.addPlayer(Character)) && args(character) {
        if (BotRegistry.isBot(character)) {
            if (!BotRegistry.isRegistered(character)) {
                throw new IllegalStateException("An unregistered headless character cannot enter a map");
            }
            character.setCanRecvPartySearchInvite(false);
            character.setEnteredChannelWorld();
        }
    }
}
