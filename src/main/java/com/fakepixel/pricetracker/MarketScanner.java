package com.fakepixel.pricetracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StringUtils;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.io.FileWriter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans open chest-style menus (Auction House, Bazaar).
 * Auction House: only BIN prices are saved (bids ignored). Per page it keeps the lowest AND highest listing of each item.
 */
public class MarketScanner {

    private static final Pattern PRICE = Pattern.compile(
            "(?i)(buy it now|buy price|sell price|price)[^\\d]*([\\d,]+(?:\\.\\d+)?)\\s*coins");
    private static final Pattern RARITY = Pattern.compile(
            "^(?:COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|VERY SPECIAL|SUPREME)\\b\\s*(.*)$");

    private static final boolean RECORD_GENERIC = false;
    private static final double MAX_PRICE = 1e12;

    private static final Pattern DUNGEON_CHEST = Pattern.compile(
            "(?i)^(wood|gold|diamond|emerald|obsidian|bedrock)\\s+chest$|reward");
    private static final long RESCAN_MS = 30000;
    private static final Pattern SETUP_TITLE = Pattern.compile("(?i)(at what price|how many|setup|confirm|manage orders|bazaar orders|order options)");
    private static final Pattern SETUP_LORE = Pattern.compile("(?i)(sell offer setup|buy order setup|click to proceed|% of spread)");

    private static class Found {
        String name, source, type;
        double price, sell, high;
    }

    private int tick = 0;
    private boolean wasChest = false;
    private int lastSig = 0;
    private long lastFullScan = 0;
    private String lastTitle = "";
    private long lastChatTime = 0;
    private int savedSinceChat = 0;
    private final Set<String> seen = new HashSet<String>();
    private final Set<String> dumpedTitles = new HashSet<String>();

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        tick++;
        Minecraft mc = Minecraft.getMinecraft();

        GuiScreen s = mc.currentScreen;
        if (!(s instanceof GuiChest)) {
            if (wasChest) { seen.clear(); wasChest = false; lastSig = 0; lastTitle = ""; }
            return;
        }
        wasChest = true;
        if (!PriceTrackerMod.scanEnabled) return;
        if (tick % 5 != 0 || mc.thePlayer == null) return;

        try {
            Container cont = ((GuiChest) s).inventorySlots;
            if (!(cont instanceof ContainerChest)) return;
            IInventory inv = ((ContainerChest) cont).getLowerChestInventory();
            int size = inv.getSizeInventory();
            String title = EnumChatFormatting.getTextWithoutFormattingCodes(inv.getDisplayName().getUnformattedText());

            int sig = signature(cont, size);
            long now = System.currentTimeMillis();
            if (sig == lastSig && now - lastFullScan < RESCAN_MS) return;
            lastSig = sig;
            lastFullScan = now;

            if (DUNGEON_CHEST.matcher(title.trim()).find()) return;
            if (SETUP_TITLE.matcher(title).find()) return;
            if (!title.equals(lastTitle)) { lastTitle = title; seen.clear(); }
            dump(title, inv);

            Map<String, Found> best = new LinkedHashMap<String, Found>();
            for (Slot slot : cont.inventorySlots) {
                if (slot.slotNumber >= size) continue;
                ItemStack st = slot.getStack();
                if (st == null || !hasLore(st)) continue;

                String name = PriceTrackerMod.nameFor(st);
                if (name.isEmpty()) continue;

                List<String> lines;
                PriceTrackerMod.scanning = true;
                try { lines = st.getTooltip(mc.thePlayer, false); }
                finally { PriceTrackerMod.scanning = false; }

                double bin = -1, bzBuy = -1, bzSell = -1, generic = -1;
                boolean ui = false;
                boolean ahMarker = false;
                String type = "";
                for (String raw : lines) {
                    String line = StringUtils.stripControlCodes(raw).trim();
                    String low = line.toLowerCase();
                    if (SETUP_LORE.matcher(line).find()) { ui = true; break; }
                    if (low.startsWith("seller") || low.startsWith("bids:")) ahMarker = true;
                    Matcher r = RARITY.matcher(line);
                    if (r.find()) type = r.group(1);

                    Matcher m = PRICE.matcher(line);
                    if (!m.find()) continue;
                    String label = m.group(1).toLowerCase();
                    double v = parse(m.group(2));
                    if (v <= 0) continue;

                    if (label.equals("buy it now")) { ahMarker = true; if (bin < 0) bin = v; }
                    else if (label.equals("buy price")) { if (bzBuy < 0) bzBuy = v; }
                    else if (label.equals("sell price")) { if (bzSell < 0) bzSell = v; }
                    else { if (generic < 0) generic = v; }
                }

                if (ui) continue;
                int count = PriceTrackerMod.quantityOf(st);
                String source;
                double price, sell = 0;

                if (bin > 0)           { source = "AH"; price = bin / count; }
                else if (ahMarker)     continue;   // bids / non-BIN auctions are ignored
                else if (bzBuy > 0)    { source = "BZ"; price = bzBuy; sell = bzSell > 0 ? bzSell : 0; }
                else if (bzSell > 0)   { source = "BZ"; price = bzSell; sell = bzSell; }
                else if (RECORD_GENERIC && generic > 0) { source = "AH"; price = generic / count; }
                else continue;

                if (price <= 0 || price > MAX_PRICE) continue;

                String k = name.toLowerCase() + "|" + source;
                Found f = best.get(k);
                if (f == null) {
                    f = new Found();
                    f.name = name; f.source = source; f.type = type;
                    f.price = price; f.sell = sell; f.high = price;
                    best.put(k, f);
                } else {
                    if (price > f.high) f.high = price;
                    if (price < f.price) {
                        f.price = price;
                        if (sell > 0) f.sell = sell;
                        if (!type.isEmpty()) f.type = type;
                    }
                }
            }

            int saved = 0;
            for (Map.Entry<String, Found> en : best.entrySet()) {
                Found f = en.getValue();
                String once = en.getKey() + "|" + f.price + "|" + f.high + "|" + f.sell;
                if (!seen.add(once)) continue;
                PriceTrackerMod.recordPrice(f.name, f.source, f.price, f.sell, f.type, f.high);
                saved++;
            }
            if (saved > 0) {
                savedSinceChat += saved;
                PriceTrackerMod.lastScan = System.currentTimeMillis();
            }

            if (PriceTrackerMod.SHOW_SCAN_CHAT && savedSinceChat > 0 && System.currentTimeMillis() - lastChatTime > 5000) {
                mc.thePlayer.addChatMessage(new ChatComponentText(
                        "§6[PriceTracker] §7Scanned prices. Tracking §e"
                                + PriceTrackerMod.PRICE_DB.size() + " §7items."));
                lastChatTime = System.currentTimeMillis();
                savedSinceChat = 0;
            }
        } catch (Exception ex) {
            PriceTrackerMod.scanning = false;
            ex.printStackTrace();
        }
    }

    private static int signature(Container cont, int size) {
        int h = 1;
        for (int i = 0; i < size; i++) {
            ItemStack st = cont.getSlot(i).getStack();
            if (st == null) {
                h = 31 * h;
                continue;
            }
            h = 31 * h + st.getDisplayName().hashCode();
            h = 31 * h + st.stackSize;
            if (st.hasTagCompound()) h = 31 * h + st.getTagCompound().hashCode();
        }
        return h;
    }

    private static boolean hasLore(ItemStack st) {
        if (!st.hasTagCompound()) return false;
        return st.getTagCompound().getCompoundTag("display").hasKey("Lore", 9);
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s.replace(",", "")); } catch (Exception e) { return -1; }
    }

    private void dump(String title, IInventory inv) {
        if (!dumpedTitles.add(title)) return;
        try {
            File f = new File(Minecraft.getMinecraft().mcDataDir, "config/pricetracker_debug.txt");
            f.getParentFile().mkdirs();
            FileWriter fw = new FileWriter(f, true);
            fw.write("==== MENU TITLE: " + title + " ====\n");
            int shown = 0;
            for (int i = 0; i < inv.getSizeInventory() && shown < 8; i++) {
                ItemStack st = inv.getStackInSlot(i);
                if (st == null || !st.hasDisplayName() || !st.hasTagCompound()) continue;
                NBTTagCompound d = st.getTagCompound().getCompoundTag("display");
                if (!d.hasKey("Lore", 9)) continue;
                NBTTagList lore = d.getTagList("Lore", 8);
                fw.write("-- slot " + i + ": " + EnumChatFormatting.getTextWithoutFormattingCodes(st.getDisplayName()) + "\n");
                for (int j = 0; j < lore.tagCount(); j++) {
                    fw.write("     " + EnumChatFormatting.getTextWithoutFormattingCodes(lore.getStringTagAt(j)) + "\n");
                }
                shown++;
            }
            fw.write("\n");
            fw.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}