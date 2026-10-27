package org.gms.soloacceptance.actor;

import org.gms.config.GameConfig;
import org.gms.service.AccountService;
import org.gms.service.CharacterService;
import org.gms.service.NoteService;
import org.gms.soloacceptance.ObservationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import soloMapling.beidou.CharacterTestContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeActorChatTimingTest {
    private NativeActorBackend backend;

    @BeforeEach
    void nativeContextWithoutDatasource() {
        CharacterTestContext.install();
        backend = new NativeActorBackend(mock(ActorProfiles.class), mock(AccountService.class),
                mock(CharacterService.class), mock(NoteService.class));
    }

    @Test
    void readsTheCurrentNativeClockIntervalForEveryCapabilityRequest() {
        try (MockedStatic<GameConfig> config = mockStatic(GameConfig.class)) {
            config.when(() -> GameConfig.getServerLong("update_interval")).thenReturn(83L, 1429L);
            Map<String, Object> first = backend.chatTimingCapabilities();
            assertEquals(Map.of("chat_minimum_interval_ms", 200,
                    "native_clock_update_interval_ms", 83L), first);
            assertEquals(Map.of("chat_minimum_interval_ms", 200,
                    "native_clock_update_interval_ms", 1429L), backend.chatTimingCapabilities());
            assertEquals(83L, first.get("native_clock_update_interval_ms"));
            config.verify(() -> GameConfig.getServerLong("update_interval"), times(2));
        }
    }

    @Test
    void refusesZeroAndNegativeNativeIntervalsInsteadOfPublishingTimingGuesses() {
        try (MockedStatic<GameConfig> config = mockStatic(GameConfig.class)) {
            config.when(() -> GameConfig.getServerLong("update_interval")).thenReturn(0L, -1L, Long.MIN_VALUE);
            for (int attempt = 0; attempt < 3; attempt++) {
                ObservationException failure = assertThrows(ObservationException.class, backend::chatTimingCapabilities);
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.status());
                assertEquals("actor_chat_timing_unavailable", failure.reason());
            }
            config.verify(() -> GameConfig.getServerLong("update_interval"), times(3));
        }
    }
}
