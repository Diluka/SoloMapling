package org.gms.soloacceptance.actor;

import org.gms.soloacceptance.ObservationException;
import org.springframework.http.HttpStatus;
import soloMapling.game.action.ChatEvidence;
import soloMapling.game.action.ChatEvidenceSource;

/** MCP owns its captured client; the shared probe owns packet matching and outcome reduction. */
final class AcceptanceChatEvidence {
    private AcceptanceChatEvidence() { }

    static ChatEvidenceSource source(NativeActorHandle handle) {
        AcceptanceClient exactClient = handle.client();
        return ChatEvidenceSource.packets(ChatEvidence.Source.SELF_NATIVE_CHAT_PACKET,
                "acceptance:" + exactClient.getSessionId(), new ChatEvidenceSource.PacketFeed() {
                    @Override public void requireCurrent() {
                        // Read facts from this already accepted command's exact transport. Requiring its old
                        // map/active epoch here would hide a broadcast that happened before a later transition.
                        if (handle.closeEvidence() != null || handle.client() != exactClient
                                || exactClient.getPlayer() != handle.character()
                                || handle.character().getClient() != exactClient
                                || exactClient.getAccID() != handle.accountId()
                                || handle.character().getAccountId() != handle.accountId()
                                || !ActorAccountOwnership.owns(handle.ownership(), exactClient)
                                || ActorAccountOwnership.currentOnlineClient(handle.accountId()) != exactClient) {
                            throw new ObservationException(HttpStatus.CONFLICT, "actor_session_stale");
                        }
                    }

                    @Override public long latestCursor() { return exactClient.latestPacketSequence(); }

                    // The packet buffer cannot reopen. A closed receiver may still retain a positive
                    // packet, but an otherwise complete window no longer proves absence of a broadcast.
                    @Override public boolean completeReception() { return !exactClient.transportClosed(); }

                    @Override public java.util.Map<String, Object> window(long sinceCursor, int limit) {
                        return exactClient.packetEvents(sinceCursor, limit);
                    }
                });
    }
}
