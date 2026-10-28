package org.gms.soloacceptance.actor;

import soloMapling.game.execution.ExecutionScope;

import org.gms.client.Character;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.service.AccountService;
import org.gms.service.CharacterService;
import org.gms.service.NoteService;
import org.gms.soloacceptance.ObservationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.CharacterTestContext;
import soloMapling.game.context.WorldEpoch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Input-only checks isolate current ownership; no native login, dispatch or datasource is involved. */
class NativeActorChatValidationTest {
    private ActorProfiles profiles;
    private AccountService accounts;
    private CharacterService characters;
    private NoteService notes;
    private AcceptanceClient client;
    private NativeActorHandle handle;
    private NativeActorBackend backend;

    @BeforeEach
    void ordinaryChineseFixtureWithCurrentHandle() {
        CharacterTestContext.install();
        WorldEpoch.onWorldStarted();
        profiles = mock(ActorProfiles.class);
        accounts = mock(AccountService.class);
        characters = mock(CharacterService.class);
        notes = mock(NoteService.class);
        client = mock(AcceptanceClient.class);
        Character character = mock(Character.class);
        when(character.gmLevel()).thenReturn(0);
        when(client.getLanguage()).thenReturn(3);
        handle = new NativeActorHandle("primary", 17, client, character,
                mock(World.class), mock(Channel.class), mock(ActorAccountOwnership.Lease.class), false, false);
        backend = spy(new NativeActorBackend(profiles, accounts, characters, notes, () -> {
            fail("chat validation must not access the native server");
            return null;
        }));
        doReturn(true).when(backend).isCurrent(handle);
    }

    @AfterEach
    void inputChecksNeverReadProfilesOrPersistenceServices() {
        ExecutionScope.tryBeginWorldStop();
        verifyNoInteractions(profiles, accounts, characters, notes);
        verify(client, never()).sendPacket(any());
    }

    @Test
    void rejectsNullEmptyAndEntirelyBlankTextWithFixedInputReason() {
        for (String text : new String[]{null, "", " ", "\t\r\n", "\u2003\u3000"}) {
            assertRejected(text, HttpStatus.BAD_REQUEST, "invalid_chat_text");
        }
    }

    @Test
    void acceptsRawTextLengthsFromOneThrough127Utf16CodeUnits() {
        for (int length : new int[]{1, 2, 126, 127}) {
            String text = "a".repeat(length);
            assertEquals(length, text.length());
            assertDoesNotThrow(() -> backend.validateChat(handle, text));
        }
        assertRejected("a".repeat(128), HttpStatus.BAD_REQUEST, "invalid_chat_text");
    }

    @Test
    void retainsLegalLeadingAndTrailingWhitespaceWithoutTrimmingForValidation() {
        for (String text : new String[]{" 有效正文 ", "\t有效正文\n", " @help ", " /all ", " " + "a".repeat(125) + " "}) {
            assertDoesNotThrow(() -> backend.validateChat(handle, text));
        }
        // Raw length is 128 even though trim() would reduce it to 126.
        assertRejected(" " + "a".repeat(126) + " ", HttpStatus.BAD_REQUEST, "invalid_chat_text");
    }

    @Test
    void blocksOnlyNativeCommandAndNonBroadcastPrefixesAtTheFirstCharacter() {
        for (String text : new String[]{"@", "@help", "/", "/all hello"}) {
            assertRejected(text, HttpStatus.BAD_REQUEST, "invalid_chat_text");
        }
        for (String text : new String[]{"!", "!help", "普通正文@玩家", "普通正文/分隔"}) {
            assertDoesNotThrow(() -> backend.validateChat(handle, text));
        }
    }

    @Test
    void gbkAllows127ChineseCharactersEvenThoughTheyUse254EncodedBytes() {
        String text = "中".repeat(127);
        assertEquals(127, text.length());
        assertDoesNotThrow(() -> backend.validateChat(handle, text));
        assertRejected("中".repeat(128), HttpStatus.BAD_REQUEST, "invalid_chat_text");
    }

    @Test
    void gbkRejectsUnmappableEmojiAndMalformedSurrogatesWithoutReplacingThem() {
        for (String text : new String[]{"\uD83D\uDE00", "普通正文\uD83D\uDE00", "\uD800", "\uDC00", "a\uD800b", "a\uDC00b"}) {
            assertRejected(text, HttpStatus.BAD_REQUEST, "chat_text_not_encodable");
        }
    }

    @Test
    void utf16LengthLimitPrecedesNativeEncodingAndDoesNotCountUnicodeCodePoints() {
        String withinLengthButUnencodable = "\uD83D\uDE00".repeat(63) + "a";
        assertEquals(127, withinLengthButUnencodable.length());
        assertRejected(withinLengthButUnencodable, HttpStatus.BAD_REQUEST, "chat_text_not_encodable");

        String overUtf16Limit = "\uD83D\uDE00".repeat(64);
        assertEquals(64, overUtf16Limit.codePointCount(0, overUtf16Limit.length()));
        assertEquals(128, overUtf16Limit.length());
        assertRejected(overUtf16Limit, HttpStatus.BAD_REQUEST, "invalid_chat_text");
    }

    @Test
    void asciiLanguageRejectsChineseUsingTheActorsConfiguredCharset() {
        when(client.getLanguage()).thenReturn(2);
        assertDoesNotThrow(() -> backend.validateChat(handle, "ASCII message"));
        assertRejected("中文消息", HttpStatus.BAD_REQUEST, "chat_text_not_encodable");
    }

    @Test
    void staleHandleCannotValidateEvenOtherwiseValidPlainText() {
        doReturn(false).when(backend).isCurrent(handle);
        assertRejected("普通聊天", HttpStatus.CONFLICT, "actor_session_stale");
    }

    private void assertRejected(String text, HttpStatus status, String reason) {
        ObservationException error = assertThrows(ObservationException.class,
                () -> backend.validateChat(handle, text));
        assertEquals(status, error.status());
        assertEquals(reason, error.reason());
        assertEquals(reason, error.getMessage());
    }
}
