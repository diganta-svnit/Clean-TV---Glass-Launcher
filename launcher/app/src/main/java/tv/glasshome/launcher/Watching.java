package tv.glasshome.launcher;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Continue watching, from what this TV can actually see, newest first:
 * 0. Android's Watch Next list (YouTube, Apple TV, Prime Video, JioHotstar...): title, picture, progress, exact link;
 * 1. titles that were playing (MediaListener's history: real names, pictures and progress; reopens the app);
 * 2. "Continue Watching" cards an app puts in its own home-screen row (Netflix): open the exact episode;
 * 3. video apps used recently that neither of the above covers: reopen the app.
 */
final class Watching {

    static final class Item {
        String pkg, appLabel, title, subtitle, image;   // image: http(s)/content URI or a local file path
        Intent open;
        long time, pos, dur;
        boolean appOnly;
        Apps.Entry app;

        float progress() {
            return dur > 0 && pos > 0 ? Math.min(1f, pos / (float) dur) : 0f;
        }

        String meta() {
            StringBuilder b = new StringBuilder(appLabel);
            if (dur > 0 && pos > 0) {
                long left = Math.max(0, (dur - pos) / 60000);
                b.append("  ·  ").append(left > 0 ? left + " min left" : "almost done");
            } else if (appOnly) {
                b.append("  ·  used ").append(ago(time));
            } else if (time > 0) {
                b.append("  ·  ").append(ago(time));
            }
            return b.toString();
        }
    }

    private static final String[] MUSIC = {"spotify", "music", "saavn", "gaana", "wynk", "radio", "podcast"};
    private static final long RECENT_APP_DAYS = 14;
    private static final int MAX = 7;
    /** Watch Next: at most this many per app, so one app can't fill the row, and nothing older than this. */
    private static final int PER_APP = 3;
    private static final long WATCH_NEXT_DAYS = 90;

    private Watching() {
    }

    static boolean isMusic(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        for (String m : MUSIC) if (l.contains(m)) return true;
        return false;
    }

    static List<Item> load(Context c, List<Apps.Entry> apps, Set<String> videoPkgs) {
        Map<String, Apps.Entry> byPkg = new HashMap<>();
        for (Apps.Entry e : apps) byPkg.put(e.pkg, e);
        Map<String, Long> lastUsed = lastUsed(c);
        List<Item> out = new ArrayList<>();
        Set<String> titles = new HashSet<>();
        Set<String> covered = new HashSet<>();

        // 0. Android's Watch Next list: apps (YouTube, Apple TV, Prime Video, JioHotstar, Sony LIV...) put what you
        //    are part-way through there, with a picture, progress and a link to the exact title.
        Map<String, Integer> perApp = new HashMap<>();
        long oldest = System.currentTimeMillis() - WATCH_NEXT_DAYS * 24 * 3600 * 1000L;
        for (WatchNext.Entry e : WatchNext.read(c)) {
            Apps.Entry app = byPkg.get(e.pkg);
            if (app == null || e.title == null || !e.browsable || isMusic(app.label)) continue;
            boolean cont = e.type == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE;
            boolean next = e.type == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT;
            if (!cont && !next) continue; // "new" and watch-list entries are suggestions, not something started
            if (cont && e.duration > 0 && e.position >= e.duration * 0.95) continue; // finished
            if (e.lastEngaged < oldest || perApp.getOrDefault(e.pkg, 0) >= PER_APP) continue;
            Item it = new Item();
            it.pkg = app.pkg;
            it.app = app;
            it.appLabel = app.label;
            it.title = e.title;
            String ep = e.season > 0 && e.number > 0 ? "S" + e.season + " E" + e.number : e.number > 0 ? "Episode " + e.number : "";
            if (e.episode != null && !e.episode.isEmpty()) ep = ep.isEmpty() ? e.episode : ep + "  ·  " + e.episode;
            it.subtitle = next ? (ep.isEmpty() ? "Next episode" : "Next: " + ep) : ep;
            it.image = e.poster;
            it.time = e.lastEngaged;
            it.pos = cont ? e.position : 0;
            it.dur = e.duration;
            it.open = parse(e.intentUri, app.launch);
            if (titles.add(key(it.title))) {
                out.add(it);
                covered.add(it.pkg);
                perApp.merge(e.pkg, 1, Integer::sum);
            }
        }

        // 1. What was playing.
        JSONArray h = MediaListener.load(c);
        for (int i = 0; i < h.length(); i++) {
            JSONObject o = h.optJSONObject(i);
            Apps.Entry app = byPkg.get(o.optString("pkg"));
            if (app == null || isMusic(app.label)) continue;
            Item it = new Item();
            it.pkg = app.pkg;
            it.app = app;
            it.appLabel = app.label;
            it.title = o.optString("title");
            it.subtitle = o.optString("sub");
            String file = o.optString("artFile", ""), uri = o.optString("artUri", "");
            it.image = !file.isEmpty() ? file : !uri.isEmpty() ? uri : null;
            it.time = o.optLong("time");
            it.pos = o.optLong("pos");
            it.dur = o.optLong("dur");
            it.open = app.launch;
            if (titles.add(key(it.title))) {
                out.add(it);
                covered.add(it.pkg);
            }
        }

        // 2. "Continue Watching" cards in apps' own rows.
        String[] cols = {TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME, TvContract.PreviewPrograms.COLUMN_TITLE,
                TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS};
        try (Cursor cur = c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, cols, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String pkg = cur.getString(0), title = cur.getString(1), desc = cur.getString(2);
                long pos = cur.isNull(5) ? 0 : cur.getLong(5), dur = cur.isNull(6) ? 0 : cur.getLong(6);
                boolean cont = pos > 0 || contains(desc, "continue watching") || contains(title, "continue watching");
                Apps.Entry app = byPkg.get(pkg);
                if (!cont || app == null || title == null || isMusic(app.label)) continue;
                Item it = new Item();
                it.pkg = pkg;
                it.app = app;
                it.appLabel = app.label;
                it.title = title;
                it.subtitle = desc == null ? "" : desc.replaceFirst("(?i)^continue watching\\s*", "");
                it.image = cur.getString(3);
                it.pos = pos;
                it.dur = dur;
                Long used = lastUsed.get(pkg);
                it.time = used != null ? used : 0;
                it.open = parse(cur.getString(4), app.launch);
                if (titles.add(key(title))) {
                    out.add(it);
                    covered.add(pkg);
                }
            }
        } catch (RuntimeException ignored) {
            // No READ_TV_LISTINGS: skip this source.
        }

        // 3. Video apps used in the last two weeks.
        long since = System.currentTimeMillis() - RECENT_APP_DAYS * 24 * 3600 * 1000L;
        for (Map.Entry<String, Long> u : lastUsed.entrySet()) {
            Apps.Entry app = byPkg.get(u.getKey());
            if (app == null || covered.contains(app.pkg) || u.getValue() < since) continue;
            if (!videoPkgs.contains(app.pkg) || isMusic(app.label)) continue;
            Item it = new Item();
            it.pkg = app.pkg;
            it.app = app;
            it.appLabel = app.label;
            it.title = app.label;
            it.subtitle = "Pick up where you left off in the app";
            it.time = u.getValue();
            it.open = app.launch;
            it.appOnly = true;
            out.add(it);
        }

        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
    }

    private static Map<String, Long> lastUsed(Context c) {
        Map<String, Long> out = new HashMap<>();
        if (!Apps.hasUsageAccess(c)) return out;
        try {
            UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
            long now = System.currentTimeMillis();
            Map<String, UsageStats> m = usm.queryAndAggregateUsageStats(now - 30L * 24 * 3600 * 1000, now);
            for (UsageStats s : m.values()) {
                if (s.getLastTimeUsed() > 0 && s.getTotalTimeInForeground() > 60_000) {
                    out.put(s.getPackageName(), s.getLastTimeUsed());
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private static Intent parse(String uri, Intent fallback) {
        if (uri == null) return fallback;
        try {
            return Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean contains(String s, String what) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(what);
    }

    private static String key(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (t <= 0) return "";
        if (m < 2) return "just now";
        if (m < 60) return m + " min ago";
        long h = m / 60;
        if (h < 24) return h + " h ago";
        long d = h / 24;
        return d == 1 ? "yesterday" : d + " days ago";
    }
}
