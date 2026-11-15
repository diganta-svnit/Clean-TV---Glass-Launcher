package tv.glasshome.launcher;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Android TV's Watch Next list (what each streaming app says you are in the middle of), read with the
 * READ_TV_LISTINGS permission. Each entry carries the app's own link back to the exact title.
 */
final class WatchNext {

    static final String TAG = "GlassWatchNext";

    static final class Entry {
        String pkg, title, episode, poster, intentUri;
        int type, season, number;
        long lastEngaged, position, duration;
        boolean browsable;
    }

    private WatchNext() {
    }

    static List<Entry> read(Context c) {
        List<Entry> out = new ArrayList<>();
        String[] cols = {
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_BROWSABLE};
        try (Cursor cur = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, cols,
                null, null, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            if (cur == null) {
                Log.i(TAG, "query returned null");
                return out;
            }
            while (cur.moveToNext()) {
                Entry e = new Entry();
                e.pkg = cur.getString(0);
                e.title = cur.getString(1);
                e.episode = cur.getString(2);
                e.season = parse(cur.getString(3));
                e.number = parse(cur.getString(4));
                e.poster = cur.getString(5);
                e.intentUri = cur.getString(6);
                e.type = cur.isNull(7) ? -1 : cur.getInt(7);
                e.lastEngaged = cur.isNull(8) ? 0 : cur.getLong(8);
                e.position = cur.isNull(9) ? 0 : cur.getLong(9);
                e.duration = cur.isNull(10) ? 0 : cur.getLong(10);
                e.browsable = cur.isNull(11) || cur.getInt(11) == 1;
                out.add(e);
            }
        } catch (SecurityException e) {
            Log.i(TAG, "not allowed: " + e.getMessage());
        } catch (RuntimeException e) {
            Log.i(TAG, "query failed: " + e);
        }
        return out;
    }

    /** One line per entry in logcat, for checking what each app publishes. */
    static void logAll(Context c) {
        List<Entry> all = read(c);
        Log.i(TAG, "entries: " + all.size());
        for (Entry e : all) {
            Log.i(TAG, e.pkg + " | " + e.title + (e.episode != null ? " | S" + e.season + "E" + e.number + " " + e.episode : "")
                    + " | type=" + e.type + " | pos=" + e.position / 60000 + "/" + e.duration / 60000 + " min"
                    + " | browsable=" + e.browsable + " | poster=" + (e.poster != null)
                    + " | link=" + (e.intentUri != null) + " | engaged=" + new java.util.Date(e.lastEngaged));
        }
    }

    /** Test probe: what each app publishes in its own home-screen channels (preview programs). */
    static void logChannels(Context c) {
        java.util.Map<String, Integer> perPkg = new java.util.TreeMap<>();
        String[] cols = {TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME, TvContract.PreviewPrograms.COLUMN_TITLE,
                TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
                TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                TvContract.PreviewPrograms.COLUMN_CHANNEL_ID};
        try (Cursor cur = c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, cols, null, null, null)) {
            if (cur == null) {
                Log.i(TAG, "preview: null cursor");
                return;
            }
            while (cur.moveToNext()) {
                String pkg = cur.getString(0);
                perPkg.merge(pkg, 1, Integer::sum);
                long pos = cur.isNull(3) ? 0 : cur.getLong(3), dur = cur.isNull(4) ? 0 : cur.getLong(4);
                String desc = cur.getString(2);
                if (pos > 0 || (desc != null && desc.toLowerCase(java.util.Locale.ROOT).contains("continue"))) {
                    Log.i(TAG, "preview progress: " + pkg + " | " + cur.getString(1) + " | " + desc + " | pos="
                            + pos / 1000 + "s/" + dur / 1000 + "s | link=" + (cur.getString(5) != null)
                            + " | channel=" + cur.getLong(6));
                }
            }
            Log.i(TAG, "preview per app: " + perPkg);
        } catch (RuntimeException e) {
            Log.i(TAG, "preview failed: " + e);
        }
        Log.i(TAG, "watch next now: " + read(c).size());
    }

    /** Test probe: every app's home-screen channels and how many programs each holds. */
    static void logChannelNames(Context c) {
        java.util.Map<Long, Integer> counts = new java.util.HashMap<>();
        try (Cursor p = c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,
                new String[]{TvContract.PreviewPrograms.COLUMN_CHANNEL_ID}, null, null, null)) {
            while (p != null && p.moveToNext()) counts.merge(p.getLong(0), 1, Integer::sum);
        } catch (RuntimeException e) {
            Log.i(TAG, "programs failed " + e);
        }
        try (Cursor ch = c.getContentResolver().query(TvContract.Channels.CONTENT_URI,
                new String[]{TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME,
                        TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_TYPE,
                        TvContract.Channels.COLUMN_BROWSABLE}, null, null, null)) {
            java.util.Map<String, java.util.Map<String, Integer>> byPkg = new java.util.TreeMap<>();
            while (ch != null && ch.moveToNext()) {
                if (!TvContract.Channels.TYPE_PREVIEW.equals(ch.getString(3))) continue;
                Integer n = counts.get(ch.getLong(0));
                byPkg.computeIfAbsent(ch.getString(1), k -> new java.util.TreeMap<>())
                        .merge(ch.getString(2) + (ch.getInt(4) == 1 ? "" : " (hidden)"), n == null ? 0 : n, Integer::sum);
            }
            for (java.util.Map.Entry<String, java.util.Map<String, Integer>> e : byPkg.entrySet()) {
                Log.i(TAG, "channels " + e.getKey() + " -> " + e.getValue());
            }
        } catch (RuntimeException e) {
            Log.i(TAG, "channels failed " + e);
        }
    }

    static void logPrograms(Context c, String pkgs) {
        try (Cursor p = c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,
                new String[]{TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME, TvContract.PreviewPrograms.COLUMN_TITLE,
                        TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
                        TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                        TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.COLUMN_INTENT_URI}, null, null, null)) {
            while (p != null && p.moveToNext()) {
                if (!pkgs.contains(p.getString(0))) continue;
                String d = p.getString(2) == null ? "" : p.getString(2);
                String u = p.getString(5) == null ? "" : p.getString(5);
                Log.i(TAG, "prog " + p.getString(0).replaceAll(".*\\.", "") + " | " + p.getString(1) + " | "
                        + d.substring(0, Math.min(60, d.length())) + " | pos=" + p.getLong(3) + " | type=" + p.getInt(4)
                        + " | uri=" + u.substring(0, Math.min(70, u.length())));
            }
        } catch (RuntimeException e) {
            Log.i(TAG, "programs failed " + e);
        }
    }

    private static int parse(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
