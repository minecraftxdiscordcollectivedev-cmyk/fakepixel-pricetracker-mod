package com.fakepixel.pricetracker;

import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StringUtils;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Adds price lines to item tooltips.
 * Auction House items: Lowest BIN, Highest BIN (only when different), Average.
 * Bazaar items: Buy price + Sell price.
 */
public class TooltipHandler {

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        if (PriceTrackerMod.scanning || !PriceTrackerMod.tooltipsEnabled) return;
        ItemStack stack = event.itemStack;
        if (stack == null || !stack.hasDisplayName()) return;

        // Which market is this tooltip from? Decided by the item's own lore when possible.
        String ctx = null;
        for (String l : event.toolTip) {
            String s = StringUtils.stripControlCodes(l).toLowerCase().trim();
            if (s.contains("buy it now") || s.contains("starting bid") || s.startsWith("seller")
                    || s.startsWith("bids:") || s.startsWith("ends in")) { ctx = "AH"; break; }
            if (s.contains("buy price") || s.contains("sell price")) ctx = "BZ";
        }

        String name = PriceTrackerMod.nameFor(stack);
        if (name.isEmpty()) return;
        PriceTrackerMod.ItemPriceData data = ctx != null
                ? PriceTrackerMod.getPrice(name, ctx)
                : PriceTrackerMod.getPrice(name);
        if (data == null) return;

        int count = PriceTrackerMod.quantityOf(stack);

        event.toolTip.add("");
        if ("BZ".equals(data.source)) {
            event.toolTip.add(EnumChatFormatting.GOLD + "Buy Price: " + EnumChatFormatting.YELLOW
                    + PriceTrackerMod.formatCoins(data.lowestBin * count) + " coins");
            if (data.sellPrice > 0) {
                event.toolTip.add(EnumChatFormatting.GOLD + "Sell Price: " + EnumChatFormatting.AQUA
                        + PriceTrackerMod.formatCoins(data.sellPrice * count) + " coins");
            }
        } else {
            event.toolTip.add(EnumChatFormatting.GOLD + "Lowest BIN: " + EnumChatFormatting.YELLOW
                    + PriceTrackerMod.formatCoins(data.lowestBin * count) + " coins");
            if (data.highestBin > data.lowestBin) {
                event.toolTip.add(EnumChatFormatting.GOLD + "Highest BIN: " + EnumChatFormatting.YELLOW
                        + PriceTrackerMod.formatCoins(data.highestBin * count) + " coins");
            }
            if (data.avgPrice > 0 && Math.abs(data.avgPrice - data.lowestBin) > 0.5) {
                event.toolTip.add(EnumChatFormatting.GOLD + "Average: " + EnumChatFormatting.YELLOW
                        + PriceTrackerMod.formatCoins(data.avgPrice * count) + " coins");
            }
        }
    }
}