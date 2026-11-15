package tv.glasshome.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * What was playing, on TVs where Android refuses notification listeners (low-RAM Android 9, like this Mi TV):
 * reads the system's media-session report ("dumpsys media_session") when the launcher comes back to the front.
 * Needs the DUMP permission, granted over ADB: pm grant tv.glasshome.launcher android.permission.DUMP
 * Only the media_session report is read; the result stays in the app's own storage (MediaListener's history).
 */
final class NowPlaying {

    static final class Session {
        String pkg, title, subtitle;
        int state;
        long position;
    }

    private NowPlaying() {
    }

    /** Reads the sessions and records each titled one into the Continue watching history. */
    static void capture(Context c, String self) {
        List<Session> list = read();
        if (list.isEmpty()) return;
        try {
            JSONArray old = MediaListener.load(c);
            JSONArray out = new JSONArray();
            List<String> ids = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Session s : list) {
                if (s.pkg == null || s.pkg.equals(self) || s.title == null || s.title.isEmpty()) continue;
                if (s.state == 0) continue; // STATE_NONE: nothing loaded
                String id = Integer.toHexString((s.pkg + "|" + s.title).hashCode());
                if (ids.contains(id)) continue;
                JSONObject prev = null;
                for (int i = 0; i < old.length(); i++) {
                    if (id.equals(old.getJSONObject(i).optString("id"))) prev = old.getJSONObject(i);
                }
                // A paused or stopped session keeps its time; a playing one is "now".
                long time = s.state == 3 || prev == null ? now : prev.optLong("time", now);
                JSONObject e = new JSONObject().put("id", id).put("pkg", s.pkg).put("title", s.title)
                        .put("sub", s.subtitle == null ? "" : s.subtitle).put("time", time)
                        .put("pos", Math.max(0, s.position))
                        .put("dur", prev != null ? prev.optLong("dur") : 0)
                        .put("artUri", prev != null ? prev.optString("artUri", "") : "")
                        .put("artFile", prev != null ? prev.optString("artFile", "") : "");
                out.put(e);
                ids.add(id);
            }
            for (int i = 0; i < old.length() && out.length() < 20; i++) {
                JSONObject o = old.getJSONObject(i);
                if (!ids.contains(o.optString("id"))) out.put(o);
            }
            c.getSharedPreferences(MediaListener.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(MediaListener.KEY, out.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    static List<Session> read() {
        List<Session> out = new ArrayList<>();
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "media_session").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            Session cur = null;
            for (String line; (line = r.readLine()) != null; ) {
                String t = line.trim();
                if (t.startsWith("Permission Denial")) return out;
                if (t.startsWith("package=")) {
                    cur = new Session();
                    cur.pkg = t.substring("package=".length()).trim();
                    out.add(cur);
                } else if (cur != null && t.startsWith("state=PlaybackState {")) {
                    cur.state = intAfter(t, "state=PlaybackState {state=");
                    cur.position = longAfter(t, "position=");
                } else if (cur != null && t.startsWith("metadata:") && t.contains("description=")) {
                    // MediaDescription.toString(): "title, subtitle, description" - split from the right.
                    String d = t.substring(t.indexOf("description=") + "description=".length());
                    int b = d.lastIndexOf(", ");
                    int a = b > 0 ? d.lastIndexOf(", ", b - 1) : -1;
                    String title = a > 0 ? d.substring(0, a) : d;
                    String sub = a > 0 ? d.substring(a + 2, b) : null;
                    cur.title = clean(title);
                    cur.subtitle = clean(sub);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroy();
        }
        return out;
    }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static int intAfter(String s, String key) {
        return (int) longAfter(s, key);
    }

    private static long longAfter(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) return 0;
        i += key.length();
        int j = i;
        while (j < s.length() && (Character.isDigit(s.charAt(j)) || (j == i && s.charAt(j) == '-'))) j++;
        try {
            return Long.parseLong(s.substring(i, j));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
