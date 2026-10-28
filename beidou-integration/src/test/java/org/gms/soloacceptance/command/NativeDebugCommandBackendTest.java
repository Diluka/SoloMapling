package org.gms.soloacceptance.command;

import org.gms.client.BotClient;
import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.dao.entity.AccountsDO;
import org.gms.dao.entity.CharactersDO;
import org.gms.model.dto.GiveResourceReqDTO;
import org.gms.net.server.PlayerStorage;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.ItemInformationProvider;
import org.gms.service.AccountService;
import org.gms.service.CharacterService;
import org.gms.service.GiveService;
import org.gms.soloacceptance.actor.ActorProfiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import soloMapling.beidou.CharacterTestContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeDebugCommandBackendTest {
    private static final int POTION = 2_000_000;
    private static final int SHELL = 4_000_000;
    private final ActorProfiles.Resolved profile = new ActorProfiles.Resolved(
            "primary", "mcp_primary", "test-password", "McpPrimary", 0, 1, false);
    private AccountService accounts;
    private CharacterService characters;
    private GiveService giveService;
    private ItemInformationProvider items;
    private Server server;
    private World world;
    private Channel channel;
    private PlayerStorage worldPlayers;
    private PlayerStorage channelPlayers;
    private AccountsDO account;
    private CharactersDO stored;
    private Character character;
    private Client client;
    private Inventory inventory;
    private NativeDebugCommandBackend backend;

    @BeforeEach
    void setup() {
        CharacterTestContext.install();
        accounts = mock(AccountService.class);
        characters = mock(CharacterService.class);
        giveService = mock(GiveService.class);
        items = mock(ItemInformationProvider.class);
        server = mock(Server.class);
        world = mock(World.class);
        channel = mock(Channel.class);
        worldPlayers = mock(PlayerStorage.class);
        channelPlayers = mock(PlayerStorage.class);
        character = mock(Character.class);
        // A normal native client is deliberate: no AcceptanceClient, module, or runtime is needed.
        client = mock(Client.class);
        account = AccountsDO.builder().id(17).name("mcp_primary").webadmin(0).banned(false).build();
        stored = CharactersDO.builder().id(41).accountid(17).name("McpPrimary").world(0).gm(0).build();
        when(accounts.findByName("mcp_primary")).thenReturn(account);
        when(characters.findByName("McpPrimary")).thenReturn(stored);
        when(server.isOnline()).thenReturn(true);
        when(server.getWorld(0)).thenReturn(world);
        when(world.getId()).thenReturn(0);
        when(world.getChannel(1)).thenReturn(channel);
        when(channel.getId()).thenReturn(1);
        when(world.getPlayerStorage()).thenReturn(worldPlayers);
        when(channel.getPlayerStorage()).thenReturn(channelPlayers);
        when(worldPlayers.getCharacterById(41)).thenReturn(character);
        when(channelPlayers.getCharacterById(41)).thenReturn(character);
        when(character.getId()).thenReturn(41);
        when(character.getAccountId()).thenReturn(17);
        when(character.getName()).thenReturn("McpPrimary");
        when(character.getWorld()).thenReturn(0);
        when(character.getClient()).thenReturn(client);
        when(character.isLoggedInWorld()).thenReturn(true);
        when(client.getPlayer()).thenReturn(character);
        when(client.getAccID()).thenReturn(17);
        when(client.getChannel()).thenReturn(1);
        when(client.isLoggedIn()).thenReturn(true);
        inventory = new Inventory(character, InventoryType.USE, (byte) 24);
        when(character.getInventory(InventoryType.USE)).thenReturn(inventory);
        when(items.getName(POTION)).thenReturn("Red Potion");
        when(items.getName(SHELL)).thenReturn("Snail Shell");
        when(items.getSlotMax(client, POTION)).thenReturn((short) 100);
        when(items.getSlotMax(client, SHELL)).thenReturn((short) 100);
        backend = new NativeDebugCommandBackend(accounts, characters, giveService, () -> server, () -> items);
    }

    @Test
    void nativeServiceReceivesOnlyTheConfiguredPositiveTargetAndFixedItemResourceType() {
        inventory.addItem(new Item(POTION, (short) 0, (short) 3));
        doAnswer(call -> {
            inventory.getItem((short) 1).setQuantity((short) 5);
            return null;
        }).when(giveService).give(any());

        var result = backend.give(profile, POTION, 2);

        assertEquals("SUCCEEDED", result.status());
        assertEquals(Boolean.TRUE, result.nativeDispatched());
        assertEquals(3L, result.quantityBefore());
        assertEquals(5L, result.quantityAfter());
        assertEquals("USE", result.inventoryType());
        assertEquals(41, result.characterId());
        assertEquals(0, result.world());
        ArgumentCaptor<GiveResourceReqDTO> captured = ArgumentCaptor.forClass(GiveResourceReqDTO.class);
        verify(giveService).give(captured.capture());
        assertEquals(41, captured.getValue().getPlayerId());
        assertEquals(0, captured.getValue().getWorldId());
        assertEquals((byte) 5, captured.getValue().getType());
        assertEquals(POTION, captured.getValue().getId());
        assertEquals(2, captured.getValue().getQuantity());
        assertNull(captured.getValue().getPlayer());
        assertNull(captured.getValue().getRate());
    }

    @Test
    void ordinaryStackableEtcItemUsesItsRealInventory() {
        Inventory etc = new Inventory(character, InventoryType.ETC, (byte) 24);
        when(character.getInventory(InventoryType.ETC)).thenReturn(etc);
        doAnswer(call -> {
            etc.addItem(new Item(SHELL, (short) 0, (short) 2));
            return null;
        }).when(giveService).give(any());

        var result = backend.give(profile, SHELL, 2);
        assertEquals("SUCCEEDED", result.status());
        assertEquals("ETC", result.inventoryType());
        assertEquals(0L, result.quantityBefore());
        assertEquals(2L, result.quantityAfter());
    }

    @Test
    void rejectsInvalidQuantitiesBeforeCallingNativeServices() {
        for (int quantity : new int[]{0, -1, Short.MAX_VALUE + 1}) {
            var result = backend.give(profile, POTION, quantity);
            assertEquals("FAILED", result.status());
            assertEquals(Boolean.FALSE, result.nativeDispatched());
            assertEquals("invalid_give_parameters", result.reason());
        }
        verifyNoInteractions(giveService, accounts, characters);
    }

    @Test
    void rejectsEquipmentPetsCashSetupAndRechargeableItems() {
        for (int itemId : new int[]{1_040_000, 5_000_000, 5_020_000, 3_010_000, 2_070_000, 2_330_000}) {
            var result = backend.give(profile, itemId, 1);
            assertEquals("FAILED", result.status(), Integer.toString(itemId));
            assertEquals(Boolean.FALSE, result.nativeDispatched());
            assertEquals("item_not_supported", result.reason());
        }
        verifyNoInteractions(giveService);
    }

    @Test
    void rejectsUnknownItemsAndSpecialNonOrdinaryItemData() {
        when(items.getName(POTION)).thenReturn(null);
        assertEquals("item_not_found", backend.give(profile, POTION, 1).reason());
        when(items.getName(POTION)).thenReturn("Red Potion");
        when(items.isCash(POTION)).thenReturn(true);
        assertEquals("item_not_supported", backend.give(profile, POTION, 1).reason());
        when(items.isCash(POTION)).thenReturn(false);
        when(items.isQuestItem(POTION)).thenReturn(true);
        assertEquals("item_not_supported", backend.give(profile, POTION, 1).reason());
        when(items.isQuestItem(POTION)).thenReturn(false);
        when(items.getSlotMax(client, POTION)).thenReturn((short) 1);
        assertEquals("item_not_supported", backend.give(profile, POTION, 1).reason());
        verifyNoInteractions(giveService);
    }

    @Test
    void rejectsAnAccountOrStoredCharacterOutsideTheConfiguredOrdinaryIdentity() {
        account.setWebadmin(1);
        assertEquals("profile_target_not_eligible", backend.give(profile, POTION, 1).reason());
        account.setWebadmin(0);
        account.setBanned(true);
        assertEquals("profile_target_not_eligible", backend.give(profile, POTION, 1).reason());
        account.setBanned(false);
        stored.setAccountid(999);
        assertEquals("profile_target_not_eligible", backend.give(profile, POTION, 1).reason());
        stored.setAccountid(17);
        stored.setWorld(1);
        assertEquals("profile_target_not_eligible", backend.give(profile, POTION, 1).reason());
        stored.setWorld(0);
        stored.setGm(1);
        assertEquals("profile_target_not_eligible", backend.give(profile, POTION, 1).reason());
        verifyNoInteractions(giveService);
    }

    @Test
    void rejectsOfflineOrReplacedNativeObjectsEvenWhenIdsMatch() {
        when(worldPlayers.getCharacterById(41)).thenReturn(null);
        assertEquals("profile_target_not_online", backend.give(profile, POTION, 1).reason());
        when(worldPlayers.getCharacterById(41)).thenReturn(character);
        when(channelPlayers.getCharacterById(41)).thenReturn(mock(Character.class));
        assertEquals("profile_target_not_online", backend.give(profile, POTION, 1).reason());
        when(channelPlayers.getCharacterById(41)).thenReturn(character);
        when(client.getAccID()).thenReturn(999);
        assertEquals("profile_target_not_online", backend.give(profile, POTION, 1).reason());
        when(client.getAccID()).thenReturn(17);
        when(client.isLoggedIn()).thenReturn(false);
        assertEquals("profile_target_not_online", backend.give(profile, POTION, 1).reason());
        verifyNoInteractions(giveService);
    }

    @Test
    void liveRoleNameMustStillMatchTheConfiguredAndPersistedIdentity() {
        when(character.getName()).thenReturn("OtherPlayer");
        var result = backend.give(profile, POTION, 1);
        assertEquals("FAILED", result.status());
        assertEquals(Boolean.FALSE, result.nativeDispatched());
        assertEquals("profile_target_not_online", result.reason());
        verifyNoInteractions(giveService);
    }

    @Test
    void rejectsAHeadlessBotOrLiveGmWithoutChangingPrivileges() {
        when(character.gmLevel()).thenReturn(1);
        assertEquals("profile_target_not_online", backend.give(profile, POTION, 1).reason());
        when(character.gmLevel()).thenReturn(0);
        BotClient bot = mock(BotClient.class);
        when(character.getClient()).thenReturn(bot);
        when(bot.getPlayer()).thenReturn(character);
        when(bot.getAccID()).thenReturn(17);
        when(bot.getChannel()).thenReturn(1);
        when(bot.isLoggedIn()).thenReturn(true);
        assertEquals("profile_target_not_online", backend.give(profile, POTION, 1).reason());
        verifyNoInteractions(giveService);
    }

    @Test
    void checksTheExactOnlineTargetAgainImmediatelyBeforeNativeDispatch() {
        when(worldPlayers.getCharacterById(41)).thenReturn(character, character, mock(Character.class));
        var result = backend.give(profile, POTION, 1);
        assertEquals("FAILED", result.status());
        assertEquals(Boolean.FALSE, result.nativeDispatched());
        assertEquals("profile_target_not_online", result.reason());
        verifyNoInteractions(giveService);
    }

    @Test
    void aVoidReturnWithoutInventoryChangeIsNotSuccess() {
        var result = backend.give(profile, POTION, 2);
        assertEquals("FAILED", result.status());
        assertEquals(Boolean.TRUE, result.nativeDispatched());
        assertEquals("inventory_full_or_no_change", result.reason());
        assertEquals(0L, result.quantityBefore());
        assertEquals(0L, result.quantityAfter());
    }

    @Test
    void partialNativeChangesRemainUnknownAndAreNotRepeated() {
        doAnswer(call -> {
            inventory.addItem(new Item(POTION, (short) 0, (short) 1));
            return null;
        }).when(giveService).give(any());
        var result = backend.give(profile, POTION, 2);
        assertEquals("UNKNOWN", result.status());
        assertEquals(Boolean.TRUE, result.nativeDispatched());
        assertEquals(0L, result.quantityBefore());
        assertEquals(1L, result.quantityAfter());
        verify(giveService, times(1)).give(any());
    }

    @Test
    void confirmedInventoryFactSurvivesAnExceptionAfterTheNativeEffect() {
        doAnswer(call -> {
            inventory.addItem(new Item(POTION, (short) 0, (short) 2));
            throw new IllegalStateException("private native error");
        }).when(giveService).give(any());
        var result = backend.give(profile, POTION, 2);
        assertEquals("SUCCEEDED", result.status());
        assertEquals("item_quantity_confirmed", result.reason());
        assertEquals(2L, result.quantityAfter());
        assertFalse(result.toString().contains("private native error"));
    }

    @Test
    void anExceptionAfterDispatchWithoutConfirmedEffectIsUnknown() {
        doThrow(new IllegalStateException("secret cause")).when(giveService).give(any());
        var result = backend.give(profile, POTION, 2);
        assertEquals("UNKNOWN", result.status());
        assertEquals(Boolean.TRUE, result.nativeDispatched());
        assertEquals("native_give_error", result.reason());
        assertFalse(result.toString().contains("secret cause"));
    }

    @Test
    void unreadablePostStateRetainsTheDispatchAndAvailableBeforeFact() {
        Inventory unreadable = mock(Inventory.class);
        when(character.getInventory(InventoryType.USE)).thenReturn(unreadable);
        when(unreadable.list()).thenReturn(List.of(new Item(POTION, (short) 1, (short) 3)))
                .thenThrow(new IllegalStateException("secret observation error"));
        var result = backend.give(profile, POTION, 2);
        assertEquals("UNKNOWN", result.status());
        assertEquals(Boolean.TRUE, result.nativeDispatched());
        assertEquals(3L, result.quantityBefore());
        assertNull(result.quantityAfter());
        verify(unreadable).unlockInventory();
        assertFalse(result.toString().contains("secret observation error"));
    }

    @Test
    void validationReadFailuresDoNotDispatchOrExposeTheirMessage() {
        when(accounts.findByName(anyString())).thenThrow(new IllegalStateException("secret account details"));
        var result = backend.give(profile, POTION, 2);
        assertEquals("FAILED", result.status());
        assertEquals(Boolean.FALSE, result.nativeDispatched());
        assertEquals("command_validation_unavailable", result.reason());
        verifyNoInteractions(giveService);
        assertFalse(result.toString().contains("secret account details"));
    }
}
