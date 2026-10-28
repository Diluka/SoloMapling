package org.gms.soloacceptance.command;

import org.gms.client.Character;
import org.gms.client.Client;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.constants.inventory.ItemConstants;
import org.gms.dao.entity.AccountsDO;
import org.gms.dao.entity.CharactersDO;
import org.gms.model.dto.GiveResourceReqDTO;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.world.World;
import org.gms.server.ItemInformationProvider;
import org.gms.service.AccountService;
import org.gms.service.CharacterService;
import org.gms.service.GiveService;
import org.gms.soloacceptance.actor.ActorProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import soloMapling.beidou.BotRegistry;

import java.util.Objects;
import java.util.function.Supplier;

/** A native management adapter; it neither owns a role nor schedules role actions. */
@Component
@ConditionalOnProperty(prefix = "solomapling.acceptance", name = {
        "enabled", "actors-enabled", "debug-commands-enabled"}, havingValue = "true", matchIfMissing = false)
public final class NativeDebugCommandBackend implements DebugCommandService.Backend {
    private final AccountService accounts;
    private final CharacterService characters;
    private final GiveService giveService;
    private final Supplier<Server> servers;
    private final Supplier<ItemInformationProvider> items;

    @Autowired
    public NativeDebugCommandBackend(AccountService accounts, CharacterService characters, GiveService giveService) {
        this(accounts, characters, giveService, Server::getInstance, ItemInformationProvider::getInstance);
    }

    NativeDebugCommandBackend(AccountService accounts, CharacterService characters, GiveService giveService,
                              Supplier<Server> servers, Supplier<ItemInformationProvider> items) {
        this.accounts = accounts;
        this.characters = characters;
        this.giveService = giveService;
        this.servers = servers;
        this.items = items;
    }

    @Override
    public DebugCommandService.Outcome give(ActorProfiles.Resolved profile, int itemId, int quantity) {
        if (itemId <= 0 || quantity < 1 || quantity > Short.MAX_VALUE) {
            return failed("invalid_give_parameters");
        }
        Target target;
        Inventory inventory;
        InventoryType type = ItemConstants.getInventoryType(itemId);
        try {
            target = target(profile);
            ItemInformationProvider ii = items.get();
            if (type != InventoryType.USE && type != InventoryType.ETC
                    || ItemConstants.isPet(itemId) || ItemConstants.isRechargeable(itemId)) {
                return failed("item_not_supported");
            }
            if (ii.getName(itemId) == null) return failed("item_not_found");
            if (ii.isCash(itemId) || ii.isQuestItem(itemId) || ii.getSlotMax(target.client(), itemId) <= 1) {
                return failed("item_not_supported");
            }
            inventory = target.character().getInventory(type);
            if (inventory == null) return failed("inventory_unavailable");
        } catch (Rejected rejected) {
            return failed(rejected.reason);
        } catch (RuntimeException ignored) {
            return failed("command_validation_unavailable");
        }

        // This is the very same reentrant inventory lock used by the native addById.
        // It bounds evidence collection without introducing another scheduler or lock.
        inventory.lockInventory();
        try {
            Long before;
            try {
                requireCurrent(target);
                before = quantity(inventory, itemId);
            } catch (Rejected rejected) {
                return failed(rejected.reason);
            } catch (RuntimeException ignored) {
                return failed("inventory_observation_unavailable");
            }

            GiveResourceReqDTO request = new GiveResourceReqDTO();
            request.setWorldId(target.world().getId());
            request.setPlayerId(target.character().getId());
            request.setType((byte) 5);
            request.setId(itemId);
            request.setQuantity(quantity);
            boolean nativeError = false;
            try {
                giveService.give(request);
            } catch (RuntimeException ignored) {
                nativeError = true;
            }
            Long after = null;
            try {
                after = quantity(inventory, itemId);
            } catch (RuntimeException ignored) {
                // Dispatched writes are never replayable merely because evidence failed.
            }
            if (after != null && after - before == quantity) {
                return outcome(target, type, "SUCCEEDED", true, "item_quantity_confirmed", before, after);
            }
            if (after != null && after.equals(before) && !nativeError) {
                return outcome(target, type, "FAILED", true, "inventory_full_or_no_change", before, after);
            }
            return outcome(target, type, "UNKNOWN", true,
                    after == null ? "inventory_observation_unavailable"
                            : after.equals(before) ? "native_give_error" : "inventory_partial_or_unexpected_change",
                    before, after);
        } finally {
            inventory.unlockInventory();
        }
    }

    private Target target(ActorProfiles.Resolved profile) {
        if (profile == null) throw new Rejected("profile_not_configured");
        AccountsDO account = accounts.findByName(profile.accountName());
        CharactersDO stored = characters.findByName(profile.characterName());
        if (account == null || account.getId() == null || account.getId() <= 0
                || !Objects.equals(profile.accountName(), account.getName())
                || !Objects.equals(account.getWebadmin(), 0) || Boolean.TRUE.equals(account.getBanned())
                || stored == null || stored.getId() == null || stored.getId() <= 0
                || !Objects.equals(stored.getAccountid(), account.getId())
                || !Objects.equals(stored.getName(), profile.characterName())
                || !Objects.equals(stored.getWorld(), profile.world()) || !Objects.equals(stored.getGm(), 0)) {
            throw new Rejected("profile_target_not_eligible");
        }
        Server server = servers.get();
        if (server == null || !server.isOnline()) throw new Rejected("server_offline");
        World world = server.getWorld(profile.world());
        Character character = world == null ? null : world.getPlayerStorage().getCharacterById(stored.getId());
        Client client = character == null ? null : character.getClient();
        Channel channel = client == null || world == null ? null : world.getChannel(client.getChannel());
        Target target = new Target(server, world, channel, character, client, account.getId(), stored.getId(),
                profile.characterName());
        requireCurrent(target);
        return target;
    }

    private void requireCurrent(Target target) {
        Character character = target.character();
        Client client = target.client();
        World world = target.world();
        Channel channel = target.channel();
        if (character == null || client == null || world == null || channel == null
                || servers.get() != target.server() || !target.server().isOnline()
                || target.server().getWorld(world.getId()) != world
                || world.getChannel(channel.getId()) != channel
                || world.getPlayerStorage().getCharacterById(target.characterId()) != character
                || channel.getPlayerStorage().getCharacterById(target.characterId()) != character
                || client.getPlayer() != character || character.getClient() != client
                || character.getId() != target.characterId() || character.getAccountId() != target.accountId()
                || !Objects.equals(character.getName(), target.characterName())
                || client.getAccID() != target.accountId() || character.getWorld() != world.getId()
                || client.getChannel() != channel.getId() || !client.isLoggedIn() || !character.isLoggedInWorld()
                || character.gmLevel() != 0 || BotRegistry.isBot(character)) {
            throw new Rejected("profile_target_not_online");
        }
    }

    private static long quantity(Inventory inventory, int itemId) {
        long total = 0;
        for (Item item : inventory.list()) {
            if (item.getItemId() == itemId) {
                if (item.getPosition() <= 0 || item.getQuantity() < 0) {
                    throw new IllegalStateException("invalid inventory evidence");
                }
                total += item.getQuantity();
            }
        }
        return total;
    }

    private static DebugCommandService.Outcome failed(String reason) {
        return new DebugCommandService.Outcome("FAILED", false, reason, null, null, null, null, null);
    }

    private static DebugCommandService.Outcome outcome(Target target, InventoryType type, String status,
                                                       boolean dispatched, String reason, Long before, Long after) {
        return new DebugCommandService.Outcome(status, dispatched, reason, before, after, type.name(),
                target.characterId(), target.world().getId());
    }

    private record Target(Server server, World world, Channel channel, Character character, Client client,
                          int accountId, int characterId, String characterName) { }

    private static final class Rejected extends RuntimeException {
        final String reason;
        Rejected(String reason) { this.reason = reason; }
    }
}
