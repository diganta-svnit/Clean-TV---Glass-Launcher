package tv.glasshome.launcher;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Weather cities: a search over Open-Meteo's free place names (no key), and the few used most recently. */
final class Places {

    static final class Place {
        final String name, where;
        final double lat, lon;

        Place(String name, String where, double lat, double lon) {
            this.name = name;
            this.where = where;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static final int RECENT = 6;

    private Places() {
    }

    static List<Place> search(String q) throws Exception {
        String u = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name="
                + URLEncoder.encode(q, "UTF-8");
        HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        String body;
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            body = out.toString(StandardCharsets.UTF_8.name());
        } finally {
            con.disconnect();
        }
        List<Place> out = new ArrayList<>();
        JSONArray r = new JSONObject(body).optJSONArray("results");
        for (int i = 0; r != null && i < r.length(); i++) {
            JSONObject o = r.getJSONObject(i);
            StringBuilder where = new StringBuilder();
            String a = o.optString("admin1", ""), c = o.optString("country", "");
            if (!a.isEmpty() && !a.equals(o.optString("name"))) where.append(a);
            if (!c.isEmpty()) where.append(where.length() > 0 ? ", " : "").append(c);
            out.add(new Place(o.getString("name"), where.toString(), o.getDouble("latitude"), o.getDouble("longitude")));
        }
        return out;
    }

    /** Cities picked before, newest first; the one from glass.properties until anything else is picked. */
    static List<Place> recent(SharedPreferences prefs) {
        List<Place> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("recentCities", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Place(o.getString("name"), o.optString("where", ""), o.getDouble("lat"), o.getDouble("lon")));
            }
        } catch (Exception ignored) {
        }
        if (out.isEmpty() && !BuildConfig.CITY_NAME.isEmpty()) {
            out.add(new Place(BuildConfig.CITY_NAME, "", BuildConfig.CITY_LAT, BuildConfig.CITY_LON));
        }
        return out;
    }

    static void remember(SharedPreferences prefs, Place p) {
        List<Place> list = recent(prefs);
        JSONArray a = new JSONArray();
        try {
            a.put(json(p));
            for (Place o : list) {
                if (a.length() >= RECENT) break;
                if (!(o.name.equals(p.name) && o.where.equals(p.where))) a.put(json(o));
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString("recentCities", a.toString()).apply();
    }

    private static JSONObject json(Place p) throws Exception {
        return new JSONObject().put("name", p.name).put("where", p.where).put("lat", p.lat).put("lon", p.lon);
    }
}
