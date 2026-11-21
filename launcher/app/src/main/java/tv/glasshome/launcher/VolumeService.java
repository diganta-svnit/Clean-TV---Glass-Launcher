package tv.glasshome.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * Shows the volume as a streak of light (VolumeLight) over whatever is on screen.
 *
 * Android TV hides its own volume bar when the sound goes to a soundbar over HDMI ARC ("System Audio"), yet it
 * still announces every volume change; this listens for that and draws the light. It needs the "display over
 * other apps" permission, granted over ADB: appops set tv.glasshome.launcher SYSTEM_ALERT_WINDOW allow
 * A small foreground service keeps the listener alive while another app is in front.
 */
public final class VolumeService extends Service {

    static final String PREF = "volumeLight";
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final String EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final String EXTRA_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";
    private static final String EXTRA_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_MUTED = "android.media.EXTRA_STREAM_VOLUME_MUTED";

    private AudioManager audio;
    private WindowManager wm;
    private VolumeLight light;
    private boolean attached;
    private int lastValue = -1;
    private boolean lastMuted;
    private int audible = -1;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    /** The window stays (empty, invisible) for a while after the light fades, so the next press is instant. */
    private final Runnable detachLater = this::detach;
    private static final long KEEP_MS = 20000;

    /** Starts the service when the light is switched on (the default) and the TV allows overlays. */
    static void sync(Context c) {
        boolean on = c.getSharedPreferences("home", MODE_PRIVATE).getBoolean(PREF, true);
        Intent i = new Intent(c, VolumeService.class);
        if (on && Settings.canDrawOverlays(c)) {
            try {
                c.startForegroundService(i);
            } catch (RuntimeException ignored) {
            }
        } else {
            c.stopService(i);
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // On a TV every stream follows the music stream: one press sends one of these per stream; use music's.
            // (Mute is announced for the first stream of the group, often not music, so take any stream's.)
            int type = i.getIntExtra(EXTRA_TYPE, AudioManager.STREAM_MUSIC);
            if (type != AudioManager.STREAM_MUSIC && !MUTE_CHANGED.equals(i.getAction())) return;
            int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int value = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            // The mute announcement carries the new state; asking right away can still return the old one.
            boolean muted = MUTE_CHANGED.equals(i.getAction()) ? i.getBooleanExtra(EXTRA_MUTED, false)
                    : audio.isStreamMute(AudioManager.STREAM_MUSIC);
            int prev = i.getIntExtra(EXTRA_PREV, lastValue >= 0 ? lastValue : value);
            if (VOLUME_CHANGED.equals(i.getAction())) value = i.getIntExtra(EXTRA_VALUE, value);
            // Muted, Android reports 0: keep the light at the level it will come back to, dimmed to an ember.
            if (muted && value == 0 && audible > 0) value = audible;
            // Just unmuted: Android can still report 0 for a moment; the level is the one before muting.
            if (!muted && value == 0 && MUTE_CHANGED.equals(i.getAction()) && audible > 0) value = audible;
            if (MUTE_CHANGED.equals(i.getAction()) || muted) prev = value;
            if (!muted && value > 0) audible = value;
            if (value == lastValue && muted == lastMuted && attached) return;
            lastValue = value;
            lastMuted = muted;
            show(prev / (float) max, value / (float) max, value, muted);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        audio = getSystemService(AudioManager.class);
        wm = getSystemService(WindowManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("volume", "Volume light", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "volume")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("Glass Home volume light")
                .setOngoing(true)
                .build();
        startForeground(7, n);
        IntentFilter f = new IntentFilter(VOLUME_CHANGED);
        f.addAction(MUTE_CHANGED);
        registerReceiver(receiver, f);
        lastValue = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        lastMuted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        if (lastValue > 0) audible = lastValue;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void show(float from, float to, int value, boolean muted) {
        if (!Settings.canDrawOverlays(this)) return;
        ui.removeCallbacks(detachLater);
        if (light == null) light = new VolumeLight(this, () -> {
            light.setVisibility(android.view.View.INVISIBLE);
            ui.postDelayed(detachLater, KEEP_MS);
        });
        light.setVisibility(android.view.View.VISIBLE);
        if (!attached) {
            float dp = getResources().getDisplayMetrics().density;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    Math.round(170 * dp), Math.round(320 * dp),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            lp.x = Math.round(14 * dp);
            lp.setTitle("Glass Home volume");
            try {
                wm.addView(light, lp);
                attached = true;
            } catch (RuntimeException e) {
                return;
            }
        }
        light.show(from, to, value, muted);
    }

    private void detach() {
        if (!attached) return;
        try {
            wm.removeView(light);
        } catch (RuntimeException ignored) {
        }
        attached = false;
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacks(detachLater);
        try {
            unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
        }
        detach();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
