package com.spotdl.gui.android;

import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SpotifyEmbedParser {
    record Track(String title, String artist) {}
    record Collection(String title, String artist, List<Track> tracks) {}

    private static final Pattern SPOTIFY_URL = Pattern.compile(
            "https?://open\\.spotify\\.com/(?:intl-[a-z]{2}/)?(album|track|playlist)/([A-Za-z0-9]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROW = Pattern.compile(
            "<h3[^>]*TracklistRow_title[^>]*>(.*?)</h3>\\s*<h4[^>]*TracklistRow_subtitle[^>]*>(.*?)</h4>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NEXT_DATA = Pattern.compile(
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>",
            Pattern.DOTALL);

    static boolean isSpotify(String input) { return SPOTIFY_URL.matcher(input).find(); }

    static Collection read(String input) throws Exception {
        Matcher spotify = SPOTIFY_URL.matcher(input);
        if (!spotify.find()) throw new IllegalArgumentException("Μη έγκυρο Spotify link.");
        String type = spotify.group(1).toLowerCase(Locale.ROOT);
        String id = spotify.group(2);
        String html = get("https://open.spotify.com/embed/" + type + "/" + id);

        List<Track> tracks = new ArrayList<>();
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            tracks.add(new Track(clean(rows.group(1)), clean(rows.group(2))));
        }

        String title = type.equals("track") ? "Spotify Track" : "Spotify " + type;
        String artist = "";
        Matcher data = NEXT_DATA.matcher(html);
        if (data.find()) {
            Collection embedded = parseNextData(data.group(1), type);
            title = embedded.title();
            artist = embedded.artist();
            if (!embedded.tracks().isEmpty()) {
                tracks.clear();
                tracks.addAll(embedded.tracks());
            }
        }

        if (tracks.isEmpty()) {
            String oembed = get("https://open.spotify.com/oembed?url=" +
                    java.net.URLEncoder.encode("https://open.spotify.com/" + type + "/" + id, "UTF-8"));
            JSONObject metadata = new JSONObject(oembed);
            String display = metadata.optString("title", "").trim();
            if (!display.isEmpty()) {
                String[] parts = display.split(" - ", 2);
                title = parts[0];
                artist = parts.length > 1 ? parts[1] : "";
                tracks.add(new Track(title, artist));
            }
        }
        if (tracks.isEmpty()) throw new IllegalStateException("Δεν βρέθηκαν κομμάτια στη δημόσια σελίδα Spotify.");
        return new Collection(safeName(title), artist, tracks);
    }

    static Collection parseNextData(String rawJson, String fallbackType) throws Exception {
        JSONObject json = new JSONObject(rawJson);
        JSONObject state = json.getJSONObject("props").getJSONObject("pageProps")
                .optJSONObject("state");
        JSONObject stateData = state == null ? null : state.optJSONObject("data");
        JSONObject entity = stateData == null ? null : stateData.optJSONObject("entity");
        if (entity == null) {
            return new Collection("Spotify " + fallbackType, "", List.of());
        }

        String title = firstNonBlank(
                entity.optString("title", ""),
                entity.optString("name", ""),
                "Spotify " + fallbackType
        );
        String artist = entity.optString("subtitle", "").trim();
        List<Track> tracks = new ArrayList<>();
        JSONArray trackList = entity.optJSONArray("trackList");
        if (trackList != null) {
            for (int i = 0; i < trackList.length(); i++) {
                JSONObject track = trackList.optJSONObject(i);
                if (track == null) continue;
                String trackTitle = firstNonBlank(track.optString("title", ""), track.optString("name", ""));
                if (!trackTitle.isBlank()) {
                    tracks.add(new Track(trackTitle, track.optString("subtitle", "").trim()));
                }
            }
        } else if ("track".equalsIgnoreCase(entity.optString("type", fallbackType))) {
            if (artist.isBlank()) artist = artistNames(entity.optJSONArray("artists"));
            tracks.add(new Track(title, artist));
        }
        return new Collection(safeName(title), artist, tracks);
    }

    private static String artistNames(JSONArray artists) {
        if (artists == null) return "";
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            String name = artist.optString("name", "").trim();
            if (name.isEmpty()) continue;
            if (names.length() > 0) names.append(", ");
            names.append(name);
        }
        return names.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private static String get(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
        connection.setRequestProperty("Accept-Language", "el-GR,el;q=0.9,en;q=0.8");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("Η υπηρεσία επέστρεψε HTTP " + status);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
            return result.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static String clean(String html) {
        String withoutTags = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return Html.fromHtml(withoutTags, Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    static String safeName(String value) {
        String safe = value.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        return safe.isEmpty() ? "SpotDL Download" : safe;
    }

    static String trackFileName(int index, Track track) {
        String title = track.title().isBlank() ? "Άγνωστο κομμάτι" : track.title();
        String stem = track.artist().isBlank() ? title : track.artist() + " - " + title;
        String escapedStem = safeName(stem).replace("%", "%%");
        return String.format(Locale.ROOT, "%02d - %s.%%(ext)s", index + 1, escapedStem);
    }
}
