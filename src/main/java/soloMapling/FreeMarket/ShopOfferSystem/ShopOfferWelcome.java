package soloMapling.FreeMarket.ShopOfferSystem;

import org.gms.client.Character;
import org.gms.server.maps.PlayerShop;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.server.MethodScheduler;

import static soloMapling.ArtificialPlayer.BotHelpers.isBot;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ShopOfferWelcome {

    private static final String DIALOGUE_PATH = "ShopOfferDialogue.yaml";
    private static final String BOT_TYPE = "ShopOffer";
    private static final Random random = new Random();

    private static final double WELCOME_CHANCE = 0.60;
    private static final int HINT_AFTER_MESSAGES = 2;

    private static final Map<String, Integer> playerMessageCounts = new ConcurrentHashMap<>();
    private static final Set<String> hintedPlayers = ConcurrentHashMap.newKeySet();

    public static void onPlayerEnterShop(PlayerShop shop, Character visitor) {
        if (!isBot(shop.getOwner())) return;

        int ownerId = shop.getOwner().getId();
        ShopOfferSystem.ShopMode mode = ShopOfferSystem.getInstance().getOrAssignMode(ownerId);

        if (mode != ShopOfferSystem.ShopMode.PRESENT) return;
        if (random.nextDouble() >= WELCOME_CHANCE) return;

        int delay = 15000 + random.nextInt(5000);
        MethodScheduler.runAfterDelay(() -> {
            if (visitor.getPlayerShop() != shop) return;
            String line = getWelcomeLine();
            if (line != null) {
                shop.chat(shop.getOwner(), line);
            }
        }, delay);
    }

    public static void onPlayerChat(Character player, PlayerShop shop, boolean offerParsed) {
        if (offerParsed) return;
        if (!isBot(shop.getOwner())) return;

        int ownerId = shop.getOwner().getId();
        ShopOfferSystem.ShopMode mode = ShopOfferSystem.getInstance().getOrAssignMode(ownerId);
        if (mode != ShopOfferSystem.ShopMode.PRESENT) return;

        String key = ownerId + "_" + player.getId();
        if (hintedPlayers.contains(key)) return;

        int count = playerMessageCounts.merge(key, 1, Integer::sum);
        if (count >= HINT_AFTER_MESSAGES) {
            hintedPlayers.add(key);
            int delay = 2000 + random.nextInt(2000);
            MethodScheduler.runAfterDelay(() -> {
                if (player.getPlayerShop() != shop) return;
                String hint = getRandomLine("OfferHint");
                if (hint != null) {
                    shop.chat(shop.getOwner(), hint);
                }
            }, delay);
        }
    }

    public static void clearShopData(int ownerId) {
        playerMessageCounts.entrySet().removeIf(e -> e.getKey().startsWith(ownerId + "_"));
        hintedPlayers.removeIf(k -> k.startsWith(ownerId + "_"));
    }

    private static String getWelcomeLine() {
        return getRandomLine("WelcomeResponse");
    }

    private static String getRandomLine(String node) {
        BotDialogueHandler.DialogueConstructor dialog =
                BotDialogueHandler.getDialogueCon(DIALOGUE_PATH, BOT_TYPE, node);
        if (dialog == null || dialog.getDialogue().isEmpty()) return null;
        List<String> lines = dialog.getDialogue();
        return lines.get(random.nextInt(lines.size()));
    }
}
