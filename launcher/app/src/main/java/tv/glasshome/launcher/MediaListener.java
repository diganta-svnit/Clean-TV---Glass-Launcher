package tv.glasshome.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers what was playing. Android lets a notification listener see the media sessions apps publish while they
 * play (title, episode, picture, position); this service records them, newest first, in the app's own storage on
 * the TV. Notifications themselves are never read. Access is granted over ADB:
 * cmd notification allow_listener tv.glasshome.launcher/tv.glasshome.launcher.MediaListener
 */
public class MediaListener extends NotificationListenerService {

    static final String PREFS = "watching", KEY = "history";
    private static final int MAX = 20;

    private MediaSessionManager sessions;
    private final Map<MediaController, MediaController.Callback> watched = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::track;

    @Override
    public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, MediaListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me);
            track(sessions.getActiveSessions(me));
        } catch (SecurityException ignored) {
            // Access not granted (yet).
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        for (Map.Entry<MediaController, MediaController.Callback> e : watched.entrySet()) {
            e.getKey().unregisterCallback(e.getValue());
        }
        watched.clear();
    }

    private void track(List<MediaController> controllers) {
        if (controllers == null) return;
        for (MediaController c : controllers) {
            if (getPackageName().equals(c.getPackageName())) continue;
            boolean known = false;
            for (MediaController w : watched.keySet()) {
                if (w.getSessionToken().equals(c.getSessionToken())) known = true;
            }
            if (known) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata m) {
                    record(c);
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState s) {
                    record(c);
                }

                @Override
                public void onSessionDestroyed() {
                    record(c);
                    c.unregisterCallback(this);
                    watched.remove(c);
                }
            };
            c.registerCallback(cb);
            watched.put(c, cb);
            record(c);
        }
    }

    /** Upserts the controller's current title into the history. */
    private void record(MediaController c) {
        MediaMetadata m = c.getMetadata();
        if (m == null) return;
        String title = first(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (title == null || title.trim().isEmpty()) return;
        String sub = first(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM);
        PlaybackState st = c.getPlaybackState();
        if (st == null || st.getState() == PlaybackState.STATE_NONE) return;
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
            pos += (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime())
                    * st.getPlaybackSpeed());
        }
        long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String artUri = first(m, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        String pkg = c.getPackageName();
        String id = Integer.toHexString((pkg + "|" + title).hashCode());
        String artFile = null;
        if (art != null) artFile = saveArt(this, id, art);

        try {
            JSONArray old = load(this);
            JSONArray out = new JSONArray();
            JSONObject e = new JSONObject();
            JSONObject prev = null;
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (id.equals(o.optString("id"))) prev = o;
            }
            e.put("id", id).put("pkg", pkg).put("title", title).put("sub", sub == null ? "" : sub)
                    .put("time", System.currentTimeMillis()).put("pos", Math.max(0, pos)).put("dur", Math.max(0, dur))
                    .put("artUri", artUri != null ? artUri : prev != null ? prev.optString("artUri", "") : "")
                    .put("artFile", artFile != null ? artFile : prev != null ? prev.optString("artFile", "") : "");
            out.put(e);
            for (int i = 0; i < old.length() && out.length() < MAX; i++) {
                JSONObject o = old.getJSONObject(i);
                if (!id.equals(o.optString("id"))) out.put(o);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY, out.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    static JSONArray load(Context c) {
        try {
            return new JSONArray(c.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static String saveArt(Context c, String id, Bitmap b) {
        File dir = new File(c.getFilesDir(), "nowplaying");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, id + ".jpg");
        int w = Math.min(480, b.getWidth());
        Bitmap small = w < b.getWidth() ? Bitmap.createScaledBitmap(b, w, Math.max(1, b.getHeight() * w / b.getWidth()), true) : b;
        try (FileOutputStream out = new FileOutputStream(f)) {
            small.compress(Bitmap.CompressFormat.JPEG, 85, out);
            return f.getPath();
        } catch (Exception e) {
            return null;
        }
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    static List<String> keysOf(JSONArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.optJSONObject(i).optString("id"));
        return out;
    }
}
