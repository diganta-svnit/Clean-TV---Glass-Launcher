package tv.glasshome.launcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TMDB: what is popular right now on the streaming apps installed on this TV (watch region from glass.properties), optionally in
 * one original language, and where a title can be watched. The read token is built into the APK from
 * my-tv/tmdb_key.txt and is never logged.
 */
final class Tmdb {

    static final String REGION = BuildConfig.REGION;
    private static final String API = "https://api.themoviedb.org/3";
    static final String IMG = "https://image.tmdb.org/t/p/";

    static final class Item {
        final int id;
        final boolean tv;
        final String title, overview, poster, backdrop, year;
        final double rating;

        Item(int id, boolean tv, String title, String overview, String poster, String backdrop, String year,
             double rating) {
            this.id = id;
            this.tv = tv;
            this.title = title;
            this.overview = overview;
            this.poster = poster;
            this.backdrop = backdrop;
            this.year = year;
            this.rating = rating;
        }

        String meta() {
            StringBuilder b = new StringBuilder(tv ? "Series" : "Film");
            if (!year.isEmpty()) b.append("  ·  ").append(year);
            if (rating > 0) b.append(String.format(Locale.UK, "  ·  ★ %.1f", rating));
            return b.toString();
        }
    }

    private final String token;

    Tmdb(String token) {
        this.token = token;
    }

    boolean hasKey() {
        return token != null && token.length() > 20;
    }

    /** Provider id → provider name for India, films and series together. */
    Map<Integer, String> providers() throws IOException, JSONException {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (String type : new String[]{"movie", "tv"}) {
            JSONArray r = get("/watch/providers/" + type + "?watch_region=" + REGION).getJSONArray("results");
            for (int i = 0; i < r.length(); i++) {
                JSONObject p = r.getJSONObject(i);
                out.put(p.getInt("provider_id"), p.getString("provider_name"));
            }
        }
        return out;
    }

    /** Popular films and series on the given providers; language is an ISO 639-1 code or "" for all. */
    List<Item> trending(Collection<Integer> providerIds, String language) throws IOException, JSONException {
        StringBuilder ids = new StringBuilder();
        for (int id : providerIds) {
            if (ids.length() > 0) ids.append('|');
            ids.append(id);
        }
        String common = "&watch_region=" + REGION + "&with_watch_providers=" + enc(ids.toString())
                + "&with_watch_monetization_types=" + enc("flatrate|free|ads")
                + "&sort_by=popularity.desc&include_adult=false&language=en-US"
                + (language.isEmpty() ? "&vote_count.gte=40" : "&with_original_language=" + language);
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        long now = System.currentTimeMillis();
        String movieSince = day.format(new Date(now - 400L * 24 * 3600 * 1000));
        String tvSince = day.format(new Date(now - 120L * 24 * 3600 * 1000));
        List<Item> movies = parse(get("/discover/movie?primary_release_date.gte=" + movieSince + common), false);
        String newShows = day.format(new Date(now - 730L * 24 * 3600 * 1000));
        List<Item> series = parse(get("/discover/tv?air_date.gte=" + tvSince + "&first_air_date.gte=" + newShows
                + common), true);
        // Few new series in this language: allow older shows that are still airing.
        if (series.size() < 4) series = parse(get("/discover/tv?air_date.gte=" + tvSince + common), true);
        List<Item> out = new ArrayList<>();
        for (int i = 0; out.size() < 20 && (i < movies.size() || i < series.size()); i++) {
            if (i < movies.size()) out.add(movies.get(i));
            if (i < series.size() && out.size() < 20) out.add(series.get(i));
        }
        return out;
    }

    /** Names of the providers where this title streams in India, best first (subscription, then free). */
    List<String> whereToWatch(Item item) throws IOException, JSONException {
        JSONObject res = get("/" + (item.tv ? "tv" : "movie") + "/" + item.id + "/watch/providers")
                .getJSONObject("results");
        List<String> names = new ArrayList<>();
        JSONObject in = res.optJSONObject(REGION);
        if (in == null) return names;
        for (String kind : new String[]{"flatrate", "free", "ads", "rent", "buy"}) {
            JSONArray a = in.optJSONArray(kind);
            if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                String n = a.getJSONObject(i).getString("provider_name");
                if (!names.contains(n)) names.add(n);
            }
        }
        return names;
    }

    /** YouTube key of the title's official trailer (or any trailer, then teaser), or null. */
    String trailer(Item item) throws IOException, JSONException {
        JSONArray r = get("/" + (item.tv ? "tv" : "movie") + "/" + item.id + "/videos").getJSONArray("results");
        String any = null, teaser = null;
        for (int i = 0; i < r.length(); i++) {
            JSONObject v = r.getJSONObject(i);
            if (!"YouTube".equals(v.optString("site"))) continue;
            String type = v.optString("type");
            if ("Trailer".equals(type)) {
                if (v.optBoolean("official")) return v.getString("key");
                if (any == null) any = v.getString("key");
            } else if ("Teaser".equals(type) && teaser == null) {
                teaser = v.getString("key");
            }
        }
        return any != null ? any : teaser;
    }

    static List<Item> parse(JSONObject page, boolean tv) throws JSONException {
        List<Item> out = new ArrayList<>();
        JSONArray r = page.getJSONArray("results");
        for (int i = 0; i < r.length(); i++) {
            JSONObject o = r.getJSONObject(i);
            String poster = o.optString("poster_path", "");
            if (poster.isEmpty() || "null".equals(poster)) continue;
            String date = o.optString(tv ? "first_air_date" : "release_date", "");
            out.add(new Item(o.getInt("id"), tv, o.optString(tv ? "name" : "title", ""),
                    o.optString("overview", ""), poster, o.optString("backdrop_path", ""),
                    date.length() >= 4 ? date.substring(0, 4) : "", o.optDouble("vote_average", 0)));
        }
        return out;
    }

    private JSONObject get(String path) throws IOException, JSONException {
        HttpURLConnection con = (HttpURLConnection) new URL(API + path).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(15000);
        con.setRequestProperty("Authorization", "Bearer " + token);
        con.setRequestProperty("Accept", "application/json");
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("TMDB HTTP " + code);
            try (InputStream in = con.getInputStream()) {
                return new JSONObject(read(in));
            }
        } finally {
            con.disconnect();
        }
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    /** Normalised name for matching a provider to an installed app: "Amazon Prime Video" ~ "Prime Video". */
    static String norm(String s) {
        String n = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return n.replace("amazon", "").replace("plus", "");
    }
}
