package soloMapling.ArtificialPlayer.BotTradeSystem;

import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Inventory;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.manipulator.InventoryManipulator;
import org.gms.client.inventory.manipulator.KarmaManipulator;
import org.gms.config.GameConfig;
import org.gms.server.Trade;
import org.gms.server.ItemInformationProvider;
import org.gms.util.PacketCreator;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.beidou.BotRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static soloMapling.ArtificialPlayer.BotLogic.generateCleanItemEquip;
import static soloMapling.ArtificialPlayer.BotLogic.generateCleanItemWithQty;
import static soloMapling.DebugUtilities.debugprint;

public class BotTradeCommands {

    private static boolean isValidTrade(Character fakechar) {
        return fakechar.getTrade() != null;
    }

    public static void startTradeRequest(Character fakechar) {
        Trade.startTrade(fakechar);
    }

    public static void sendTradeRequestToPlayer(Character fakechar, Character invitee) {
        startTradeRequest(fakechar);
        Trade.inviteTrade(fakechar, invitee);
    }

    public static void acceptTradeInvite(Character fakechar) {
        if (isValidTrade(fakechar)) {
            Trade.visitTrade(fakechar, getTradePartnerCharacter(fakechar));
        }
    }

    public static void declineTradeInvite(Character fakechar) {
        try {
            Trade.declineTrade(fakechar);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void readTradeChat() {
        // Read text in trade window
//        todo
    }

    public static void writeTradeChat(Character fakechar, String message) {
        if (isValidTrade(fakechar)) {
            fakechar.getTrade().chat(message);
        }
    }

    public static void setMeso(Character fakechar, int amount) {
        // Bot sets, not add (for easy modification)
        if (amount < 0) {
            throw new IllegalArgumentException("setMeso must be positive amount or less than max meso");
        }
        if (!BotRegistry.isRegistered(fakechar)) {
            throw new IllegalArgumentException("Only registered SoloMapling bots may prepare trade funds");
        }
        Trade trade = fakechar.getTrade();
        if (trade != null) {
            Trade partner = trade.getPartner();
            Trade handshake = partner == null || fakechar.getId() < partner.getChr().getId()
                    ? trade : partner;
            synchronized (handshake) {
                if (fakechar.getTrade() != trade || trade.soloIsLocked()) return;
                int needed = Math.max(0, amount - trade.soloGetMeso());
                int deficit = needed - fakechar.getMeso();
                if (deficit > 0) fakechar.gainMeso(deficit, false, true, false);
                trade.setMesoBot(amount);
            }
        }
    }

    public static int readMeso(Character fakechar) {
        if (isValidTrade(fakechar)) {
            int botMeso = fakechar.getTrade().soloGetMeso();
            debugprint(botMeso);
            return botMeso;
        }
        return 0;
    }

    public static int readPartnerMeso(Character fakechar) {
        if (isValidTrade(fakechar)) {
            int partnerMeso = getTradePartner(fakechar).soloGetMeso();
            debugprint(partnerMeso);
            return partnerMeso;
        }
        return 0;
    }

    public static List<Integer> getOccupiedTradeSlots(Character fakechar) {
        if (isValidTrade(fakechar)) {
            List<Integer> occupiedPositions = new ArrayList<>();
            List<Item> botItems = fakechar.getTrade().getItems();
            for (Item item : botItems) {
                occupiedPositions.add((int) item.getPosition());
            }
            debugprint(occupiedPositions);
            return occupiedPositions;
        }
        return List.of();
    }

    public static List<Integer> getEmptyTradeSlots(Character fakechar) {
        if (isValidTrade(fakechar)) {
            List<Integer> emptySlots = new ArrayList<>(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9));
            List<Integer> occupiedSlots = getOccupiedTradeSlots(fakechar);
            if (occupiedSlots == null) {
                return emptySlots;
            }
            emptySlots.removeAll(occupiedSlots);
            debugprint(emptySlots);
            return emptySlots;
        }
        return List.of();
    }

    public static Integer getRandomEmptySlot(Character fakechar) {
        List<Integer> emptySlots = getEmptyTradeSlots(fakechar);

        if (emptySlots == null || emptySlots.isEmpty()) {
            return null; // No empty slots available
        }

        int randomIndex = (int) (Math.random() * emptySlots.size());
        return emptySlots.get(randomIndex);
    }

    public static boolean addEquipToTrade(Character fakechar, Equip equip, int slot) {
        if (validateTradeParams(fakechar, slot) && equip != null) {
            return addItemToTradeWindow(fakechar, equip, slot);
        }
        return false;
    }

    public static boolean addCleanEquipToTrade(Character fakechar, int equipId, int slot) {
        if (validateTradeParams(fakechar, slot)) {
            Equip tradeItem = (Equip) generateCleanItemEquip(equipId);
            return addEquipToTrade(fakechar, tradeItem, slot);
        }
        return false;
    }

    public static boolean addItemToTrade(Character fakechar, int itemId, int qty, int slot) {
        if (validateTradeParams(fakechar, slot)) {
            if (qty < 1 || qty > Short.MAX_VALUE) return false;
            BotSM bot = CharacterStorage.getBotById(fakechar.getId());
            Item advertised = bot == null ? null : bot.getTradeInventory().getMainItemForSale();
            Item tradeItem = advertised != null && advertised.getItemId() == itemId
                    ? advertised : generateCleanItemWithQty(itemId, qty);
            return reserveItem(fakechar, tradeItem, (short) qty, slot, false);
        }
        return false;
    }

    public static boolean swapScamEquipToTrade(Character fakechar, int equipId, int slot) {
        if (isConfirmed(fakechar)) {
            return false;
        }
        if (validateTradeParams(fakechar, slot)) {
            Equip tradeItem = (Equip) generateCleanItemEquip(equipId);
            return swapItemToTradeWindow(fakechar, tradeItem, slot);
        }
        return false;
    }

    public static boolean swapScamItemToTrade(Character fakechar, int itemId, int qty, int slot) {
        if (qty < 1 || qty > Short.MAX_VALUE) return false;
        if (isConfirmed(fakechar)) {
            return false;
        }
        if (validateTradeParams(fakechar, slot)) {
            Item tradeItem = generateCleanItemWithQty(itemId, qty);
            return swapItemToTradeWindow(fakechar, tradeItem, slot);
        }
        return false;
    }

    private static boolean validateTradeParams(Character fakechar, int slot) {
        if (fakechar == null || fakechar.getTrade() == null || fakechar.getTrade().soloIsLocked()) {
            return false;
        }
        if (slot < 1 || slot > 9) {
            throw new IllegalArgumentException("slot must be 1-9.");
        }
        return true;
    }

    private static boolean addItemToTradeWindow(Character fakechar, Item tradeItem, int slot) {
        return reserveItem(fakechar, tradeItem, tradeItem.getQuantity(), slot, false);
    }

    private static boolean swapItemToTradeWindow(Character fakechar, Item tradeItem, int slot) {
        return reserveItem(fakechar, tradeItem, tradeItem.getQuantity(), slot, true);
    }

    private static boolean reserveItem(Character botCharacter, Item advertised, short quantity,
                                       int slot, boolean replace) {
        if (advertised == null || quantity <= 0 || !validateTradeParams(botCharacter, slot)) return false;
        BotSM bot = CharacterStorage.getBotById(botCharacter.getId());
        if (bot == null || !BotRegistry.isRegistered(botCharacter)) return false;
        Trade trade = botCharacter.getTrade();
        Trade partner = trade.getPartner();
        // Native completion and cancellation synchronize on the lower character ID's trade.
        Trade handshake = partner == null || botCharacter.getId() < partner.getChr().getId()
                ? trade : partner;
        synchronized (handshake) {
            if (botCharacter.getTrade() != trade || trade.soloIsLocked()) return false;
            ItemInformationProvider items = ItemInformationProvider.getInstance();
            if (!GameConfig.getServerBoolean("trade_limit_item_cash") && items.isUnmerchable(advertised.getItemId())) return false;
            if (!GameConfig.getServerBoolean("trade_limit_item_nodrop") && items.isDropRestricted(advertised.getItemId())
                    && !KarmaManipulator.hasKarmaFlag(advertised)) return false;
            Item previous = readItemInLocalSlot(botCharacter, slot);
            if ((!replace && previous != null) || (replace && previous == null)) return false;
            Inventory inventory = botCharacter.getInventory(advertised.getInventoryType());
            Inventory returnedInventory = previous == null ? inventory
                    : botCharacter.getInventory(previous.getInventoryType());
            Inventory first = inventory.getType().ordinal() <= returnedInventory.getType().ordinal()
                    ? inventory : returnedInventory;
            Inventory second = first == inventory ? returnedInventory : inventory;
            first.lockInventory();
            if (second != first) second.lockInventory();
            try {
                if (previous != null && !InventoryManipulator.checkSpace(botCharacter.getClient(),
                        previous.getItemId(), previous.getQuantity(), previous.getOwner())) return false;
                Item owned = bot.getTradeInventory().stockForTrade(botCharacter, advertised, quantity);
                if (owned == null) return false;
                Item reserved = owned.copy();
                reserved.setQuantity(quantity);
                reserved.setPosition((short) slot);
                InventoryManipulator.removeFromSlot(botCharacter.getClient(), owned.getInventoryType(),
                        owned.getPosition(), quantity, true);
                bot.getTradeInventory().recordReservation(advertised, owned, reserved);
                boolean accepted;
                try {
                    accepted = replace ? trade.swapItem(reserved) : trade.addItem(reserved);
                } catch (RuntimeException failure) {
                    if (!InventoryManipulator.addFromDrop(botCharacter.getClient(), reserved, false)) {
                        failure.addSuppressed(new IllegalStateException("Could not roll back bot trade reservation"));
                    }
                    throw failure;
                }
                if (!accepted) {
                    if (!InventoryManipulator.addFromDrop(botCharacter.getClient(), reserved, false)) {
                        throw new IllegalStateException("Could not roll back rejected bot trade reservation");
                    }
                    return false;
                }
                if (previous != null && !InventoryManipulator.addFromDrop(botCharacter.getClient(), previous, false)) {
                    throw new IllegalStateException("Could not return replaced bot trade stock");
                }
                botCharacter.sendPacket(PacketCreator.getTradeItemAdd((byte) 0, reserved));
                if (partner != null) partner.getChr().sendPacket(PacketCreator.getTradeItemAdd((byte) 1, reserved));
                return true;
            } finally {
                if (second != first) second.unlockInventory();
                first.unlockInventory();
            }
        }
    }

    public static Item readItemInLocalSlot(Character fakechar, int slot) {
        List<Item> localItems = getLocalItems(fakechar);
        if (localItems == null) {
            throw new IllegalStateException("Local's items list is null.");
        }
        for (Item item : localItems) {
            if (item.getPosition() == slot) {
                return item;
            }
        }
        return null;
    }

    public static List<Item> getLocalItems(Character fakechar) {
        if (isValidTrade(fakechar)) {
            return fakechar.getTrade().getItems();
        }
        return null;
    }

    public static List<Item> getPartnersItems(Character fakechar) {
        if (isValidTrade(fakechar)) {
            return getTradePartner(fakechar).getItems();
        }
        return null;
    }

    public static Item readItemInPartnerSlot(Character fakechar, int slot) {
        List<Item> partnerItems = getPartnersItems(fakechar);
        if (partnerItems == null) {
            throw new IllegalStateException("Partner's items list is null.");
        }
        for (Item item : partnerItems) {
            if (item.getPosition() == slot) {
                return item;
            }
        }
        return null;
    }

    public static boolean isPartnerLocked(Character fakechar) {
        if (isValidTrade(fakechar)) {
            boolean partnerLocked = getTradePartner(fakechar).soloIsLocked();
            debugprint(partnerLocked);
            return partnerLocked;
        }
        return false;
    }

    public static void confirmTrade(Character fakechar) {
        if (isValidTrade(fakechar)) {
            Trade.completeTrade(fakechar);
        }
    }

    public static boolean isConfirmed(Character fakechar) {
        if (isValidTrade(fakechar)) {
            return fakechar.getTrade().soloIsLocked();
        }
        return false;
    }

    public static void cancelTrade(Character fakechar) {
        Trade.declineTrade(fakechar);
    }

    public static Trade getTradePartner(Character fakechar) {
        return fakechar.getTrade().getPartner();
    }

    public static Character getTradePartnerCharacter(Character fakechar) {
        return getTradePartner(fakechar).getChr();
    }

    // Flavor methods

    public static int readPartnerFame(Character fakechar) {
        if (isValidTrade(fakechar)) {
            int fame = getTradePartnerCharacter(fakechar).getFame();
            debugprint(fame);
            return fame;
        }
        return 0;
    }

    public static int readPartnerLevel(Character fakechar) {
        if (isValidTrade(fakechar)) {
            int level = getTradePartnerCharacter(fakechar).getLevel();
            debugprint(level);
            return level;
        }
        return 0;
    }

}
