package com.fakepixel.pricetracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shares prices with the central server.
 *  - download: every minute, newer prices from other players are merged into the local database
 *  - upload:   prices this player scans are sent to the server in small batches
 *
 * Runs on its own background thread, so it never slows the game down. If the server is offline
 * the mod just keeps working with its local data.
 *
 * Settings file: config/fakepixel_pricetracker/sync.json
 *   { "enabled": true, "upload": true, "serverUrl": "https://your-app.onrender.com" }
 */
public class PriceSync {

    /** CHANGE THIS to your server address before building the jar you share with others. */
    private static final String DEFAULT_SERVER_URL = "https://pricetracker-server.onrender.com";

    private static final long DOWNLOAD_EVERY_MS = 60000;
    private static final long LOOP_MS = 10000;
    private static final long WATCH_EVERY_MS = 60000;
    private static final int BATCH = 200;

    private static volatile String serverUrl = DEFAULT_SERVER_URL;
    private static volatile boolean enabled = true;
    private static volatile boolean upload = true;
    /** code from https://your-server/modlink - links this mod to your website watchlist (set with /prices link <code>) */
    private static volatile String modToken = "";
    private static volatile long lastWatchPull = 0;

    private static volatile File configDir;
    private static volatile boolean seeded = false;
    private static volatile long since = 0;       // server time of the last successful download
    private static volatile long lastDownload = 0;
    private static volatile int serverCount = -1;     // how many items the server has
    private static volatile long lastReseed = 0;
    private static volatile boolean started = false;
    public static volatile String status = "Sync not started";

    private static class Pending {
        String name, source, category;
        double price, sell;
    }

    private static final Map<String, Pending> OUTBOX = new ConcurrentHashMap<String, Pending>();

    // ---------- config ----------
    public static void loadConfig(File dir) {
        configDir = dir;
        File f = new File(dir, "sync.json");
        try {
            if (!f.exists()) {
                JsonObject o = new JsonObject();
                o.addProperty("enabled", true);
                o.addProperty("upload", true);
                o.addProperty("serverUrl", DEFAULT_SERVER_URL);
                o.addProperty("modToken", "");
                write(f, o.toString());
                return;
            }
            Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8);
            try {
                JsonObject o = new JsonParser().parse(r).getAsJsonObject();
                if (o.has("enabled")) enabled = o.get("enabled").getAsBoolean();
                if (o.has("upload")) upload = o.get("upload").getAsBoolean();
                if (o.has("modToken") && !o.get("modToken").isJsonNull()) modToken = o.get("modToken").getAsString().trim();
                if (o.has("serverUrl")) {
                    String u = o.get("serverUrl").getAsString().trim();
                    while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
                    if (!u.isEmpty()) serverUrl = u;
                }
            } finally { r.close(); }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void write(File f, String text) throws IOException {
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try { w.write(text); } finally { w.close(); }
    }

    private static void saveConfig() {
        if (configDir == null) return;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("upload", upload);
            o.addProperty("serverUrl", serverUrl);
            o.addProperty("modToken", modToken);
            write(new File(configDir, "sync.json"), o.toString());
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static String serverUrl() { return serverUrl; }

    /** /prices link <code>  (an empty code = unlink) */
    public static void setModToken(String token) {
        modToken = token == null ? "" : token.trim();
        saveConfig();
        lastWatchPull = 0;                    // pull the watchlist at the next loop
        if (modToken.isEmpty()) PriceTrackerMod.applySiteWatch(new java.util.HashMap<String, double[]>());
        start();
    }

    public static boolean isLinked() { return !modToken.isEmpty(); }

    private static boolean active() {
        return enabled && serverUrl != null && serverUrl.startsWith("http") && !serverUrl.contains("YOUR-SERVER");
    }

    // ---------- called by PriceTrackerMod when the player scans a price ----------
    public static void queue(String name, String source, String category, double price, double sell) {
        if (!upload || !active()) return;
        Pending p = new Pending();
        p.name = name; p.source = source; p.category = category; p.price = price; p.sell = sell;
        if (OUTBOX.size() > 2000) return;                       // server unreachable for a long time
        OUTBOX.put(PriceTrackerMod.keyOf(name, source), p);     // newest price per item wins
    }

    // ---------- background thread ----------
    public static synchronized void start() {
        if (started) return;
        started = true;
        if (!active()) {
            status = "Sync off (set serverUrl in sync.json)";
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() { loop(); }
        }, "pricetracker-sync");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        while (true) {
            try {
                if (active()) {
                    if (!OUTBOX.isEmpty()) flush();
                    if (System.currentTimeMillis() - lastDownload >= DOWNLOAD_EVERY_MS) download();
                    if (!modToken.isEmpty() && System.currentTimeMillis() - lastWatchPull >= WATCH_EVERY_MS) pullWatch();
                    if (upload && lastDownload > 0 && (!seeded || serverLostData())) seed();
                }
            } catch (Throwable t) {
                status = "Sync error: " + t.getMessage();
            }
            try { Thread.sleep(LOOP_MS); } catch (InterruptedException e) { return; }
        }
    }

    /** Downloads this player's website watchlist (price alerts) so they also fire in game. */
    private static void pullWatch() {
        lastWatchPull = System.currentTimeMillis();
        try {
            String text = request("GET", "/api/me", null, modToken);
            JsonObject o = new JsonParser().parse(text).getAsJsonObject();
            java.util.HashMap<String, double[]> watch = new java.util.HashMap<String, double[]>();
            if (o.has("watch") && o.get("watch").isJsonObject()) {
                for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("watch").entrySet()) {
                    if (!en.getValue().isJsonObject()) continue;
                    JsonObject w = en.getValue().getAsJsonObject();
                    watch.put(en.getKey(), new double[]{num(w, "above"), num(w, "below")});
                }
            }
            PriceTrackerMod.applySiteWatch(watch);
            status = "Synced, website watchlist: " + watch.size() + " items";
        } catch (IOException e) {
            if (String.valueOf(e.getMessage()).contains("401")) status = "Mod link expired - get a new code at " + serverUrl + "/modlink";
            else status = "Watchlist sync error: " + e.getMessage();
        } catch (Exception e) {
            status = "Watchlist sync error: " + e.getMessage();
        }
    }

    private static void download() throws IOException {
        String text = request("GET", "/prices?since=" + since, null);
        JsonObject o = new JsonParser().parse(text).getAsJsonObject();
        JsonArray items = o.getAsJsonArray("items");
        int n = 0;
        for (JsonElement el : items) {
            JsonObject it = el.getAsJsonObject();
            String name = str(it, "name");
            if (name.isEmpty()) continue;
            PriceTrackerMod.mergeRemote(name, str(it, "source"), str(it, "category"),
                    num(it, "lowestBin"), num(it, "sellPrice"), (long) num(it, "lastSeen"));
            n++;
        }
        if (o.has("count")) serverCount = o.get("count").getAsInt();
        if (o.has("now")) since = o.get("now").getAsLong();
        lastDownload = System.currentTimeMillis();
        status = "Synced (" + n + " updates)";
        if (n > 0) System.out.println("[PriceTracker] received " + n + " price updates from the server");
    }

    /**
     * One time per install: after the server was reached, upload everything this player already
     * has, so a new server fills up at once. Delete config/fakepixel_pricetracker/seeded.flag
     * to upload the whole list again.
     */
    /** True when the server has far fewer items than this player (Render free plan wipes data on restart). */
    private static boolean serverLostData() {
        if (!seeded || serverCount < 0) return false;
        if (System.currentTimeMillis() - lastReseed < 10 * 60 * 1000) return false;
        int mine = PriceTrackerMod.snapshotForUpload().size();
        return mine >= 20 && serverCount < mine / 2;
    }

    private static void seed() throws IOException {
        boolean reseed = seeded;                      // seeded before -> the server lost its data
        File flag = configDir == null ? null : new File(configDir, "seeded.flag");
        if (!reseed && (flag == null || flag.exists())) { seeded = true; return; }
        lastReseed = System.currentTimeMillis();
        List<PriceTrackerMod.ItemPriceData> all = PriceTrackerMod.snapshotForUpload();
        int sentCount = 0;
        for (int i = 0; i < all.size(); i += BATCH) {
            JsonArray arr = new JsonArray();
            for (int j = i; j < Math.min(all.size(), i + BATCH); j++) {
                PriceTrackerMod.ItemPriceData d = all.get(j);
                JsonObject o = new JsonObject();
                o.addProperty("name", d.name);
                o.addProperty("source", d.source);
                o.addProperty("category", d.category == null ? "Other" : d.category);
                o.addProperty("price", d.lowestBin);
                o.addProperty("sell", d.sellPrice);
                if (d.history != null && d.history.size() > 1) {
                    JsonArray hist = new JsonArray();
                    for (Double hv : d.history) if (hv != null) hist.add(new com.google.gson.JsonPrimitive(hv));
                    o.add("history", hist);
                }
                arr.add(o);
            }
            request("POST", "/submit", arr.toString());
            sentCount += arr.size();
        }
        if (flag != null) try { write(flag, "uploaded " + sentCount + " items\n"); } catch (IOException e) { /* ignore */ }
        serverCount = sentCount;
        seeded = true;
        status = "Uploaded your " + sentCount + " existing prices";
        System.out.println("[PriceTracker] uploaded " + sentCount + " existing prices to the server");
    }

    private static void flush() throws IOException {
        JsonArray arr = new JsonArray();
        java.util.List<Map.Entry<String, Pending>> sent = new java.util.ArrayList<Map.Entry<String, Pending>>();
        for (Map.Entry<String, Pending> en : OUTBOX.entrySet()) {
            Pending p = en.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("name", p.name);
            o.addProperty("source", p.source);
            o.addProperty("category", p.category == null ? "Other" : p.category);
            o.addProperty("price", p.price);
            o.addProperty("sell", p.sell);
            arr.add(o);
            sent.add(en);
            if (sent.size() >= BATCH) break;
        }
        if (arr.size() == 0) return;
        request("POST", "/submit", arr.toString());
        for (Map.Entry<String, Pending> en : sent) OUTBOX.remove(en.getKey(), en.getValue());
        status = "Uploaded " + sent.size() + " prices";
    }

    // ---------- http ----------
    private static String request(String method, String path, String body) throws IOException {
        return request(method, path, body, null);
    }

    private static String request(String method, String path, String body, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(serverUrl + path).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);          // free hosts can take ~50 s to wake up
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", "PriceTracker/" + PriceTrackerMod.VERSION);
            if (bearer != null && !bearer.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                c.setFixedLengthStreamingMode(b.length);
                OutputStream os = c.getOutputStream();
                try { os.write(b); } finally { os.close(); }
            }
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("server answered HTTP " + code);
            InputStream is = c.getInputStream();
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] data = new byte[8192];
                int n;
                while ((n = is.read(data)) != -1) buf.write(data, 0, n);
                return buf.toString("UTF-8");
            } finally { is.close(); }
        } finally {
            c.disconnect();
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static double num(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0;
    }
}