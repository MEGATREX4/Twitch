package com.megatrex4;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.megatrex4.config.Config;
import com.megatrex4.config.ConfigManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight Helix HTTP client for stream status.
 * <p>
 * Avoids Twitch4J's Helix module (and its commons-logging / Hystrix deps)
 * so live checks work on Fabric even when {@code withEnableHelix(true)} fails.
 */
public final class HelixHttp {
    private static final AtomicReference<String> appAccessToken = new AtomicReference<>();
    private static final AtomicLong appTokenExpiresAtMs = new AtomicLong(0);

    private HelixHttp() {
    }

    public static boolean isConfigured() {
        Config.TwitchSettings t = ConfigManager.getConfig().twitch;
        boolean hasApp = t.clientId != null && !t.clientId.isBlank()
                && t.clientSecret != null && !t.clientSecret.isBlank();
        boolean hasUser = t.token != null && !t.token.isBlank()
                && !t.token.equals("oauth:your_token_here")
                && t.clientId != null && !t.clientId.isBlank();
        return hasApp || hasUser;
    }

    /**
     * Query which of the given logins are currently live.
     *
     * @return map login(lowercase) → stream info (only live channels present)
     */
    public static Map<String, StreamInfo> getLiveStreams(List<String> logins) throws IOException {
        Map<String, StreamInfo> live = new HashMap<>();
        if (logins == null || logins.isEmpty()) return live;

        Config.TwitchSettings t = ConfigManager.getConfig().twitch;
        String clientId = t.clientId == null ? "" : t.clientId.trim();
        if (clientId.isBlank()) {
            throw new IOException("clientId is empty – set it in config/twitch.json");
        }

        String bearer = resolveBearerToken(t);
        if (bearer == null || bearer.isBlank()) {
            throw new IOException("No Helix bearer token (need clientSecret for app token, or user oauth token)");
        }

        // Helix allows up to 100 user_login params
        StringBuilder url = new StringBuilder("https://api.twitch.tv/helix/streams?");
        boolean first = true;
        for (String login : logins) {
            if (login == null || login.isBlank()) continue;
            if (!first) url.append('&');
            first = false;
            url.append("user_login=").append(URLEncoder.encode(login.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8));
        }
        if (first) return live;

        HttpURLConnection conn = (HttpURLConnection) URI.create(url.toString()).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setRequestProperty("Client-Id", clientId);
        conn.setRequestProperty("Authorization", "Bearer " + bearer);
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        String body = readBody(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (code < 200 || code > 299) {
            throw new IOException("Helix HTTP " + code + ": " + body);
        }

        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray data = root.has("data") ? root.getAsJsonArray("data") : null;
        if (data == null) return live;

        for (JsonElement el : data) {
            JsonObject o = el.getAsJsonObject();
            String login = text(o, "user_login");
            if (login == null || login.isBlank()) {
                login = text(o, "user_name");
            }
            if (login == null) continue;
            login = login.toLowerCase(Locale.ROOT);
            live.put(login, new StreamInfo(
                    text(o, "id"),
                    text(o, "title"),
                    text(o, "user_name"),
                    login
            ));
        }
        return live;
    }

    private static String resolveBearerToken(Config.TwitchSettings t) throws IOException {
        // Prefer app access token (client credentials) for public stream status
        if (t.clientId != null && !t.clientId.isBlank()
                && t.clientSecret != null && !t.clientSecret.isBlank()) {
            return getAppAccessToken(t.clientId.trim(), t.clientSecret.trim());
        }
        // Fall back to user token (strip oauth:)
        if (t.token != null && !t.token.isBlank()) {
            String bare = t.token.trim();
            if (bare.regionMatches(true, 0, "oauth:", 0, 6)) {
                bare = bare.substring(6);
            }
            return bare;
        }
        return null;
    }

    private static String getAppAccessToken(String clientId, String clientSecret) throws IOException {
        long now = System.currentTimeMillis();
        String cached = appAccessToken.get();
        // refresh 60s early
        if (cached != null && now < appTokenExpiresAtMs.get() - 60_000L) {
            return cached;
        }

        synchronized (HelixHttp.class) {
            cached = appAccessToken.get();
            if (cached != null && now < appTokenExpiresAtMs.get() - 60_000L) {
                return cached;
            }

            String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                    + "&grant_type=client_credentials";

            HttpURLConnection conn = (HttpURLConnection) URI.create("https://id.twitch.tv/oauth2/token")
                    .toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String resp = readBody(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code > 299) {
                throw new IOException("OAuth client_credentials HTTP " + code + ": " + resp);
            }

            JsonObject json = JsonParser.parseString(resp).getAsJsonObject();
            String token = text(json, "access_token");
            int expiresIn = json.has("expires_in") ? json.get("expires_in").getAsInt() : 3600;
            if (token == null || token.isBlank()) {
                throw new IOException("OAuth response missing access_token: " + resp);
            }

            appAccessToken.set(token);
            appTokenExpiresAtMs.set(System.currentTimeMillis() + expiresIn * 1000L);
            Twitch.LOGGER.info("Helix app access token obtained (expires in {}s).", expiresIn);
            return token;
        }
    }

    /** Clear cached app token (call on reload). */
    public static void clearTokenCache() {
        appAccessToken.set(null);
        appTokenExpiresAtMs.set(0);
    }

    private static String text(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsString();
    }

    private static String readBody(InputStream in) {
        if (in == null) return "";
        try (Scanner s = new Scanner(in, StandardCharsets.UTF_8).useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        }
    }

    public static final class StreamInfo {
        public final String id;
        public final String title;
        public final String userName;
        public final String userLogin;

        public StreamInfo(String id, String title, String userName, String userLogin) {
            this.id = id == null ? "" : id;
            this.title = title == null ? "" : title;
            this.userName = userName == null ? "" : userName;
            this.userLogin = userLogin == null ? "" : userLogin;
        }
    }
}
