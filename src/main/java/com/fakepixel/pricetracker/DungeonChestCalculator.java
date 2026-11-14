package com.fakepixel.pricetracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DungeonChestCalculator {

    private static final Pattern COST_PATTERN = Pattern.compile("([0-9][0-9,]*)\\s*Coins", Pattern.CASE_INSENSITIVE);

    @SubscribeEvent
    public void onDrawGui(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!PriceTrackerMod.chestCalcEnabled) return;
        try {
            if (!(event.gui instanceof GuiChest)) return;

            GuiChest guiChest = (GuiChest) event.gui;
            ContainerChest container = (ContainerChest) guiChest.inventorySlots;
            IInventory lowerInv = container.getLowerChestInventory();
            String title = EnumChatFormatting.getTextWithoutFormattingCodes(lowerInv.getDisplayName().getUnformattedText());

            // Detect a reward chest by its title OR by the "Open Reward Chest" button
            boolean hasButton = false;
            for (int i = 0; i < lowerInv.getSizeInventory(); i++) {
                ItemStack s = lowerInv.getStackInSlot(i);
                if (s == null || !s.hasDisplayName()) continue;
                String n = PriceTrackerMod.cleanItemName(s.getDisplayName()).toLowerCase();
                if (n.contains("open reward chest") || n.contains("open chest")) { hasButton = true; break; }
            }
            if (!hasButton && !isDungeonChest(title)) return;

            double chestCost = 0;
            double totalLootValue = 0;
            List<String> itemLines = new ArrayList<String>();

            for (int i = 0; i < lowerInv.getSizeInventory(); i++) {
                ItemStack stack = lowerInv.getStackInSlot(i);
                if (stack == null || !stack.hasDisplayName()) continue;

                String cleanName = PriceTrackerMod.cleanItemName(stack.getDisplayName());
                String lower = cleanName.toLowerCase();

                if (lower.contains("open reward chest") || lower.contains("open chest")) {
                    chestCost = parseChestCost(stack);
                    continue;
                }
                if (cleanName.isEmpty() || lower.equals("close") || lower.contains("dungeon chest")
                        || lower.contains("reward") || lower.contains("go back")) {
                    continue;
                }

                int qty = PriceTrackerMod.quantityOf(stack);
                PriceTrackerMod.ItemPriceData priceData = PriceTrackerMod.getPrice(cleanName);
                if (priceData == null) priceData = PriceTrackerMod.getPrice("Enchanted Book (" + cleanName + ")");
                double itemVal = (priceData != null) ? PriceTrackerMod.sellValue(priceData) * qty : 0;
                totalLootValue += itemVal;

                String valText = (priceData != null)
                        ? EnumChatFormatting.YELLOW + PriceTrackerMod.formatCoins(itemVal)
                        : EnumChatFormatting.RED + "Unknown";
                itemLines.add(EnumChatFormatting.WHITE + "- " + cleanName + (qty > 1 ? " x" + qty : "") + " " + valText);
            }

            double profit = totalLootValue - chestCost;
            drawProfitOverlay(guiChest, title, chestCost, totalLootValue, profit, itemLines);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isDungeonChest(String title) {
        String lower = title.toLowerCase();
        return lower.contains("wood chest") || lower.contains("gold chest")
                || lower.contains("diamond chest") || lower.contains("emerald chest")
                || lower.contains("obsidian chest") || lower.contains("bedrock chest");
    }

    private double parseChestCost(ItemStack stack) {
        if (!stack.hasTagCompound()) return 0;
        NBTTagCompound display = stack.getTagCompound().getCompoundTag("display");
        if (!display.hasKey("Lore", 9)) return 0;

        NBTTagList lore = display.getTagList("Lore", 8);
        for (int i = 0; i < lore.tagCount(); i++) {
            String line = EnumChatFormatting.getTextWithoutFormattingCodes(lore.getStringTagAt(i));
            if (line.toLowerCase().contains("free")) return 0;
            Matcher m = COST_PATTERN.matcher(line);
            if (m.find()) {
                try {
                    return Double.parseDouble(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }

    private void drawProfitOverlay(GuiChest gui, String chestName, double cost, double value, double profit, List<String> items) {
        FontRenderer fr = Minecraft.getMinecraft().fontRendererObj;

        int boxWidth = 185;
        int boxHeight = 68 + (items.size() * 11);
        int x = (gui.width / 2) + 95;
        int y = (gui.height / 2) - (boxHeight / 2);

        if (x + boxWidth > gui.width - 5) {
            x = gui.width - boxWidth - 5;
        }
        if (y < 2) y = 2;

        Gui.drawRect(x - 2, y - 2, x + boxWidth + 2, y + boxHeight + 2, 0xFFDAA520);
        Gui.drawRect(x, y, x + boxWidth, y + boxHeight, 0xE614141A);

        int curY = y + 6;
        fr.drawStringWithShadow(EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + chestName + " Profit", x + 6, curY, 0xFFFFFF);
        curY += 14;

        for (String line : items) {
            fr.drawStringWithShadow(fr.trimStringToWidth(line, boxWidth - 12), x + 6, curY, 0xFFFFFF);
            curY += 11;
        }

        curY += 4;
        fr.drawStringWithShadow(EnumChatFormatting.GRAY + "Loot Value: " + EnumChatFormatting.YELLOW + PriceTrackerMod.formatCoins(value), x + 6, curY, 0xFFFFFF);
        curY += 11;
        fr.drawStringWithShadow(EnumChatFormatting.GRAY + "Chest Cost: " + EnumChatFormatting.RED + "-" + PriceTrackerMod.formatCoins(cost), x + 6, curY, 0xFFFFFF);
        curY += 13;

        String verdict = profit >= 0
                ? EnumChatFormatting.GREEN + "" + EnumChatFormatting.BOLD + "PROFIT: +" + PriceTrackerMod.formatCoins(profit)
                : EnumChatFormatting.RED + "" + EnumChatFormatting.BOLD + "LOSS: " + PriceTrackerMod.formatCoins(profit);
        fr.drawStringWithShadow(verdict, x + 6, curY, 0xFFFFFF);
    }
}