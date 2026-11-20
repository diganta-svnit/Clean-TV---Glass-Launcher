package tv.glasshome.launcher;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;

import java.util.ArrayList;
import java.util.List;

/**
 * Android TV's Watch Next list (what each streaming app says you are in the middle of), read with the
 * READ_TV_LISTINGS permission. Each entry carries the app's own link back to the exact title.
 */
final class WatchNext {

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
        } catch (RuntimeException e) {
            // No READ_TV_LISTINGS, or the TV has no Watch Next: skip this source.
        }
        return out;
    }

    private static int parse(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
