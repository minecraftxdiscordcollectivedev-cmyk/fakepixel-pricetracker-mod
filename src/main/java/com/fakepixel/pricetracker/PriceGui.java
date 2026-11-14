package com.fakepixel.pricetracker;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-game market browser. Open with the P key (rebindable in Controls) or /prices gui.
 *
 * Tabs:  Market (everything) | Movers (biggest price changes) | Flips (Bazaar margins) | Watchlist
 * Extras: category/source filters, search, sorting, star = watchlist, price alerts, graph with hover,
 *         scan on/off switch, last-scan status. Works at any GUI scale (detail panel becomes a popup
 *         on narrow screens).
 */
public class PriceGui extends GuiScreen {

    private static final String[] TABS = {"Market", "Movers", "Flips", "Watchlist"};
    private static final String[] CATS = {
            "All", "Weapons", "Armor", "Accessories", "Tools", "Pets",
            "Enchanted Books", "Dungeon Items", "Enchanted Materials", "Raw Materials", "Other"
    };
    private static final String[] SORTS = {"Name", "Price \u2193", "Price \u2191", "Risers", "Fallers", "Volatile", "Recent", "Margin"};
    private static final int ROW_H = 12;
    private static final int GOLD = 0xFFDAA520;
    private static final long STALE_MS = 24L * 3600L * 1000L;

    // remembered between openings
    private static int tab = 0;
    private static String cat = "All";
    private static String src = "ALL";
    private static int sort = 0;

    private GuiTextField search, alertAbove, alertBelow;
    private final List<PriceTrackerMod.ItemPriceData> list = new ArrayList<PriceTrackerMod.ItemPriceData>();
    private final Map<String, Integer> counts = new HashMap<String, Integer>();
    private final Map<PriceTrackerMod.ItemPriceData, Double> trend = new HashMap<PriceTrackerMod.ItemPriceData, Double>();
    private int scroll = 0;
    private PriceTrackerMod.ItemPriceData selected;
    private boolean detailOpen = false;      // narrow screens: detail replaces the table
    private boolean dirty = true;
    private long lastRebuild = 0;

    // layout (recomputed every frame)
    private int px, py, pw, ph, catW = 104, tx, tw, tabY, barY, hdrY, listY, rows;
    private int searchW, srcX, sortX, scanX;
    private boolean sideDetail, popup, detailVisible;
    private int dX, dY, dW, dB;
    // clickable areas of the detail panel {x, y, w, h}, filled while drawing
    private int[] btnWatch, btnSet, btnClear, btnBack;

    // ================= setup =================
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        String old = search == null ? "" : search.getText();
        search = new GuiTextField(0, fontRendererObj, 0, 0, 110, 12);
        search.setMaxStringLength(40);
        search.setText(old);
        search.setFocused(true);
        alertAbove = new GuiTextField(1, fontRendererObj, 0, 0, 60, 12);
        alertBelow = new GuiTextField(2, fontRendererObj, 0, 0, 60, 12);
        alertAbove.setMaxStringLength(16);
        alertBelow.setMaxStringLength(16);
        loadAlertFields();
        dirty = true;
    }

    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        PriceTrackerMod.saveNow();   // keeps watchlist / alerts
    }

    public void updateScreen() {
        search.updateCursorCounter();
        alertAbove.updateCursorCounter();
        alertBelow.updateCursorCounter();
    }

    public boolean doesGuiPauseGame() { return false; }

    private void layout() {
        px = 6; py = 6; pw = width - 12; ph = height - 12;
        sideDetail = pw >= 600;
        int detW = sideDetail ? 164 : 0;
        tx = px + catW + 8;
        tw = pw - catW - 14 - (sideDetail ? detW + 6 : 0);
        tabY = py + 18;
        barY = py + 34;
        hdrY = py + 50;
        listY = py + 64;
        rows = Math.max(1, (py + ph - 14 - listY) / ROW_H);

        searchW = Math.max(60, Math.min(150, tw - 232));
        srcX = tx + searchW + 4;
        sortX = srcX + 88 + 4;
        scanX = sortX + 86 + 4;

        popup = !sideDetail && detailOpen && selected != null;
        if (sideDetail) { dX = tx + tw + 8; dY = tabY; dW = detW; dB = py + ph - 6; }
        else            { dX = tx; dY = barY; dW = tw; dB = py + ph - 14; }
        detailVisible = selected != null && (sideDetail || popup);
    }

    // ================= data =================
    private static String catOf(PriceTrackerMod.ItemPriceData d) { return d.category == null ? "Other" : d.category; }
    private static String srcOf(PriceTrackerMod.ItemPriceData d) { return "BZ".equals(d.source) ? "BZ" : "AH"; }

    private boolean tabAccepts(PriceTrackerMod.ItemPriceData d, double tr) {
        switch (tab) {
            case 1: return Math.abs(tr) >= 0.1 && d.history.size() >= 3;
            case 2: return "BZ".equals(d.source) && PriceTrackerMod.margin(d) > 0;
            case 3: return d.watched;
            default: return true;
        }
    }

    private void rebuild() {
        dirty = false;
        lastRebuild = System.currentTimeMillis();
        list.clear();
        counts.clear();
        trend.clear();
        counts.put("All", 0);
        String q = search.getText().trim().toLowerCase();

        for (PriceTrackerMod.ItemPriceData d : PriceTrackerMod.PRICE_DB.values()) {
            double tr = PriceTrackerMod.change(d);
            trend.put(d, tr);
            if (!tabAccepts(d, tr)) continue;

            String c = catOf(d);
            counts.put("All", counts.get("All") + 1);
            Integer n = counts.get(c);
            counts.put(c, n == null ? 1 : n + 1);

            if (!cat.equals("All") && !cat.equals(c)) continue;
            if (!src.equals("ALL") && !src.equals(srcOf(d))) continue;
            if (!q.isEmpty() && !d.name.toLowerCase().contains(q)) continue;
            list.add(d);
        }

        switch (sort) {
            case 1: Collections.sort(list, (a, b) -> Double.compare(b.lowestBin, a.lowestBin)); break;
            case 2: Collections.sort(list, (a, b) -> Double.compare(a.lowestBin, b.lowestBin)); break;
            case 3: Collections.sort(list, (a, b) -> Double.compare(tr(b), tr(a))); break;
            case 4: Collections.sort(list, (a, b) -> Double.compare(tr(a), tr(b))); break;
            case 5: Collections.sort(list, (a, b) -> Double.compare(Math.abs(tr(b)), Math.abs(tr(a)))); break;
            case 6: Collections.sort(list, (a, b) -> Long.compare(b.lastSeen, a.lastSeen)); break;
            case 7: Collections.sort(list, (a, b) -> Double.compare(PriceTrackerMod.margin(b), PriceTrackerMod.margin(a))); break;
            default: Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
        }
    }

    private double tr(PriceTrackerMod.ItemPriceData d) {
        Double v = trend.get(d);
        return v == null ? 0 : v;
    }

    private void setTab(int t) {
        tab = t;
        scroll = 0;
        dirty = true;
        detailOpen = false;
        if (t == 1) sort = 5;
        else if (t == 2) sort = 7;
        else if (sort == 5 || sort == 7) sort = 0;
    }

    private void select(PriceTrackerMod.ItemPriceData d) {
        selected = d;
        if (!sideDetail) detailOpen = true;
        loadAlertFields();
    }

    // ================= formatting =================
    private static String fmt(double v) { return v <= 0 ? "-" : PriceTrackerMod.formatCoins(v); }

    private static String chg(double c) {
        if (Math.abs(c) < 0.05) return "\u00a77-";
        return (c > 0 ? "\u00a7a\u25B2 " : "\u00a7c\u25BC ") + String.format("%.1f%%", Math.abs(c));
    }

    private static String marginStr(double m) {
        return (m >= 0 ? "\u00a7a+" : "\u00a7c") + String.format("%.1f%%", m);
    }

    private static String ago(long t) {
        if (t <= 0) return "-";
        long s = (System.currentTimeMillis() - t) / 1000;
        if (s < 60) return "now";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }

    /** "1.5m", "200k", "2b", "1,500,000" -> number. 0 if empty/invalid. */
    private static double parseCoins(String s) {
        if (s == null) return 0;
        s = s.trim().toLowerCase().replace(",", "").replace(" ", "");
        if (s.isEmpty()) return 0;
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        if (last == 'k') mult = 1e3; else if (last == 'm') mult = 1e6; else if (last == 'b') mult = 1e9;
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try {
            double v = Double.parseDouble(s) * mult;
            return (v > 0 && !Double.isInfinite(v) && !Double.isNaN(v)) ? v : 0;
        } catch (NumberFormatException e) { return 0; }
    }

    // ================= alerts =================
    private void loadAlertFields() {
        if (alertAbove == null) return;
        alertAbove.setText(selected != null && selected.alertAbove > 0 ? String.valueOf(Math.round(selected.alertAbove)) : "");
        alertBelow.setText(selected != null && selected.alertBelow > 0 ? String.valueOf(Math.round(selected.alertBelow)) : "");
        alertAbove.setFocused(false);
        alertBelow.setFocused(false);
    }

    private void applyAlerts() {
        if (selected == null) return;
        double a = parseCoins(alertAbove.getText());
        double b = parseCoins(alertBelow.getText());
        synchronized (PriceTrackerMod.PRICE_DB) {
            selected.alertAbove = a;
            selected.alertBelow = b;
            selected.firedAbove = false;
            selected.firedBelow = false;
            if (a > 0 || b > 0) selected.watched = true;
        }
        loadAlertFields();
        dirty = true;
    }

    private void toggleWatch(PriceTrackerMod.ItemPriceData d) {
        synchronized (PriceTrackerMod.PRICE_DB) {
            d.watched = !d.watched;
            if (!d.watched) { d.alertAbove = 0; d.alertBelow = 0; }
            d.firedAbove = false;
            d.firedBelow = false;
        }
        if (d == selected) loadAlertFields();
        dirty = true;
    }

    // ================= drawing helpers =================
    private static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static boolean in(int mx, int my, int[] r) {
        return r != null && in(mx, my, r[0], r[1], r[2], r[3]);
    }

    private void rightString(String s, int xRight, int y, int color) {
        fontRendererObj.drawStringWithShadow(s, xRight - fontRendererObj.getStringWidth(s), y, color);
    }

    private void button(int x, int y, int w, int h, String label, boolean on, int mx, int my) {
        boolean hover = in(mx, my, x, y, w, h);
        Gui.drawRect(x, y, x + w, y + h, on ? GOLD : (hover ? 0xFF2C3246 : 0xFF1B1F2D));
        label = fontRendererObj.trimStringToWidth(label, w - 4);
        int w2 = fontRendererObj.getStringWidth(label);
        fontRendererObj.drawString(label, x + (w - w2) / 2, y + (h - 8) / 2, on ? 0xFF14120A : 0xFFCCCCCC);
    }

    // ================= main draw =================
    public void drawScreen(int mx, int my, float pt) {
        layout();
        if (dirty || System.currentTimeMillis() - lastRebuild > 2000) rebuild();
        int maxScroll = Math.max(0, list.size() - rows);
        if (scroll > maxScroll) scroll = maxScroll;
        if (scroll < 0) scroll = 0;
        btnWatch = btnSet = btnClear = btnBack = null;

        drawDefaultBackground();
        Gui.drawRect(px - 1, py - 1, px + pw + 1, py + ph + 1, GOLD);
        Gui.drawRect(px, py, px + pw, py + ph, 0xF00C0E14);

        // title + scanner status
        fontRendererObj.drawStringWithShadow("\u00a76\u00a7lPrice Tracker", px + 6, py + 6, 0xFFFFFF);
        String status;
        if (!PriceTrackerMod.scanEnabled) status = "\u00a7cScan OFF";
        else if (PriceTrackerMod.lastScan <= 0) status = "\u00a77Waiting for a scan";
        else {
            long s = (System.currentTimeMillis() - PriceTrackerMod.lastScan) / 1000;
            status = (s < 20 ? "\u00a7a" : "\u00a77") + "Last scan " + (s < 60 ? s + "s" : (s / 60) + "m") + " ago";
        }
        rightString(status + " \u00a78| \u00a77Tracking \u00a7e" + PriceTrackerMod.PRICE_DB.size(), px + pw - 6, py + 6, 0xFFFFFF);

        drawCategories(mx, my);
        drawTabs(mx, my);

        if (popup) {
            drawDetail(mx, my);
        } else {
            drawToolbar(mx, my);
            drawTable(mx, my);
            if (sideDetail) drawDetail(mx, my);
        }

        // alert fields are only drawn/used while the detail panel is visible
        String hint;
        if (popup) hint = "ESC / Back returns to the list";
        else if (tab == 2) hint = "Margin = profit of buying at Sell and selling at Buy, after 1.25% tax";
        else hint = "Click item for graph  |  \u2605 = watch  |  TAB = next tab  |  Green = Bazaar, Yellow = Auction (Low / High BIN)";
        fontRendererObj.drawString("\u00a78" + fontRendererObj.trimStringToWidth(hint, tw + 6), tx, py + ph - 10, 0xFFFFFF);

        super.drawScreen(mx, my, pt);
    }

    private void drawCategories(int mx, int my) {
        for (int i = 0; i < CATS.length; i++) {
            int y = py + 22 + i * 13;
            boolean on = CATS[i].equals(cat);
            boolean hover = in(mx, my, px + 4, y, catW, 12);
            Gui.drawRect(px + 4, y, px + catW + 4, y + 12, on ? 0xFF2A3047 : (hover ? 0xFF1C2030 : 0x00000000));
            Integer n = counts.get(CATS[i]);
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(CATS[i], catW - 30), px + 7, y + 2, on ? 0xFFF5B942 : 0xFFAAAAAA);
            rightString("\u00a78" + (n == null ? 0 : n), px + catW, y + 2, 0xFFFFFF);
        }
    }

    private void drawTabs(int mx, int my) {
        for (int i = 0; i < TABS.length; i++) {
            String label = TABS[i];
            if (i == 3) {
                int w = 0;
                for (PriceTrackerMod.ItemPriceData d : PriceTrackerMod.PRICE_DB.values()) if (d.watched) w++;
                label += " (" + w + ")";
            }
            button(tx + i * 66, tabY, 64, 13, label, i == tab, mx, my);
        }
    }

    private void drawToolbar(int mx, int my) {
        search.xPosition = tx;
        search.yPosition = barY;
        search.width = searchW;
        search.height = 12;
        search.drawTextBox();
        if (search.getText().isEmpty() && !search.isFocused())
            fontRendererObj.drawString("\u00a77Search...", tx + 4, barY + 2, 0xFFFFFF);
        button(srcX, barY, 28, 12, "All", src.equals("ALL"), mx, my);
        button(srcX + 30, barY, 28, 12, "AH", src.equals("AH"), mx, my);
        button(srcX + 60, barY, 28, 12, "BZ", src.equals("BZ"), mx, my);
        button(sortX, barY, 86, 12, "Sort: " + SORTS[sort], false, mx, my);
        if (scanX + 46 <= tx + tw + 2)
            button(scanX, barY, 46, 12, PriceTrackerMod.scanEnabled ? "Scan ON" : "Scan OFF", PriceTrackerMod.scanEnabled, mx, my);
    }

    private void drawTable(int mx, int my) {
        int cChg = tx + tw - 4, cSell = cChg - 52, cBuy = cSell - 56;
        Gui.drawRect(tx, hdrY - 2, tx + tw, hdrY + 10, 0xFF171A26);
        fontRendererObj.drawString("\u00a76Item", tx + 13, hdrY, 0xFFFFFF);
        rightString("\u00a76Low / Buy", cBuy, hdrY, 0xFFFFFF);
        rightString("\u00a76High / Sell", cSell, hdrY, 0xFFFFFF);
        rightString(tab == 2 ? "\u00a76Margin" : "\u00a76Trend", cChg, hdrY, 0xFFFFFF);

        if (list.isEmpty()) {
            String msg;
            if (tab == 1) msg = "No Bazaar movement yet. Trends need 6+ scans of an item over time.";
            else if (tab == 2) msg = "No Bazaar items with buy + sell prices yet. Browse the Bazaar.";
            else if (tab == 3) msg = "Watchlist empty. Click the star next to an item.";
            else msg = "No items. Open the Auction House / Bazaar and browse pages.";
            fontRendererObj.drawString("\u00a77" + fontRendererObj.trimStringToWidth(msg, tw - 8), tx + 4, listY + 6, 0xFFFFFF);
        }

        long now = System.currentTimeMillis();
        for (int i = scroll; i < Math.min(list.size(), scroll + rows); i++) {
            PriceTrackerMod.ItemPriceData d = list.get(i);
            int y = listY + (i - scroll) * ROW_H;
            boolean hover = in(mx, my, tx, y, tw, ROW_H);
            if (d == selected) Gui.drawRect(tx, y, tx + tw, y + ROW_H, 0xFF2A3047);
            else if (hover) Gui.drawRect(tx, y, tx + tw, y + ROW_H, 0xFF1A1E2B);
            else if ((i & 1) == 1) Gui.drawRect(tx, y, tx + tw, y + ROW_H, 0x10FFFFFF);

            boolean bz = "BZ".equals(d.source);
            boolean stale = now - d.lastSeen > STALE_MS;
            fontRendererObj.drawString(d.watched ? "\u00a76\u2605" : "\u00a78\u2606", tx + 2, y + 2, 0xFFFFFF);
            String name = fontRendererObj.trimStringToWidth(d.name, cBuy - tx - 76);
            fontRendererObj.drawString((stale ? "\u00a78" : (bz ? "\u00a7a" : "\u00a7e")) + name, tx + 13, y + 2, 0xFFFFFF);
            rightString("\u00a76" + fmt(d.lowestBin), cBuy, y + 2, 0xFFFFFF);
            String second;
            if (bz) second = "\u00a7b" + fmt(d.sellPrice);
            else if (d.highestBin > d.lowestBin) second = "\u00a7e" + fmt(d.highestBin);   // AH: highest BIN seen
            else second = "\u00a78-";
            rightString(second, cSell, y + 2, 0xFFFFFF);
            // trend only exists for Bazaar items; Auction House rows show nothing
            String last = tab == 2 ? marginStr(PriceTrackerMod.margin(d)) : (bz ? chg(tr(d)) : "\u00a78-");
            rightString(last, cChg, y + 2, 0xFFFFFF);
        }

        if (list.size() > rows) {
            int trackH = rows * ROW_H;
            int barH = Math.max(10, trackH * rows / list.size());
            int barYy = listY + (trackH - barH) * scroll / Math.max(1, list.size() - rows);
            Gui.drawRect(tx + tw + 1, listY, tx + tw + 4, listY + trackH, 0xFF161922);
            Gui.drawRect(tx + tw + 1, barYy, tx + tw + 4, barYy + barH, GOLD);
        }
    }

    // ================= detail panel =================
    private void drawDetail(int mx, int my) {
        Gui.drawRect(dX, dY, dX + dW, dB, 0xFF151823);
        if (selected == null) {
            fontRendererObj.drawString("\u00a77Click an item", dX + 6, dY + 8, 0xFFFFFF);
            fontRendererObj.drawString("\u00a77to see its graph.", dX + 6, dY + 20, 0xFFFFFF);
            return;
        }
        PriceTrackerMod.ItemPriceData d = selected;
        boolean bz = "BZ".equals(d.source);
        int x = dX + 5;
        int y = dY + 5;
        int nameW = dW - 10;

        if (popup) {
            btnBack = new int[]{dX + dW - 44, dY + 3, 40, 12};
            button(btnBack[0], btnBack[1], btnBack[2], btnBack[3], "Back", false, mx, my);
            nameW -= 46;
        }
        fontRendererObj.drawStringWithShadow("\u00a76" + fontRendererObj.trimStringToWidth(d.name, nameW), x, y, 0xFFFFFF);
        y += 12;
        fontRendererObj.drawString("\u00a77" + catOf(d) + " \u00a78| " + (bz ? "\u00a7aBazaar" : "\u00a7eAuction"), x, y, 0xFFFFFF);
        y += 13;
        fontRendererObj.drawString("\u00a77" + (bz ? "Buy: " : "Lowest BIN: ") + "\u00a7e" + fmt(d.lowestBin), x, y, 0xFFFFFF);
        y += 11;
        if (!bz && d.highestBin > d.lowestBin) {
            fontRendererObj.drawString("\u00a77Highest BIN: \u00a7e" + fmt(d.highestBin), x, y, 0xFFFFFF);
            y += 11;
        }
        if (bz) {
            fontRendererObj.drawString("\u00a77Sell: \u00a7b" + fmt(d.sellPrice), x, y, 0xFFFFFF);
            y += 11;
            if (d.sellPrice > 0 && d.lowestBin > 0) {
                double m = PriceTrackerMod.margin(d);
                double profit = d.lowestBin * 0.9875 - d.sellPrice;
                fontRendererObj.drawString("\u00a77Flip: " + marginStr(m) + " \u00a78(" + (profit >= 0 ? "+" : "") + fmt(Math.abs(profit)) + "/item)", x, y, 0xFFFFFF);
                y += 11;
            }
        }
        fontRendererObj.drawString("\u00a77Average: \u00a7f" + fmt(d.avgPrice), x, y, 0xFFFFFF);
        y += 11;
        if (bz) {
            fontRendererObj.drawString("\u00a77Trend: " + chg(PriceTrackerMod.change(d)), x, y, 0xFFFFFF);
            y += 11;
        }
        List<Double> h = PriceTrackerMod.historyOf(d);
        fontRendererObj.drawString("\u00a77Samples: \u00a7f" + h.size() + " \u00a78(" + ago(d.lastSeen) + ")", x, y, 0xFFFFFF);
        y += 14;

        // ---- graph ----
        int ctlTop = dB - 48;
        int gx = dX + 5, gw = dW - 10, gh = Math.min(70, ctlTop - y - 22);
        if (h.size() < 2) {
            fontRendererObj.drawString("\u00a78Need more samples for a graph", x, y, 0xFFFFFF);
        } else if (gh >= 20) {
            double min = Double.MAX_VALUE, max = 0, sum = 0;
            for (double v : h) { min = Math.min(min, v); max = Math.max(max, v); sum += v; }
            double avg = sum / h.size();
            double range = Math.max(1e-9, max - min);
            Gui.drawRect(gx - 1, y - 1, gx + gw + 1, y + gh + 1, 0xFF2A3047);
            Gui.drawRect(gx, y, gx + gw, y + gh, 0xFF0C0E14);
            int n = h.size();
            int color = h.get(n - 1) >= h.get(0) ? 0xFF3DDC84 : 0xFFFF5D5D;
            for (int i = 0; i < n; i++) {
                int x1 = gx + i * gw / n;
                int x2 = Math.max(x1 + 1, gx + (i + 1) * gw / n - (gw / n > 3 ? 1 : 0));
                int bh = 2 + (int) ((h.get(i) - min) / range * (gh - 3));
                Gui.drawRect(x1, y + gh - bh, x2, y + gh, color);
            }
            int avgY = y + gh - (2 + (int) ((avg - min) / range * (gh - 3)));
            Gui.drawRect(gx, avgY, gx + gw, avgY + 1, 0x99FFFFFF);       // average line

            fontRendererObj.drawString("\u00a78High \u00a77" + fmt(max), gx, y + gh + 3, 0xFFFFFFFF);
            rightString("\u00a78Low \u00a77" + fmt(min), gx + gw, y + gh + 3, 0xFFFFFF);

            if (in(mx, my, gx, y, gw, gh)) {                              // hover read-out
                int idx = Math.min(n - 1, (mx - gx) * n / gw);
                int bx = gx + idx * gw / n;
                Gui.drawRect(bx, y, bx + 1, y + gh, 0x88FFFFFF);
                double prev = idx > 0 ? h.get(idx - 1) : h.get(idx);
                double diff = prev > 0 ? (h.get(idx) - prev) / prev * 100 : 0;
                fontRendererObj.drawString("\u00a7f#" + (idx + 1) + " \u00a7e" + fmt(h.get(idx)) + " " + chg(diff), gx, y + gh + 13, 0xFFFFFF);
            } else {
                fontRendererObj.drawString("\u00a78White line = average", gx, y + gh + 13, 0xFFFFFF);
            }
        }

        // ---- watch + alert controls (bottom of panel) ----
        int c1 = dB - 46, c2 = dB - 31, c3 = dB - 16;
        btnWatch = new int[]{dX + 5, c1, 62, 12};
        btnSet = new int[]{dX + 70, c1, 34, 12};
        btnClear = new int[]{dX + 107, c1, 36, 12};
        button(btnWatch[0], btnWatch[1], btnWatch[2], btnWatch[3], d.watched ? "\u2605 Watching" : "\u2606 Watch", d.watched, mx, my);
        button(btnSet[0], btnSet[1], btnSet[2], btnSet[3], "Set", false, mx, my);
        button(btnClear[0], btnClear[1], btnClear[2], btnClear[3], "Clear", false, mx, my);

        int fx = dX + 5 + 40, fw = Math.max(40, dW - 5 - 40 - 5);
        fontRendererObj.drawString("\u00a77Above", dX + 5, c2 + 2, 0xFFFFFF);
        alertAbove.xPosition = fx; alertAbove.yPosition = c2; alertAbove.width = fw; alertAbove.height = 12;
        alertAbove.drawTextBox();
        fontRendererObj.drawString("\u00a77Below", dX + 5, c3 + 2, 0xFFFFFF);
        alertBelow.xPosition = fx; alertBelow.yPosition = c3; alertBelow.width = fw; alertBelow.height = 12;
        alertBelow.drawTextBox();
        if (alertAbove.getText().isEmpty() && !alertAbove.isFocused()) fontRendererObj.drawString("\u00a78e.g. 1.5m", fx + 4, c2 + 2, 0xFFFFFF);
        if (alertBelow.getText().isEmpty() && !alertBelow.isFocused()) fontRendererObj.drawString("\u00a78e.g. 200k", fx + 4, c3 + 2, 0xFFFFFF);
    }

    // ================= input =================
    protected void keyTyped(char c, int code) throws IOException {
        if (code == Keyboard.KEY_ESCAPE) {
            if (popup) { detailOpen = false; return; }
            mc.displayGuiScreen(null);
            return;
        }
        if (detailVisible && (alertAbove.isFocused() || alertBelow.isFocused())) {
            if (code == Keyboard.KEY_RETURN || code == Keyboard.KEY_NUMPADENTER) {
                applyAlerts();
                return;
            }
            alertAbove.textboxKeyTyped(c, code);
            alertBelow.textboxKeyTyped(c, code);
            return;
        }
        if (!popup && search.textboxKeyTyped(c, code)) { dirty = true; scroll = 0; return; }
        if (code == Keyboard.KEY_TAB) setTab((tab + 1) % TABS.length);
        else if (code == Keyboard.KEY_UP) scroll--;
        else if (code == Keyboard.KEY_DOWN) scroll++;
        else if (code == Keyboard.KEY_PRIOR) scroll -= rows;
        else if (code == Keyboard.KEY_NEXT) scroll += rows;
    }

    protected void mouseClicked(int mx, int my, int btn) throws IOException {
        // text fields (focus handling)
        if (detailVisible) {
            alertAbove.mouseClicked(mx, my, btn);
            alertBelow.mouseClicked(mx, my, btn);
        } else {
            alertAbove.setFocused(false);
            alertBelow.setFocused(false);
        }
        if (!popup) search.mouseClicked(mx, my, btn); else search.setFocused(false);

        // categories
        for (int i = 0; i < CATS.length; i++) {
            if (in(mx, my, px + 4, py + 22 + i * 13, catW, 12)) {
                cat = CATS[i]; scroll = 0; dirty = true; detailOpen = false; return;
            }
        }
        // tabs
        for (int i = 0; i < TABS.length; i++) {
            if (in(mx, my, tx + i * 66, tabY, 64, 13)) { setTab(i); return; }
        }
        // detail panel buttons
        if (detailVisible) {
            if (in(mx, my, btnBack)) { detailOpen = false; return; }
            if (in(mx, my, btnWatch)) { toggleWatch(selected); return; }
            if (in(mx, my, btnSet)) { applyAlerts(); return; }
            if (in(mx, my, btnClear)) {
                alertAbove.setText(""); alertBelow.setText(""); applyAlerts(); return;
            }
        }
        if (popup) return;

        // toolbar
        if (in(mx, my, tx, barY, tw + 2, 12)) {
            if (in(mx, my, srcX, barY, 28, 12)) { src = "ALL"; scroll = 0; dirty = true; return; }
            if (in(mx, my, srcX + 30, barY, 28, 12)) { src = "AH"; scroll = 0; dirty = true; return; }
            if (in(mx, my, srcX + 60, barY, 28, 12)) { src = "BZ"; scroll = 0; dirty = true; return; }
            if (in(mx, my, sortX, barY, 86, 12)) {
                sort = btn == 1 ? (sort + SORTS.length - 1) % SORTS.length : (sort + 1) % SORTS.length;
                dirty = true; return;
            }
            if (scanX + 46 <= tx + tw + 2 && in(mx, my, scanX, barY, 46, 12)) {
                PriceTrackerMod.scanEnabled = !PriceTrackerMod.scanEnabled; return;
            }
        }
        // table rows
        if (in(mx, my, tx, listY, tw, rows * ROW_H)) {
            int idx = scroll + (my - listY) / ROW_H;
            if (idx >= 0 && idx < list.size()) {
                PriceTrackerMod.ItemPriceData d = list.get(idx);
                if (mx < tx + 12) toggleWatch(d);   // the star
                else select(d);
            }
        }
    }

    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0 && !popup) scroll += d > 0 ? -3 : 3;
    }
}