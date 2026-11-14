package com.fakepixel.pricetracker;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Local dashboard server (127.0.0.1 only).
 *
 *   GET  /                 the website (resources/web/index.html)
 *   GET  /api/prices       every tracked item
 *   GET  /api/status       counts, last scan, settings
 *   POST /api/settings     {"key":"tooltips","value":true}
 *   POST /api/watch        {"key":"bz:wither essence","watched":true,"above":0,"below":150}
 *   POST /api/purge        removes junk entries ("Buy Instantly" etc.)
 *   POST /api/add          {"name":"..","source":"AH","price":123}
 */
public class LocalWebServer {
    private static final Gson GSON = new Gson();
    private static final String[] SETTING_KEYS = {"tooltips", "chestCalc", "scan", "scanChat", "alertSound"};

    public static void start(int port) {
        try {
            // only this PC can open the site
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

            route(server, "/", false, ex -> {
                byte[] bytes = loadResource("/web/index.html");
                ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
            });

            route(server, "/api/prices", false, ex -> {
                String json;
                synchronized (PriceTrackerMod.PRICE_DB) { json = GSON.toJson(PriceTrackerMod.PRICE_DB.values()); }
                sendJson(ex, 200, json);
            });

            route(server, "/api/status", false, ex -> {
                Map<String, Object> o = new LinkedHashMap<String, Object>();
                int ah = 0, bz = 0, watched = 0;
                for (PriceTrackerMod.ItemPriceData d : PriceTrackerMod.PRICE_DB.values()) {
                    if ("BZ".equals(d.source)) bz++; else ah++;
                    if (d.watched) watched++;
                }
                o.put("total", ah + bz);
                o.put("ah", ah);
                o.put("bz", bz);
                o.put("watched", watched);
                o.put("lastScan", PriceTrackerMod.lastScan);
                o.put("now", System.currentTimeMillis());
                Map<String, Boolean> st = new LinkedHashMap<String, Boolean>();
                for (String k : SETTING_KEYS) st.put(k, PriceTrackerMod.getSetting(k));
                o.put("settings", st);
                sendJson(ex, 200, GSON.toJson(o));
            });

            route(server, "/api/settings", true, ex -> {
                JsonObject req = body(ex);
                String key = str(req, "key");
                boolean ok = false;
                for (String k : SETTING_KEYS) if (k.equals(key)) ok = true;
                if (ok && req.has("value")) PriceTrackerMod.setSetting(key, req.get("value").getAsBoolean());
                sendJson(ex, ok ? 200 : 400, "{\"ok\":" + ok + "}");
            });

            route(server, "/api/watch", true, ex -> {
                JsonObject req = body(ex);
                Boolean watched = req.has("watched") ? Boolean.valueOf(req.get("watched").getAsBoolean()) : null;
                Double above = req.has("above") ? Double.valueOf(req.get("above").getAsDouble()) : null;
                Double below = req.has("below") ? Double.valueOf(req.get("below").getAsDouble()) : null;
                boolean ok = PriceTrackerMod.setWatch(str(req, "key"), watched, above, below);
                sendJson(ex, ok ? 200 : 404, "{\"ok\":" + ok + "}");
            });

            route(server, "/api/purge", true, ex -> {
                int n = PriceTrackerMod.purgeJunk();
                sendJson(ex, 200, "{\"ok\":true,\"removed\":" + n + "}");
            });

            route(server, "/api/add", true, ex -> {
                JsonObject req = body(ex);
                String name = str(req, "name");
                String source = "BZ".equalsIgnoreCase(str(req, "source")) ? "BZ" : "AH";
                double price = req.has("price") ? req.get("price").getAsDouble() : 0;
                if (!name.isEmpty() && price > 0) PriceTrackerMod.recordPrice(name, source, price);
                sendJson(ex, 200, "{\"ok\":true}");
            });

            server.setExecutor(Executors.newFixedThreadPool(3, new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "pricetracker-web");
                    t.setDaemon(true);
                    return t;
                }
            }));
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---------- helpers ----------
    private interface Handler { void handle(HttpExchange ex) throws Exception; }

    private static void route(HttpServer server, String path, final boolean postOnly, final Handler h) {
        server.createContext(path, new HttpHandler() {
            public void handle(HttpExchange ex) throws IOException {
                try {
                    boolean isPost = "POST".equalsIgnoreCase(ex.getRequestMethod());
                    if (postOnly && !isPost) { sendJson(ex, 405, "{\"error\":\"POST only\"}"); return; }
                    if (isPost && !originAllowed(ex)) { sendJson(ex, 403, "{\"error\":\"forbidden\"}"); return; }
                    h.handle(ex);
                } catch (Exception e) {
                    try { sendJson(ex, 500, "{\"error\":\"server error\"}"); } catch (Exception ignored) {}
                } finally {
                    ex.close();
                }
            }
        });
    }

    /** Stops other websites from posting to your local server through your browser. */
    private static boolean originAllowed(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;
        return origin.startsWith("http://localhost") || origin.startsWith("http://127.0.0.1");
    }

    private static JsonObject body(HttpExchange ex) throws IOException {
        try {
            JsonElement e = new JsonParser().parse(readStream(ex.getRequestBody()));
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static byte[] loadResource(String path) throws IOException {
        try (InputStream is = LocalWebServer.class.getResourceAsStream(path)) {
            if (is == null) return "<h1>web/index.html not found in the mod jar</h1><p>Put it in src/main/resources/web/index.html</p>".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int n;
            while ((n = is.read(data, 0, data.length)) != -1) buffer.write(data, 0, n);
            return buffer.toByteArray();
        }
    }

    private static String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int n;
        while ((n = is.read(data, 0, data.length)) != -1) buffer.write(data, 0, n);
        return buffer.toString("UTF-8");
    }
}