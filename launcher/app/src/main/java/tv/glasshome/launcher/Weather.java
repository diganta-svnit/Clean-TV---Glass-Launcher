package tv.glasshome.launcher;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;

/** Current weather and today's sunrise and sunset from Open-Meteo (no key), plus what the launcher derives from it. */
final class Weather {

    /** The forecast model: ECMWF's global model, which tracked Apple Weather more closely than Open-Meteo's default in testing. */
    static final String MODEL = "ecmwf_ifs025";

    final double temp;
    final int code;
    final double wind;
    final long sunrise;
    final long sunset;

    private Weather(double temp, int code, double wind, long sunrise, long sunset) {
        this.temp = temp;
        this.code = code;
        this.wind = wind;
        this.sunrise = sunrise;
        this.sunset = sunset;
    }

    static String download(double lat, double lon) throws IOException {
        String u = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,weather_code,wind_speed_10m&daily=sunrise,sunset"
                + "&timezone=auto&timeformat=unixtime&forecast_days=1&models=" + MODEL, lat, lon);
        HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        try (InputStream in = con.getInputStream()) {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            con.disconnect();
        }
    }

    static Weather parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        JSONObject cur = o.getJSONObject("current");
        JSONObject daily = o.getJSONObject("daily");
        return new Weather(cur.getDouble("temperature_2m"), cur.getInt("weather_code"),
                cur.optDouble("wind_speed_10m", 0),
                daily.getJSONArray("sunrise").getLong(0) * 1000L,
                daily.getJSONArray("sunset").getLong(0) * 1000L);
    }

    /** The wallpaper weather: clear, cloudy, mist, rain or windy (WMO codes). */
    String condition() {
        String c;
        if (code <= 1) c = "clear";
        else if (code == 45 || code == 48) c = "mist";
        else if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95) c = "rain";
        else c = "cloudy"; // 2, 3, snow
        if (wind >= 30 && (c.equals("clear") || c.equals("cloudy"))) c = "windy";
        return c;
    }

    String label() {
        if (code == 0) return "Clear";
        if (code == 1) return "Mainly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 85 && code <= 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "—";
    }

    String glyph(boolean night) {
        if (code <= 1) return night ? "☾" : "☀️";
        if (code == 2) return night ? "☁️" : "⛅";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 95) return "⛈️";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄️";
        return "🌧️";
    }

    /** morning, day, evening or night, from the real sunrise and sunset when known. */
    static String timeOfDay(long now, Weather w) {
        int m = minuteOfDay(now);
        if (w != null && w.sunrise > 0 && w.sunset > w.sunrise) {
            int rise = minuteOfDay(w.sunrise), set = minuteOfDay(w.sunset);
            if (m >= rise - 45 && m < rise + 150) return "morning";
            if (m >= rise + 150 && m < set - 90) return "day";
            if (m >= set - 90 && m < set + 40) return "evening";
            return "night";
        }
        int h = m / 60;
        if (h >= 5 && h < 9) return "morning";
        if (h >= 9 && h < 16) return "day";
        if (h >= 16 && h < 19) return "evening";
        return "night";
    }

    private static int minuteOfDay(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }
}
