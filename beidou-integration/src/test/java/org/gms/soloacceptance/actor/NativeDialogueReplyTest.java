package org.gms.soloacceptance.actor;

import org.gms.client.Client;
import org.gms.constants.game.NextLevelType;
import org.gms.model.pojo.NextLevelContext;
import org.gms.net.packet.Packet;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.scripting.npc.NPCConversationManager;
import org.gms.soloacceptance.ObservationException;
import org.gms.util.PacketCreator;
import org.gms.util.ThreadLocalUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.CharacterTestContext;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeDialogueReplyTest {
    private static final String CENTER = "#L3#传送自由#l #L69#快速转职#l #L70#学习技能#l "
            + "#L71#超级传送#l #L4#爆率一览#l #L2#在线奖励#l #L0#新人福利#l #L1#签到#l #L72#重生#l";
    private static final String CATEGORY = "#L0#BOSS地图#l\r\n#L1#练级地图#l\r\n#L2#城镇地图#l\r\n#L3#活动地图#l";
    private AcceptanceClient client;
    private NPCConversationManager root;

    @BeforeEach
    void installNativeContextWithoutDatasource() {
        CharacterTestContext.install();
        client = client();
        root = manager(null);
        doReturn(root).when(client).getCM();
        doReturn(null).when(client).getQM();
    }

    @Test
    void currentOptionsComeFromActualMenuButOnlyReviewedPathsCanBeReplied() {
        open(CENTER);
        assertEquals("center", state().get("path"));
        assertEquals(List.of(3,69,70,71,4,2,0,1,72), options().get("offered_selections"));
        assertEquals(List.of(0,71), options().get("allowed_selections"));
        assertEquals(List.of("select"), options().get("kinds"));
        assertEquals(true, options().get("complete"));
        assertEquals(false, options().get("consumed"));
        Map<String,Object> unchanged = state();
        for (int hidden : new int[]{61,62,63,64,65,66,67,68,999,700}) {
            error(HttpStatus.CONFLICT,"npc_selection_not_offered", () -> reply("select", hidden));
        }
        for (int unreviewed : new int[]{3,69,70,4,2,1,72}) {
            error(HttpStatus.CONFLICT,"npc_reply_path_not_supported", () -> reply("select", unreviewed));
        }
        assertEquals(unchanged, state());
        assertEquals(1L, client.latestPacketSequence());
        AcceptanceClient.NativeDialogueCommand command = reply("select",0);
        assertEquals(1L,command.version()); assertEquals(4,command.messageType()); assertEquals(1,command.action());
        assertEquals(0,command.selection()); assertEquals("center",command.path()); assertEquals("welfare",command.expectedPath());
        assertFalse(command.consumed()); assertEquals(false, options().get("consumed"));
    }

    @Test
    void defaultCenterCannotTrustAnExternalPacketOrArbitraryNamedManager() {
        client.sendPacket(packet(4,CENTER));
        assertEquals(false,state().get("supported"));
        error(HttpStatus.CONFLICT,"dialogue_not_active", () -> reply("select",0));
        open(CENTER);
        doReturn(manager("新人福利")).when(client).getCM();
        client.sendPacket(packet(12,"领取确认"));
        assertEquals("unsupported",state().get("path"));
        assertEquals(false,state().get("active"));
        error(HttpStatus.CONFLICT,"dialogue_not_active", () -> reply("decline",null));
    }

    @Test
    void trustedWelfareTransitionAllowsOnlyDeclineAndCorrectLegacyCancel() {
        open(CENTER);
        AcceptanceClient.NativeDialogueCommand choose = reply("select",0);
        NPCConversationManager welfare = manager("新人福利");
        client.dispatchNativeDialogue(choose, () -> { doReturn(welfare).when(client).getCM(); client.sendPacket(packet(12,"随机奖励确认")); });
        assertTrue(choose.consumed());
        assertEquals("welfare",state().get("path")); assertEquals(true,state().get("active"));
        assertEquals(12,state().get("message_type")); assertEquals(List.of("decline"),options().get("kinds"));
        assertEquals(List.of(),options().get("offered_selections")); assertEquals(List.of(),options().get("allowed_selections"));
        error(HttpStatus.BAD_REQUEST,"invalid_npc_reply", () -> reply("accept",null));
        error(HttpStatus.CONFLICT,"npc_reply_path_not_supported", () -> reply("select",0));
        AcceptanceClient.NativeDialogueCommand cancel = client.validateNativeDialogueCancel(version());
        assertEquals(12,cancel.messageType()); assertEquals(-1,cancel.action()); assertEquals("closed",cancel.expectedPath());
        AcceptanceClient.NativeDialogueCommand decline = reply("decline",null);
        assertEquals(0,decline.action()); assertNull(decline.selection());
        long old = version();
        client.dispatchNativeDialogue(decline, () -> {
            client.sendPacket(closePacket());
            doReturn(null).when(client).getCM();
        });
        assertTrue(decline.consumed()); assertEquals(false,state().get("active"));
        assertEquals(3L,decline.closedPacketSequence());
        assertEquals("packet_summary", latest().get("event_type"));
        assertEquals("unsupported_npc_message_type", latest().get("reason"));
        error(HttpStatus.GONE,"stale_dialogue", () -> client.validateNativeDialogueCancel(old));
    }

    @Test
    void nextLevelRoutesAreReadAfterHandlerCompletesAndTruncatedTownCanOnlyCancel() {
        NPCConversationManager transport = enterTransport();
        assertEquals("transport_category",state().get("path"));
        assertEquals(List.of(0,1,2,3),options().get("offered_selections"));
        assertEquals(List.of(2),options().get("allowed_selections"));
        assertEquals(0,client.validateNativeDialogueCancel(version()).action());
        for (int unreviewed : new int[]{0,1,3}) error(HttpStatus.CONFLICT,"npc_reply_path_not_supported", () -> reply("select",unreviewed));
        AcceptanceClient.NativeDialogueCommand town = reply("select",2);
        long categoryVersion = version();
        client.dispatchNativeDialogue(town, () -> {
            transport.getNextLevelContext().clear();
            client.sendPacket(packet(4,"#L1#射手村 800金币#l" + "中".repeat(1100)));
            assertEquals(List.of(),options().get("kinds")); // sendNextSelectLevel sets the route only after sendPacket.
            transport.getNextLevelContext().setLevelType(NextLevelType.SEND_NEXT_SELECT);
            transport.getNextLevelContext().setNextLevel("Town");
        });
        assertEquals("transport_town",state().get("path")); assertEquals(true,state().get("active"));
        assertEquals(true,latest().get("text_truncated")); assertEquals(false,options().get("complete"));
        assertEquals(List.of(),options().get("offered_selections")); assertEquals(List.of(),options().get("allowed_selections"));
        error(HttpStatus.CONFLICT,"npc_menu_incomplete", () -> reply("select",1));
        error(HttpStatus.GONE,"stale_dialogue", () -> client.validateNativeDialogueCancel(categoryVersion));
        assertEquals(0,client.validateNativeDialogueCancel(version()).action());
        transport.getNextLevelContext().setNextLevel("Boss");
        error(HttpStatus.CONFLICT,"dialogue_not_active", () -> client.validateNativeDialogueCancel(version()));
    }

    @Test
    void townTransitionMustKeepTheSameManagerAndWrongNamedSuccessorCannotInherit() {
        NPCConversationManager transport = enterTransport();
        AcceptanceClient.NativeDialogueCommand town = reply("select",2);
        client.dispatchNativeDialogue(town, () -> {
            NPCConversationManager replacement = manager("万能传送");
            replacement.getNextLevelContext().setLevelType(NextLevelType.SEND_NEXT_SELECT);
            replacement.getNextLevelContext().setNextLevel("Town");
            doReturn(replacement).when(client).getCM(); client.sendPacket(packet(4,"#L1#射手村#l"));
        });
        assertEquals(false,state().get("supported"));
        doReturn(root).when(client).getCM(); open(CENTER);
        AcceptanceClient.NativeDialogueCommand welfare = reply("select",0);
        client.dispatchNativeDialogue(welfare, () -> { doReturn(transport).when(client).getCM(); client.sendPacket(packet(12,"伪装礼包确认")); });
        assertEquals("unsupported",state().get("path"));
    }

    @Test
    void malformedOrUnknownPacketPoisonsTheDispatchAndCannotBeFollowedByATrustedMenu() {
        open(CENTER);
        AcceptanceClient.NativeDialogueCommand command = reply("select",0);
        NPCConversationManager welfare = manager("新人福利");
        client.dispatchNativeDialogue(command, () -> {
            doReturn(welfare).when(client).getCM();
            client.sendPacket(() -> new byte[]{0x30,0x01,4});
            client.sendPacket(packet(12,"合法格式但已失去来源"));
        });
        assertEquals(3L,version()); assertEquals(false,state().get("supported"));
        error(HttpStatus.CONFLICT,"dialogue_not_active", () -> reply("decline",null));
    }

    @Test
    void sameScopeCannotReplaceItsFirstTrustedTargetWithAnotherSameNamedManager() {
        open(CENTER);
        AcceptanceClient.NativeDialogueCommand command=reply("select",0);
        NPCConversationManager first=manager("新人福利");
        NPCConversationManager second=manager("新人福利");
        client.dispatchNativeDialogue(command, () -> {
            doReturn(first).when(client).getCM(); client.sendPacket(packet(12,"首个确认"));
            assertEquals("welfare",state().get("path"));
            doReturn(second).when(client).getCM(); client.sendPacket(packet(12,"同名但不同CM"));
        });
        assertEquals(false,state().get("supported"));
        error(HttpStatus.CONFLICT,"dialogue_not_active", () -> reply("decline",null));
    }

    @Test
    void unreadablePacketPoisonsScopeWithoutLettingLaterValidPacketRecoverTrust() {
        open(CENTER);
        AcceptanceClient.NativeDialogueCommand command=reply("select",0);
        client.dispatchNativeDialogue(command, () -> {
            doReturn(manager("新人福利")).when(client).getCM();
            client.sendPacket(() -> { throw new IllegalStateException("private error"); });
            client.sendPacket(packet(12,"不能复活来源"));
        });
        assertEquals(3L,state().get("dialogue_version"));
        assertEquals(false,state().get("supported"));
        assertEquals(false,state().get("active"));
    }

    @Test
    void terminalProofRequiresStrictNativeSendOkAndDoesNotExposeItsText() {
        Packet valid=closePacket();
        assertTrue(PacketEventDecoder.nativeNpcClose(valid.getBytes(),java.nio.charset.Charset.forName("GBK")));
        for (int length=0;length<valid.getBytes().length;length++) {
            assertFalse(PacketEventDecoder.nativeNpcClose(Arrays.copyOf(valid.getBytes(),length),java.nio.charset.Charset.forName("GBK")));
        }
        for (int position:new int[]{0,2,3,7,8,valid.getBytes().length-2,valid.getBytes().length-1}) {
            byte[] changed=valid.getBytes().clone(); changed[position]^=1;
            assertFalse(PacketEventDecoder.nativeNpcClose(changed,java.nio.charset.Charset.forName("GBK")));
        }
        byte[] badLength=valid.getBytes().clone();badLength[9]=(byte)0xff;badLength[10]=(byte)0xff;
        assertFalse(PacketEventDecoder.nativeNpcClose(badLength,java.nio.charset.Charset.forName("GBK")));
        byte[] badText=packet(0,"A").getBytes();
        // This raw packet has no type0 tail; append the normal tail and corrupt its GBK text byte.
        badText=Arrays.copyOf(badText,badText.length+2);badText[11]=(byte)0x81;
        assertFalse(PacketEventDecoder.nativeNpcClose(badText,java.nio.charset.Charset.forName("GBK")));
        byte[] extra=Arrays.copyOf(valid.getBytes(),valid.getBytes().length+1);
        assertFalse(PacketEventDecoder.nativeNpcClose(extra,java.nio.charset.Charset.forName("GBK")));
        assertFalse(PacketEventDecoder.decode(valid.getBytes(),java.nio.charset.Charset.forName("GBK")).containsKey("text"));
    }

    @Test
    void declineProofRejectsMalformedUnknownWrongManagerAndSubsequentNpcPackets() {
        for (int variant=0;variant<10;variant++) {
            doReturn(root).when(client).getCM(); open(CENTER);
            AcceptanceClient.NativeDialogueCommand choose=reply("select",0);
            NPCConversationManager welfare=manager("新人福利");
            client.dispatchNativeDialogue(choose, () -> { doReturn(welfare).when(client).getCM();client.sendPacket(packet(12,"确认")); });
            AcceptanceClient.NativeDialogueCommand decline=reply("decline",null);
            int test=variant;
            client.dispatchNativeDialogue(decline, () -> {
                if (test==0) client.sendPacket(() -> new byte[]{0x30,1,4});
                if (test==1) client.sendPacket(packet(1,"未知业务消息"));
                if (test==2) { doReturn(manager("新人福利")).when(client).getCM();client.sendPacket(closePacket()); }
                if (test==3) { client.sendPacket(closePacket());client.sendPacket(packet(1,"后续未知")); }
                if (test==4) { client.sendPacket(packet(1,"先破坏来源"));client.sendPacket(closePacket()); }
                if (test==5) { doThrow(new IllegalStateException("native getter failed")).when(client).getCM();client.sendPacket(closePacket()); }
                if (test==6) java.util.concurrent.CompletableFuture.runAsync(() -> client.sendPacket(closePacket())).join();
                if (test==7) { client.sendPacket(closePacket());client.sendPacket(closePacket()); }
                if (test==8) { client.sendPacket(closePacket());client.sendPacket(() -> new byte[1]); }
                if (test==9) {
                    doReturn(mock(org.gms.scripting.quest.QuestActionManager.class)).when(client).getQM();
                    client.sendPacket(closePacket());doReturn(null).when(client).getQM();
                }
                doReturn(null).when(client).getCM();
            });
            assertTrue(decline.consumed()); assertNull(decline.closedPacketSequence(),"variant="+variant);
        }
    }

    @Test
    void captureWhichFinishesAfterItsScopeCannotCreateATrustedState() {
        PacketEventBuffer buffer=new PacketEventBuffer();
        NativeDialogueTracker tracker=new NativeDialogueTracker(buffer);
        NativeDialogueTracker.DispatchScope scope=tracker.openCenter();
        PacketEventBuffer.Receipt receipt=buffer.accept(packet(4,CENTER),3);
        NativeDialogueTracker.Pending pending=tracker.begin(tracker.epoch(),receipt);
        tracker.finish(scope,false);
        tracker.capture(pending,root,9900001,null,false,true);
        assertFalse(tracker.current().supported());
        assertNull(tracker.current().cm);
    }

    @Test
    void versionIsConsumedBeforeNativeCallAndCannotBeReusedEvenWithoutNewPacket() {
        open(CENTER);
        AcceptanceClient.NativeDialogueCommand first = reply("select",0);
        AcceptanceClient.NativeDialogueCommand competing = reply("select",0);
        AtomicInteger calls = new AtomicInteger();
        client.dispatchNativeDialogue(first, () -> {
            calls.incrementAndGet(); assertTrue(first.consumed()); assertEquals(true,options().get("consumed"));
            assertEquals(List.of(),options().get("kinds"));
        });
        assertEquals(true,options().get("consumed"));
        error(HttpStatus.GONE,"stale_dialogue", () -> client.dispatchNativeDialogue(first,calls::incrementAndGet));
        error(HttpStatus.GONE,"stale_dialogue", () -> client.dispatchNativeDialogue(competing,calls::incrementAndGet));
        error(HttpStatus.GONE,"stale_dialogue", () -> reply("select",0));
        assertEquals(1,calls.get()); assertFalse(competing.consumed());
    }

    @Test
    void commandIsClientBoundAndDispatchRevalidatesActualRouteBeforeConsuming() {
        open(CENTER);
        AcceptanceClient.NativeDialogueCommand command = reply("select",0);
        AcceptanceClient foreign = client();
        error(HttpStatus.GONE,"stale_dialogue", () -> foreign.dispatchNativeDialogue(command, () -> fail("foreign dispatch")));
        assertFalse(command.consumed());
        root.getNextLevelContext().setLevelType(NextLevelType.SEND_SELECT);
        error(HttpStatus.CONFLICT,"dialogue_not_active", () -> client.dispatchNativeDialogue(command, () -> fail("wrong route")));
        assertFalse(command.consumed());
        root.getNextLevelContext().clear();
        client.dispatchNativeDialogue(command, () -> {});
        assertTrue(command.consumed());
    }

    @Test
    void nativeFailureDoesNotRestoreConsumedVersionOrTrustAnInflightResult() {
        open(CENTER);
        AcceptanceClient.NativeDialogueCommand command = reply("select",0);
        assertThrows(IllegalStateException.class, () -> client.dispatchNativeDialogue(command, () -> {
            doReturn(manager("新人福利")).when(client).getCM(); client.sendPacket(packet(12,"暂时建立的确认"));
            throw new IllegalStateException("native handler failed");
        }));
        assertTrue(command.consumed()); assertNull(state().get("dialogue_version"));
        error(HttpStatus.GONE,"stale_dialogue", () -> client.dispatchNativeDialogue(command, () -> fail("must not retry")));
    }

    @Test
    void invalidOffersCannotSelectButCanStillUseTheTrustedNativeCancel() {
        for (String text : List.of("#L0#未闭合", "#L0#a#L71#nested#l#l", "#L0#a#l#L0#duplicate#l")) {
            open(text);
            assertEquals(true,state().get("active")); assertEquals(false,options().get("complete"));
            error(HttpStatus.CONFLICT,"npc_menu_incomplete", () -> reply("select",0));
            assertEquals(-1,client.validateNativeDialogueCancel(version()).action());
        }
    }

    @Test
    void typeTwelveUsesStrictNativeEncodingAndNeverLeaksUnsupportedOrMalformedBodies() {
        open(CENTER);
        AcceptanceClient.NativeDialogueCommand command = reply("select",0);
        client.dispatchNativeDialogue(command, () -> {
            doReturn(manager("新人福利")).when(client).getCM(); client.sendPacket(packet(12,"是否拒绝礼包？"));
        });
        assertEquals("是否拒绝礼包？",latest().get("text")); assertEquals(12,latest().get("message_type"));
        Packet valid = packet(12,"A");
        byte[] malformed = valid.getBytes().clone(); malformed[malformed.length-1]=(byte)0x81;
        client.sendPacket(() -> malformed);
        assertEquals("packet_text_encoding_invalid",latest().get("reason")); assertFalse(latest().containsKey("text"));
        byte[] trailing = Arrays.copyOf(valid.getBytes(),valid.getBytes().length+1);
        client.sendPacket(() -> trailing); assertEquals("packet_trailing_bytes",latest().get("reason"));
        assertEquals(false,state().get("active"));
    }

    @Test
    void currentOptionsAreImmutableBoundedAndAreNotRepeatedInPacketHistory() {
        open(CENTER);
        Map<String,Object> opts=options();
        assertEquals(5,opts.size()); assertThrows(UnsupportedOperationException.class, () -> opts.put("complete",false));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>)opts.get("offered_selections")).clear());
        assertFalse(latest().containsKey("reply_options")); assertFalse(latest().containsKey("offered_selections"));
        assertFalse(state().toString().contains("新人福利"));
        assertEquals(Map.of("entrypoints",List.of("native_ui_center"),"message_types",List.of(4,12),
                "version_scope","session_packet_sequence","cancel_requires_latest_version",true,"npc_minimum_interval_ms",500,
                "reply_kinds",List.of("select","decline"),"requires_complete_offers",true,
                "requires_current_offered_selection",true,"maximum_offered_selections",128), AcceptanceClient.nativeDialogueCapabilities());
    }

    private NPCConversationManager enterTransport() {
        open(CENTER);
        AcceptanceClient.NativeDialogueCommand command=reply("select",71);
        NPCConversationManager transport=manager("万能传送");
        client.dispatchNativeDialogue(command, () -> {
            doReturn(transport).when(client).getCM(); client.sendPacket(packet(4,CATEGORY));
            assertEquals("transport_category",state().get("path")); assertEquals(List.of(),options().get("kinds"));
            transport.getNextLevelContext().setLevelType(NextLevelType.SEND_SELECT);
            transport.getNextLevelContext().setPrefix("");
        });
        return transport;
    }

    private void open(String text) { client.dispatchNativeCenter(() -> client.sendPacket(packet(4,text))); }
    private AcceptanceClient.NativeDialogueCommand reply(String kind,Integer selection) {
        return client.validateNativeDialogueReply(version(),kind,selection);
    }
    private long version() { return (Long)state().get("dialogue_version"); }
    private Map<String,Object> state() { return client.dialogueState(); }
    @SuppressWarnings("unchecked") private Map<String,Object> options() { return (Map<String,Object>)state().get("reply_options"); }
    @SuppressWarnings("unchecked") private Map<String,Object> latest() { return ((List<Map<String,Object>>)client.packetEvents(0,500).get("items")).getLast(); }
    private static void error(HttpStatus status,String reason,Runnable action) {
        ObservationException failure=assertThrows(ObservationException.class,action::run);
        assertEquals(status,failure.status()); assertEquals(reason,failure.reason());
    }
    private AcceptanceClient client() {
        World world=mock(World.class); Channel channel=mock(Channel.class);
        when(world.getId()).thenReturn(0); when(channel.getId()).thenReturn(1);
        AcceptanceClient c=spy(new AcceptanceClient(Client.Type.CHANNEL,-504L,"127.0.0.104",world,channel));c.setLanguage(3);return c;
    }
    private static NPCConversationManager manager(String name) {
        NPCConversationManager manager=mock(NPCConversationManager.class);
        when(manager.getNpc()).thenReturn(9900001); when(manager.getScriptName()).thenReturn(name);
        when(manager.getNextLevelContext()).thenReturn(new NextLevelContext()); return manager;
    }
    private Packet packet(int type,String text) {
        Client previous=ThreadLocalUtil.getCurrentClient();ThreadLocalUtil.setCurrentClient(client);
        try { return PacketCreator.getNPCTalk(9900001,(byte)type,text,"",(byte)0); }
        finally { if(previous==null)ThreadLocalUtil.removeCurrentClient();else ThreadLocalUtil.setCurrentClient(previous); }
    }
    private Packet closePacket() {
        Client previous=ThreadLocalUtil.getCurrentClient();ThreadLocalUtil.setCurrentClient(client);
        try { return PacketCreator.getNPCTalk(9900001,(byte)0,"好吧，下次再来领取吧。","00 00",(byte)0); }
        finally { if(previous==null)ThreadLocalUtil.removeCurrentClient();else ThreadLocalUtil.setCurrentClient(previous); }
    }
}
