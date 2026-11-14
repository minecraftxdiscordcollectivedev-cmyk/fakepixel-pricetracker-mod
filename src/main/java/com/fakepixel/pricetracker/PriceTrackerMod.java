package com.fakepixel.pricetracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core of the mod: price database, settings, alerts, key bind, admin controls and the /prices command.
 */
@Mod(modid = PriceTrackerMod.MODID, version = PriceTrackerMod.VERSION, clientSideOnly = true)
public class PriceTrackerMod {
    public static final String MODID = "pricetracker";
    public static final String VERSION = "3.1";

    // Lowercase Minecraft usernames allowed to use /prices clearah and /prices clearall.
    // PUT YOUR OWN USERNAME HERE.
    private static final Set<String> ADMIN_USERS = new HashSet<String>(Arrays.asList(
            "..."
    ));

    // ---------- settings ----------
    public static volatile boolean tooltipsEnabled = true;
    public static volatile boolean chestCalcEnabled = true;
    public static volatile boolean scanEnabled = true;
    public static volatile boolean SHOW_SCAN_CHAT = false;
    public static volatile boolean alertSound = true;

    // ---------- scanner state ----------
    public static volatile boolean scanning = false;
    public static volatile long lastScan = 0;

    // ---------- data ----------
    public static final Map<String, ItemPriceData> PRICE_DB = new ConcurrentHashMap<String, ItemPriceData>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_HISTORY = 60;
    private static File dataFile;
    private static File settingsFile;
    private static volatile boolean dirty = false;

    private static final Set<String> PET_NAMES = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final ConcurrentLinkedQueue<String> PENDING_CHAT = new ConcurrentLinkedQueue<String>();
    private static final Set<String> JUNK = new HashSet<String>(Arrays.asList(
            "buy instantly", "sell instantly", "create buy order", "create sell order", "buy order", "sell order",
            "go back", "close", "next page", "previous page", "search", "sort", "filter", "view bids",
            "claim", "collect", "manage orders", "sell inventory now", "sell sacks now", "instant sell",
            "instant buy", "bazaar orders", "auction house", "browse auctions", "view auctions"));

    // Only real reforges that never start a normal item name. Ordinary words (Epic, Perfect, Fine, Giant...)
    // are deliberately NOT here, they used to merge different items together.
    private static final String[] REFORGES = {
            "Fabled ", "Withered ", "Heroic ", "Spicy ", "Fierce ", "Necrotic ", "Ancient ", "Renowned ",
            "Loving ", "Spiritual ", "Precise ", "Hasty ", "Suspicious ", "Gilded "
    };

    private static KeyBinding openKey;
    private static boolean openGuiNext = false;
    private static int saveTicks = 0;

    // =====================================================================
    //  Data class
    // =====================================================================
    public static class ItemPriceData {
        public String name = "";
        public String source = "AH";
        public String category = "Other";
        public double lowestBin;
        public double highestBin;
        public double sellPrice;
        public double avgPrice;
        public List<Double> history = new ArrayList<Double>();
        public long lastSeen;

        public boolean watched;
        public double alertAbove;
        public double alertBelow;
        public boolean firedAbove;
        public boolean firedBelow;
    }

    // =====================================================================
    //  Admin
    // =====================================================================
    public static boolean isModAdmin() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSession() == null || mc.getSession().getUsername() == null) return false;
        return ADMIN_USERS.contains(mc.getSession().getUsername().toLowerCase().trim());
    }

    // =====================================================================
    //  Forge lifecycle
    // =====================================================================
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File configDir = new File(event.getModConfigurationDirectory(), "fakepixel_pricetracker");
        if (!configDir.exists()) configDir.mkdirs();
        dataFile = new File(configDir, "prices.json");
        settingsFile = new File(configDir, "settings.json");
        loadSettings();
        loadPrices();
        PriceSync.loadConfig(configDir);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() { if (dirty) savePrices(); }
        });
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new MarketScanner());
        MinecraftForge.EVENT_BUS.register(new TooltipHandler());
        MinecraftForge.EVENT_BUS.register(new DungeonChestCalculator());

        openKey = new KeyBinding("Open Price Tracker", Keyboard.KEY_P, "Price Tracker");
        ClientRegistry.registerKeyBinding(openKey);

        ClientCommandHandler.instance.registerCommand(new CommandBase() {
            public String getCommandName() { return "prices"; }
            public String getCommandUsage(ICommandSender sender) { return "/prices [gui | clearah | clearall | purge]"; }
            public int getRequiredPermissionLevel() { return 0; }
            public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

            public void processCommand(ICommandSender sender, String[] args) {
                if (args.length > 0) {
                    String sub = args[0].toLowerCase();
                    if ("clearah".equals(sub) || "clearall".equals(sub) || "purge".equals(sub)) {
                        if (!isModAdmin()) {
                            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                                    + "[PriceTracker] Access denied: admin only."));
                            return;
                        }
                        int removed;
                        if ("clearah".equals(sub)) removed = clearAhPrices();
                        else if ("clearall".equals(sub)) removed = clearAllPrices();
                        else removed = purgeJunk();
                        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + "[PriceTracker] "
                                + EnumChatFormatting.GREEN + "Done. Removed " + removed + " entries."));
                        return;
                    }
                }
                openGuiNext = true;
            }
        });

        LocalWebServer.start(8080);
        PriceSync.start();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();

        if (mc.thePlayer != null && !PENDING_CHAT.isEmpty()) {
            String msg;
            while ((msg = PENDING_CHAT.poll()) != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText(msg));
                if (alertSound) mc.thePlayer.playSound("random.orb", 1.0F, 1.0F);
            }
        }

        boolean pressed = false;
        while (openKey != null && openKey.isPressed()) pressed = true;
        if (mc.currentScreen == null && mc.thePlayer != null && (pressed || openGuiNext)) {
            openGuiNext = false;
            mc.displayGuiScreen(new PriceGui());
        }

        if (++saveTicks >= 600) {
            saveTicks = 0;
            if (dirty) savePrices();
        }
    }

    // =====================================================================
    //  Admin clear controls
    // =====================================================================
    public static int clearAhPrices() {
        if (!isModAdmin()) return 0;
        int removed = 0;
        synchronized (PRICE_DB) {
            for (String key : new HashSet<String>(PRICE_DB.keySet())) {
                ItemPriceData d = PRICE_DB.get(key);
                if (d != null && "AH".equalsIgnoreCase(d.source)) {
                    PRICE_DB.remove(key);
                    removed++;
                }
            }
        }
        dirty = true;
        savePrices();
        return removed;
    }

    public static int clearAllPrices() {
        if (!isModAdmin()) return 0;
        int removed;
        synchronized (PRICE_DB) {
            removed = PRICE_DB.size();
            PRICE_DB.clear();
        }
        dirty = true;
        savePrices();
        return removed;
    }

    // =====================================================================
    //  Recording prices
    // =====================================================================
    public static void recordPrice(String rawName, String source, double price) {
        recordPrice(rawName, source, price, 0, "");
    }

    public static void recordPrice(String rawName, String source, double price, double sell, String type) {
        recordPrice(rawName, source, price, sell, type, 0);
    }

    /** @param high AH: highest listing price of this item on the scanned page (0 = unknown) */
    public static void recordPrice(String rawName, String source, double price, double sell, String type, double high) {
        if (price <= 0) return;
        String name = cleanItemName(rawName);
        if (name.isEmpty() || isJunk(name)) return;
        String src = "BZ".equalsIgnoreCase(source) ? "BZ" : "AH";
        String key = keyOf(name, src);
        String uploadCategory;

        synchronized (PRICE_DB) {
            ItemPriceData d = PRICE_DB.get(key);
            if (d == null) {
                d = new ItemPriceData();
                d.name = name;
                d.source = src;
                PRICE_DB.put(key, d);
            }
            if (d.history == null) d.history = new ArrayList<Double>();

            // AH: within 10 minutes keep the LOWEST price seen, and the HIGHEST listing seen.
            boolean win = "AH".equals(src) && d.lowestBin > 0 && (System.currentTimeMillis() - d.lastSeen < 10 * 60 * 1000L);
            double hi = Math.max(high, price);
            if (win && price > d.lowestBin) price = d.lowestBin;
            d.history.add(price);
            while (d.history.size() > MAX_HISTORY) d.history.remove(0);

            double sum = 0;
            for (double p : d.history) sum += p;
            d.lowestBin = price;
            d.highestBin = "AH".equals(src) ? (win ? Math.max(d.highestBin, hi) : hi) : 0;
            d.avgPrice = sum / d.history.size();
            if (sell > 0) d.sellPrice = sell;
            d.lastSeen = System.currentTimeMillis();

            String cat = categorize(name, src, type);
            if (!"Other".equals(cat) || d.category == null || d.category.isEmpty()) d.category = cat;

            uploadCategory = d.category;
            checkAlerts(d);
        }
        dirty = true;
        PriceSync.queue(name, src, uploadCategory, price, sell);
    }

    public static void mergeRemote(String rawName, String source, String category, double price, double sell, long seen) {
        mergeRemote(rawName, source, category, price, sell, seen, 0);
    }

    public static void mergeRemote(String rawName, String source, String category, double price, double sell, long seen, double high) {
        if (price <= 0) return;
        String name = cleanItemName(rawName);
        if (name.isEmpty() || isJunk(name)) return;
        String src = "BZ".equalsIgnoreCase(source) ? "BZ" : "AH";
        String key = keyOf(name, src);

        synchronized (PRICE_DB) {
            ItemPriceData d = PRICE_DB.get(key);
            if (d == null) {
                d = new ItemPriceData();
                d.name = name;
                d.source = src;
                PRICE_DB.put(key, d);
            } else if (seen > 0 && seen <= d.lastSeen) {
                return;
            }
            if (d.history == null) d.history = new ArrayList<Double>();
            if (d.history.isEmpty() || d.history.get(d.history.size() - 1).doubleValue() != price) d.history.add(price);
            while (d.history.size() > MAX_HISTORY) d.history.remove(0);

            double sum = 0;
            for (double p : d.history) sum += p;
            d.lowestBin = price;
            if ("AH".equals(src) && high > 0) d.highestBin = Math.max(high, price);
            d.avgPrice = sum / d.history.size();
            if (sell > 0) d.sellPrice = sell;
            d.lastSeen = seen > 0 ? seen : System.currentTimeMillis();
            if (category != null && !category.isEmpty() && (!"Other".equals(category) || d.category == null || d.category.isEmpty())) {
                d.category = category;
            }
            checkAlerts(d);
        }
        dirty = true;
    }

    private static void checkAlerts(ItemPriceData d) {
        if (!d.watched) return;
        if (d.alertAbove > 0) {
            if (d.lowestBin >= d.alertAbove) {
                if (!d.firedAbove) {
                    d.firedAbove = true;
                    PENDING_CHAT.add("\u00a76[PriceTracker] \u00a7a" + d.name + " \u00a77rose above \u00a7e"
                            + formatCoins(d.alertAbove) + " \u00a77(now \u00a7e" + formatCoins(d.lowestBin) + "\u00a77)");
                }
            } else d.firedAbove = false;
        }
        if (d.alertBelow > 0) {
            if (d.lowestBin <= d.alertBelow) {
                if (!d.firedBelow) {
                    d.firedBelow = true;
                    PENDING_CHAT.add("\u00a76[PriceTracker] \u00a7c" + d.name + " \u00a77fell below \u00a7e"
                            + formatCoins(d.alertBelow) + " \u00a77(now \u00a7e" + formatCoins(d.lowestBin) + "\u00a77)");
                }
            } else d.firedBelow = false;
        }
    }

    // =====================================================================
    //  Lookups
    // =====================================================================
    /** Hook called by PriceSync. */
    public static void applySiteWatch(HashMap<String, double[]> watch) {
    }

    public static String keyOf(String cleanName, String source) {
        return ("BZ".equals(source) ? "bz:" : "") + cleanName.toLowerCase();
    }

    public static ItemPriceData getPrice(String rawName) {
        String name = cleanItemName(rawName);
        if (name.isEmpty()) return null;
        ItemPriceData d = PRICE_DB.get(keyOf(name, "AH"));
        return d != null ? d : PRICE_DB.get(keyOf(name, "BZ"));
    }

    public static ItemPriceData getPrice(String rawName, String market) {
        String name = cleanItemName(rawName);
        if (name.isEmpty()) return null;
        return PRICE_DB.get(keyOf(name, "BZ".equals(market) ? "BZ" : "AH"));
    }

    public static double sellValue(ItemPriceData d) {
        if (d == null) return 0;
        if ("BZ".equals(d.source) && d.sellPrice > 0) return d.sellPrice;
        return d.lowestBin;
    }

    public static List<Double> historyOf(ItemPriceData d) {
        synchronized (PRICE_DB) {
            return d.history == null ? new ArrayList<Double>() : new ArrayList<Double>(d.history);
        }
    }

    /** Bazaar-only trend in %, clamped to +/-50. Auction House prices have no meaningful trend. */
    public static double change(ItemPriceData d) {
        if (d == null || !"BZ".equals(d.source)) return 0;
        List<Double> h = historyOf(d);
        int n = h.size();
        if (n < 6) return 0;
        int r = 3;
        int from = Math.max(0, n - r - 10);
        double recent = median(h.subList(n - r, n));
        double base = median(h.subList(from, n - r));
        if (base < 50) return 0;
        double v = (recent - base) / base * 100.0;
        return Math.max(-50, Math.min(50, v));
    }

    /** Bazaar flip margin in %, after the 1.25% tax. Junk prices and unrealistic margins give 0. */
    public static double margin(ItemPriceData d) {
        if (d == null || !"BZ".equals(d.source)) return 0;
        if (d.sellPrice < 10 || d.lowestBin < 10) return 0;
        double m = ((d.lowestBin * 0.9875 - d.sellPrice) / d.sellPrice) * 100.0;
        return (m > 50 || m < 0) ? 0 : m;
    }

    private static double median(List<Double> l) {
        if (l.isEmpty()) return 0;
        List<Double> c = new ArrayList<Double>(l);
        Collections.sort(c);
        int m = c.size() / 2;
        return c.size() % 2 == 1 ? c.get(m) : (c.get(m - 1) + c.get(m)) / 2.0;
    }

    // =====================================================================
    //  Watchlist / junk
    // =====================================================================
    public static boolean setWatch(String key, Boolean watched, Double above, Double below) {
        synchronized (PRICE_DB) {
            ItemPriceData d = PRICE_DB.get(key);
            if (d == null) return false;
            if (watched != null) {
                d.watched = watched.booleanValue();
                if (!d.watched) { d.alertAbove = 0; d.alertBelow = 0; }
            }
            if (above != null) d.alertAbove = Math.max(0, above.doubleValue());
            if (below != null) d.alertBelow = Math.max(0, below.doubleValue());
            if ((above != null || below != null) && (d.alertAbove > 0 || d.alertBelow > 0)
                    && (watched == null || watched.booleanValue())) {
                d.watched = true;
            }
            d.firedAbove = false;
            d.firedBelow = false;
        }
        dirty = true;
        return true;
    }

    private static boolean isJunk(String cleanName) {
        String l = cleanName.toLowerCase().trim();
        return JUNK.contains(l) || l.startsWith("buy instantly") || l.startsWith("sell instantly")
                || l.startsWith("create ") || l.startsWith("view ") || l.startsWith("click ")
                || l.contains("% of spread") || l.contains("best offer") || l.contains("best order") || l.contains("same as best")
                || l.contains("top order") || l.startsWith("custom ") || l.contains("sell inventory") || l.contains("fill my inventory")
                || l.contains("buy only one") || l.startsWith("go back") || l.startsWith("manage ") || l.endsWith(" setup");
    }

    public static List<ItemPriceData> snapshotForUpload() {
        List<ItemPriceData> out = new ArrayList<ItemPriceData>();
        synchronized (PRICE_DB) {
            for (ItemPriceData d : PRICE_DB.values()) {
                if (d != null && d.name != null && d.lowestBin > 0 && !isJunk(d.name)) out.add(d);
            }
        }
        return out;
    }

    public static int purgeJunk() {
        int removed = 0;
        synchronized (PRICE_DB) {
            for (String key : new HashSet<String>(PRICE_DB.keySet())) {
                ItemPriceData d = PRICE_DB.get(key);
                if (d == null || d.name == null || isJunk(d.name)) {
                    PRICE_DB.remove(key);
                    removed++;
                }
            }
        }
        if (removed > 0) { dirty = true; savePrices(); }
        return removed;
    }

    // =====================================================================
    //  Settings
    // =====================================================================
    public static boolean getSetting(String key) {
        if ("tooltips".equals(key)) return tooltipsEnabled;
        if ("chestCalc".equals(key)) return chestCalcEnabled;
        if ("scan".equals(key)) return scanEnabled;
        if ("scanChat".equals(key)) return SHOW_SCAN_CHAT;
        if ("alertSound".equals(key)) return alertSound;
        return false;
    }

    public static void setSetting(String key, boolean value) {
        if ("tooltips".equals(key)) tooltipsEnabled = value;
        else if ("chestCalc".equals(key)) chestCalcEnabled = value;
        else if ("scan".equals(key)) scanEnabled = value;
        else if ("scanChat".equals(key)) SHOW_SCAN_CHAT = value;
        else if ("alertSound".equals(key)) alertSound = value;
        else return;
        saveSettings();
    }

    // =====================================================================
    //  Names
    // =====================================================================
    /** Strips colour codes, pet levels, quantity suffix and dungeon star symbols. Never changes the item itself. */
    public static String cleanItemName(String name) {
        if (name == null) return "";
        String stripped = EnumChatFormatting.getTextWithoutFormattingCodes(name);
        if (stripped == null) return "";
        return stripped.trim()
                .replaceAll("^\\[Lvl \\d+\\]\\s*", "")
                .replaceAll("\\s*x\\d+$", "")
                .replaceAll("[\u272A\u272F\u2726\u2727\u2B50\u25C6\u25C7\u2605\u2606]", "")
                .trim();
    }

    private static final Pattern QTY_SUFFIX = Pattern.compile("\\s+x(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

    public static int quantityOf(ItemStack stack) {
        if (stack == null) return 1;
        if (stack.hasDisplayName()) {
            String n = EnumChatFormatting.getTextWithoutFormattingCodes(stack.getDisplayName());
            if (n != null) {
                Matcher m = QTY_SUFFIX.matcher(n.trim());
                if (m.find()) {
                    try {
                        int q = Integer.parseInt(m.group(1));
                        if (q > 0) return q;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return Math.max(1, stack.stackSize);
    }

    private static String stripReforge(String name) {
        for (String ref : REFORGES) {
            if (name.startsWith(ref) && name.length() > ref.length()) return name.substring(ref.length()).trim();
        }
        return name;
    }

    /** Clean item name used for the database. Reforges are removed here, so a reforged item matches the base item. */
    public static String nameFor(ItemStack stack) {
        if (stack == null) return "";
        String raw = stack.hasDisplayName() ? stack.getDisplayName() : stack.getUnlocalizedName();
        String clean = cleanItemName(raw);
        if (clean.isEmpty()) return "";

        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(raw);
        boolean isPet = plain != null && plain.trim().startsWith("[Lvl");
        if (isPet) PET_NAMES.add(clean.toLowerCase());

        if (clean.equalsIgnoreCase("Enchanted Book") && stack.hasTagCompound()) {
            NBTTagCompound display = stack.getTagCompound().getCompoundTag("display");
            if (display.hasKey("Lore", 9)) {
                NBTTagList lore = display.getTagList("Lore", 8);
                if (lore.tagCount() > 0) {
                    String first = EnumChatFormatting.getTextWithoutFormattingCodes(lore.getStringTagAt(0));
                    if (first != null && !first.trim().isEmpty()) return "Enchanted Book (" + first.trim() + ")";
                }
            }
        }
        return isPet ? clean : stripReforge(clean);
    }

    private static String categorize(String name, String source, String type) {
        String n = name.toLowerCase();
        String t = type == null ? "" : type.toUpperCase();
        if (n.startsWith("enchanted book")) return "Enchanted Books";
        if (PET_NAMES.contains(n)) return "Pets";
        if (has(t, "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")) return "Armor";
        if (has(t, "ACCESSORY", "HATCESSORY")) return "Accessories";
        if (has(t, "SWORD", "BOW", "WAND", "DAGGER", "SCYTHE")) return "Weapons";
        if (has(t, "PICKAXE", "AXE", "SHOVEL", "HOE", "DRILL", "FISHING", "ROD", "GAUNTLET")) return "Tools";
        if (t.contains("DUNGEON")) return "Dungeon Items";
        if ("BZ".equals(source)) return n.startsWith("enchanted ") ? "Enchanted Materials" : "Raw Materials";
        return "Other";
    }

    private static boolean has(String text, String... parts) {
        for (String p : parts) if (text.contains(p)) return true;
        return false;
    }

    public static String formatCoins(double coins) {
        if (coins >= 1e9) return String.format("%.2fB", coins / 1e9);
        if (coins >= 1e6) return String.format("%.2fM", coins / 1e6);
        if (coins >= 1e3) return String.format("%.1fK", coins / 1e3);
        return String.format("%,.0f", coins);
    }

    // =====================================================================
    //  Saving / loading
    // =====================================================================
    public static void saveNow() {
        savePrices();
        saveSettings();
    }

    public static synchronized void savePrices() {
        if (dataFile == null) return;
        try {
            String json;
            synchronized (PRICE_DB) { json = GSON.toJson(PRICE_DB); }
            writeFile(dataFile, json);
            dirty = false;
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static synchronized void loadPrices() {
        if (dataFile == null || !dataFile.exists()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(dataFile), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<HashMap<String, ItemPriceData>>() {}.getType();
            Map<String, ItemPriceData> loaded = GSON.fromJson(reader, type);
            if (loaded == null) return;
            synchronized (PRICE_DB) {
                PRICE_DB.clear();
                for (ItemPriceData d : loaded.values()) {
                    if (d == null || d.name == null || d.name.isEmpty()) continue;
                    if (d.source == null) d.source = "AH";
                    if (d.category == null) d.category = "Other";
                    if (d.history == null) d.history = new ArrayList<Double>();
                    PRICE_DB.put(keyOf(d.name, d.source), d);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static void saveSettings() {
        if (settingsFile == null) return;
        try {
            Map<String, Boolean> m = new HashMap<String, Boolean>();
            for (String k : new String[]{"tooltips", "chestCalc", "scan", "scanChat", "alertSound"}) m.put(k, getSetting(k));
            writeFile(settingsFile, GSON.toJson(m));
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static void loadSettings() {
        if (settingsFile == null || !settingsFile.exists()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(settingsFile), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<HashMap<String, Boolean>>() {}.getType();
            Map<String, Boolean> m = GSON.fromJson(reader, type);
            if (m == null) return;
            for (Map.Entry<String, Boolean> en : m.entrySet()) {
                if (en.getValue() != null) setSettingQuiet(en.getKey(), en.getValue().booleanValue());
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static void setSettingQuiet(String key, boolean value) {
        if ("tooltips".equals(key)) tooltipsEnabled = value;
        else if ("chestCalc".equals(key)) chestCalcEnabled = value;
        else if ("scan".equals(key)) scanEnabled = value;
        else if ("scanChat".equals(key)) SHOW_SCAN_CHAT = value;
        else if ("alertSound".equals(key)) alertSound = value;
    }

    private static void writeFile(File f, String text) throws Exception {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(text);
        }
    }
}