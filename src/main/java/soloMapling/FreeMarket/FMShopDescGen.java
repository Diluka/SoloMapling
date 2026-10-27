package soloMapling.FreeMarket;

import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.server.ItemInformationProvider;
import org.gms.server.maps.PlayerShopItem;
import soloMapling.Environment.LocalizedResources;
import soloMapling.Environment.SoloResources;
import soloMapling.itemPool.ScrolledItemComparator;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class FMShopDescGen {

    static final String NAME_DESC_PARENT = "FreeMarket/";
    static final String NAME_DESC_PACK = "FMNameDesc";
    static List<String> topFMClans = new ArrayList<>();
    private static String topFMClansResource = "";

    protected static final Map<String, String> typeToFileName;

    static {
        typeToFileName = new HashMap<>();
        typeToFileName.put("ign", "randomRealMaplestoryIGNs.txt");
        typeToFileName.put("thief", "thiefDesc.txt");
        typeToFileName.put("warrior", "warriorDesc.txt");
        typeToFileName.put("mage", "mageDesc.txt");
        typeToFileName.put("bowman", "bowmanDesc.txt");
        typeToFileName.put("chair", "chairDesc.txt");
        typeToFileName.put("scrolls", "scrollsDesc.txt");
        typeToFileName.put("useable", "useableDesc.txt");
        typeToFileName.put("etc", "etcDesc.txt");
        typeToFileName.put("common", "commonDesc.txt");
        typeToFileName.put("fmclan", "FMClans.txt");
        typeToFileName.put("shortword", "shortWordDesc.txt");
        typeToFileName.put("emojis", "emojiFaces.txt");
        typeToFileName.put("offerable", "offerableDesc.txt");
        typeToFileName.put("welcome", "welcomeDesc.txt");
        typeToFileName.put("rwtcurrency", "rwtCurrencyDesc.txt");
    }

    protected static final Map<String, String> ITEM_ACRONYM_MAP = Map.ofEntries(
            // Gloves
            Map.entry("Brown Work Glove", "bwg"),
            Map.entry("Stormcaster Gloves", "scg"),

            // Accessories
            Map.entry("Pink Adventurer Cape", "pac"),
            Map.entry("Facestompers", "fs"),

            // Consumables
            Map.entry("Onyx Apple", "Apples")

            // Armor
    );

    protected static String modifyShopTypeSeperatorText(String currentStr) {
//        trimTertiaryShopDescription(merchant);
//        String currentDesc = merchant.getDescription();
        String replaceString = replaceSeperatorText(currentStr);
        return replaceString;
    }

    protected static String replaceSeperatorText(String str) {
        //        Equips&Stuff
//        White Scrolls
//        Apples&Scrolls
        boolean replaceText = Math.random() < 0.33;
        if (!replaceText) {
            return str;
        }

        // Randomly decide whether to replace with ' ' or '&'
        char replacementChar = new Random().nextBoolean() ? ' ' : '&';

        // Replace all '|' characters with the chosen character
        String replacedString = str.replace('|', replacementChar);
        return replacedString;
    }

    protected static String getRandomQuote() {
//        - meme/quote
//                - video game quotes / reference ("Trinkets, Odds and ends, that sort of thing" - Skyrim)
//                - anime quotes/reference ("Nah I'd Win")

        return null;
    }

    protected static String FMClanAdvertisement() {
        String fmClan = getRandomTopFMClan();
        fmClan = emblemizeFirstLetter(fmClan);

        if (Math.random() < 0.99) { // .7
            fmClan = asciiBorderString(fmClan, 19);
        }

        int width = displayWidth(fmClan);
        if (width < 14) { // Pad out with white space
            int spacesToAdd = 18 - width + 6;
            fmClan += " ".repeat(spacesToAdd);
        } else if (width < 18) { // Pad out with white space
            int spacesToAdd = 18 - width + 2;
            fmClan += " ".repeat(spacesToAdd);
        }
        return fmClan;
    }

    /** Rendered width in half-width cells, so CJK shop names aren't padded as if they were ASCII. */
    protected static int displayWidth(String str) {
        if (str == null) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < str.length(); i++) {
            width += Character.UnicodeBlock.of(str.charAt(i)) == Character.UnicodeBlock.BASIC_LATIN ? 1 : 2;
        }
        return width;
    }

    protected static String randomShortWordsPhrases() {
        String shortWord = getRandomStoreDescription("shortword");
        return shortWord;
    }

    protected static String emojiFaces() {
        String emoji = getRandomStoreDescription("emojis");
        return emoji;
    }

    protected static String cringeDescriptions() {
//        <3
//        I love him/Ilove her/I miss her
        return null;
    }

    protected static String guildRecruitment() {
        // R> Guild
        return null;
    }

    // Localized currency lists aren't guaranteed to be two-letter, so anything else passes through.
    protected static String transformTwoLetterString(String input) {
        if (input == null || input.length() != 2) {
            return input;
        }

        if (Math.random() < 0.5) { // 50% chance
            return input.substring(0, 1).toUpperCase() + input.substring(1, 2).toLowerCase();
        }
        return input; // Return the original string if the condition is not met
    }

    protected static String advertiseRWTWebsites() {
        return null;
    }

    protected static String advertiseRWTCurrencies() {
        List<String> rwtCurrencies = getStoreDescriptionLines("rwtcurrency");
        if (rwtCurrencies.isEmpty()) {
            return null;
        }

        Random random = new Random();
        int numberOfCurrencies = Math.min(random.nextInt(3) + 1, rwtCurrencies.size());

        StringBuilder result = new StringBuilder("|");
        for (int i = 0; i < numberOfCurrencies; i++) {
            result.append(transformTwoLetterString(rwtCurrencies.get(i))).append("|");
        }

        return result.toString();
    }

    protected static String getOfferableDescription() {
        return convertToLowerCaseWithChance(getRandomStoreDescription("offerable"));
    }

    /** Shop greeting line; {@code %OWNER%} is filled with the shop owner's IGN. */
    protected static String getWelcomeDescription(String owner) {
        return getRandomStoreDescription("welcome").replace("%OWNER%", owner);
    }

    protected static String convertToLowerCaseWithChance(String input) {
        if (Math.random() < 0.5) { // 50% chance
            return input.toLowerCase();
        }
        return input; // Return the original string if the chance condition is not met
    }


    protected static String itemNameAcronymConverter(String str) {
        return ITEM_ACRONYM_MAP.getOrDefault(str, str); // Return the acronym or the original string if not found
    }


    protected static String trimColorsFromEquipNames(String str) {
        // Define a list of common color names to remove
        List<String> colors = List.of(
                "Dark", "Red", "Blue", "Green", "White", "Black",
                "Purple", "Yellow", "Orange", "Pink", "Silver", "Gold", "Brown"
        );

        // Iterate through the list of colors and remove them from the string
        for (String color : colors) {
            if (str.startsWith(color + " ")) {
                return str.substring(color.length() + 1); // Remove the color and the following space
            }
        }

        return str;
    }


    protected static String trimTextFromItemNames(String str) {
        if (str == null || str.isEmpty()) {
            return str; // Return as is for null or empty input
        }

        // Define the list of words/phrases to remove
        List<String> substringsToRemove = List.of("Dark Scroll ", "Dark scroll ", "Scroll ", "for ", "[Mastery Book] ",
                "Throwing-Knives", "Throwing-Stars");

        // Define the list of special cases to preserve
        List<String> omitList = List.of("White Scroll", "Chaos Scroll");

        // Check if the string contains any omit list item
        for (String omit : omitList) {
            if (str.contains(omit)) {
                return str; // Return original string if it matches any omit condition
            }
        }
        // Iterate over each substring and remove it from the input string
        for (String substring : substringsToRemove) {
            str = str.replace(substring, "").trim();
        }

        return str.trim();
    }

    protected static String advertiseBestEquip(Item bestItem, boolean writeStat) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        int itemId = bestItem.getItemId();
        String itemName = ii.getName(itemId);
        itemName = itemNameAcronymConverter(itemName);
        itemName = trimColorsFromEquipNames(itemName);

        ScrolledItemComparator bestEq = new ScrolledItemComparator((Equip) bestItem);
        int numSuccessfulScrolls = ((Equip) bestItem).getLevel();
        String bestStatName = bestEq.getHighestStatType();
        int highestStatValue = bestEq.getHighestStatValue();

        if (numSuccessfulScrolls != 0) {
            if (writeStat) {
                return (highestStatValue + " " + bestStatName + " " + itemName);
            } else {
                return ("Godly" + " " + itemName);
            }
        } else {
            return ("clean " + itemName);
        }
    }

    protected static String getMostExpensiveItemName(HiredMerchantArtificial merchant) {
        String itemName = "";
        int maxValue = 0;
        int mostExpensiveItemId = 0;
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (PlayerShopItem psItem : merchant.getItems()) {
            if (psItem.getItem().getInventoryType() != InventoryType.EQUIP) {
                if (psItem.getPrice() > maxValue) {
                    maxValue = psItem.getPrice();
                    mostExpensiveItemId = psItem.getItem().getItemId();
                }
            }
        }
        itemName = ii.getName(mostExpensiveItemId);
        return trimTextFromItemNames(itemName);
    }

    protected static String advertiseBestEquip(Item bestItem) {
        boolean displayStat = Math.random() < 0.5;
        return advertiseBestEquip(bestItem, displayStat);
    }

    protected static Item getMostExpensiveEquipFromShop(HiredMerchantArtificial merchant) {
        String itemName = "";
        int maxValue = 0;
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Item mostExpensiveItem = null;
        for (PlayerShopItem psItem : merchant.getItems()) {
            if (psItem.getItem().getInventoryType() == InventoryType.EQUIP) {
                if (psItem.getPrice() > maxValue) {
                    maxValue = psItem.getPrice();
                    mostExpensiveItem = psItem.getItem();
                }
            }
        }
        return mostExpensiveItem;
    }


    protected static synchronized String getRandomTopFMClan() {
        String resource = resolveFilePath("fmclan");
        if (!resource.equals(topFMClansResource)) {
            topFMClans.clear();
            topFMClansResource = resource;
        }
        if (topFMClans.isEmpty()) {
            for (int i = 0; i < 7; i++) {
                topFMClans.add(getRandomStoreDescription("fmclan"));
            }
        }
        Random random = new Random();
        int randomIndex = random.nextInt(topFMClans.size());
        return topFMClans.get(randomIndex);
    }

    private static List<String> namePool;
    private static int namePoolIndex = 0;
    private static final List<String> assignedCharacterNames = new ArrayList<>();

    /**
     * Draws a unique name from the pool and registers it as a bot character name.
     * Use this when spawning bot characters.
     */
    public static synchronized String getRandomCharacterIGN() {
        String name = getRandomIGN();
        assignedCharacterNames.add(name);
        return name;
    }

    /**
     * Gets a name for a shop owner. ~35% chance to pick from existing bot character
     * names (simulates an online player's shop), otherwise draws a fresh unique name
     * (simulates an offline player's shop).
     */
    public static synchronized String getRandomShopOwnerIGN() {
        Random rand = new Random();
        if (!assignedCharacterNames.isEmpty() && rand.nextInt(100) < 35) {
            return assignedCharacterNames.get(rand.nextInt(assignedCharacterNames.size()));
        }
        return getRandomIGN();
    }

    /**
     * Core pool draw — hands out one unique name per call. Reloads and reshuffles
     * only when the entire pool is exhausted.
     */
    public static synchronized String getRandomIGN() {
        if (namePool == null || namePoolIndex >= namePool.size()) {
            namePool = loadAndShuffleNames();
            namePoolIndex = 0;
        }
        return namePool.get(namePoolIndex++);
    }

    private static List<String> loadAndShuffleNames() {
        String filePath = resolveFilePath("ign");
        List<String> names = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(SoloResources.openReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty() && line.length() <= 12) {
                    names.add(line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        Collections.shuffle(names);
        return names;
    }

    /** Localized word list first ({@code FMNameDesc-zh-CN/}), English list as fallback. */
    protected static String resolveFilePath(String type) {
        String fileName = typeToFileName.get(type);
        if (fileName == null) {
            return "";
        }
        String resolved = LocalizedResources.resolve(NAME_DESC_PARENT, NAME_DESC_PACK, fileName);
        return resolved != null ? resolved : "";
    }

    /** Whole word list in file order; empty when the list is missing or unreadable. */
    protected static List<String> getStoreDescriptionLines(String type) {
        String filePath = resolveFilePath(type);
        if (filePath.isEmpty()) {
            System.err.println("[FMShopDescGen] no word list for type: " + type);
            return List.of();
        }

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(SoloResources.openReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return lines;
    }

    protected static String getRandomStoreDescription(String type) {
        String filePath = resolveFilePath(type);
        if (filePath.isEmpty()) {
            System.err.println("[FMShopDescGen] no word list for type: " + type);
            return "null";
        }

        try (BufferedReader reader = new BufferedReader(SoloResources.openReader(filePath))) {
            String line;
            String result = null;
            Random random = new Random();
            int count = 0;

            while ((line = reader.readLine()) != null) {
                count++;
                if (random.nextInt(count) == 0) {
                    result = line;
                }
            }

            if (result == null) {
                throw new IOException("File is empty: " + filePath);
            }

            return result;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "null";
    }

    protected static String emblemizeFirstLetter(String str) {
        if (str == null || str.isEmpty()) {
            return str; // Return as is if the string is null or empty
        }
        // Surround the first letter with brackets and append the rest of the string
        return "[" + str.charAt(0) + "]" + str.substring(1);
    }

    protected static String asciiBorderString(String str, int maxLineLength) {
        if (str == null || displayWidth(str) > maxLineLength - 2) {
            throw new IllegalArgumentException("String must be non-null and fit within the max line length with borders.");
        }

        int maxBorderStyleLength = (maxLineLength - displayWidth(str)) / 2;
        String borderStyle = selectRandomAsciiBorderCharacters(maxBorderStyleLength);

        return borderStyle + str + reverseString(borderStyle);
    }

    protected static String selectRandomAsciiBorderCharacters(int maxBorderStyleLength) {
        List<String> borderStyles = new ArrayList<>(List.of("-", "~", "~~", ".:", "*"));
        borderStyles.add("--");
//        borderStyles.add("==");
        borderStyles.add("++");
        borderStyles.add("'~.");

        // Filter the list to only include styles within the length constraint
        List<String> filteredStyles = new ArrayList<>();
        for (String style : borderStyles) {
            if (style.length() <= maxBorderStyleLength) {
                filteredStyles.add(style);
            }
        }

        // If no styles meet the length condition, return an empty string or a default value
        if (filteredStyles.isEmpty()) {
            return ""; // Or a default style like "-" or "N/A"
        }

        // Select a random border style from the filtered list
        Random random = new Random();
        int randomIndex = random.nextInt(filteredStyles.size());
        return filteredStyles.get(randomIndex);
    }

    protected static String reverseString(String str) {
        if (str == null) {
            return null; // Handle null input gracefully
        }
        return new StringBuilder(str).reverse().toString();
    }

    protected static String addSpacesInbetweenLetters(String str) {
        if (str == null || str.length() > 6) {
            return str;
        }

        StringBuilder spacedString = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            spacedString.append(str.charAt(i));
            if (i < str.length() - 1) {
                spacedString.append(" "); // Add a space between characters
            }
        }

        return spacedString.toString().toUpperCase();
    }

}
