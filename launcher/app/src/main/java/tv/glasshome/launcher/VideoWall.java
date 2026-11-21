package tv.glasshome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;

/**
 * The moving wallpaper: a looping, silent clip on a TextureView, with its poster frame on top until the first video
 * frame is drawn (and while a new clip loads).
 *
 * The glass follows the video with the clip's GlassTrack: its frames, prepared once, small and blurred, shown in step
 * with the video (up to 30 a second) from one reused bitmap. A clip without a track yet gets one built in the
 * background the first time it plays; until then, and on Android 8, the glass copies a frame from the video every
 * 1.5 s instead (copying more often stalls the GPU on budget TVs). When paused, the glass shows the frozen frame.
 */
final class VideoWall implements TextureView.SurfaceTextureListener {

    interface BlurSink {
        void onBlur(Bitmap blurred);
    }

    private static final int GRAB_W = 160, GRAB_H = 90;
    /** Glass refresh: up to 30 a second from a glass track; every 1.5 s when it has to copy from the video. */
    static final long GLASS_MS = 33, COPY_MS = 1500;

    private final TextureView texture;
    private final ImageView poster;
    private final BlurSink sink;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Context ctx;
    private GlassTrack track;
    private volatile File trackFor, allDir;
    private volatile boolean builtAll;
    private final java.util.concurrent.ExecutorService builder = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "glass-track");
        t.setDaemon(true);
        return t;
    });
    private MediaPlayer player;
    private Surface surface;
    private File video;
    private boolean prepared, rendering, wantPlay = true, fadeOnFrame;
    private long lastGrab;
    // Reused for every glass refresh: the frame copy and the blurred result.
    private final Bitmap grabBuf = Bitmap.createBitmap(GRAB_W, GRAB_H, Bitmap.Config.ARGB_8888);
    private final BoxBlur.Reusable blurBuf = new BoxBlur.Reusable(GRAB_W, GRAB_H);

    VideoWall(Context c, FrameLayout root, BlurSink sink) {
        this.sink = sink;
        ctx = c.getApplicationContext();
        texture = new TextureView(c);
        texture.setSurfaceTextureListener(this);
        texture.setOpaque(true);
        poster = new ImageView(c);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        root.addView(poster, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Shows a new wallpaper: its poster fades in over the old picture, then its clip (if any) takes over. */
    void show(Bitmap still, File clip) {
        poster.animate().cancel();
        boolean first = poster.getDrawable() == null;
        poster.setImageBitmap(still);
        if (first) {
            poster.setAlpha(1f);
            start(clip);
        } else {
            poster.setAlpha(0f);
            poster.animate().alpha(1f).setDuration(900).withEndAction(() -> start(clip)).start();
        }
    }

    /** Plays in the first home state; pauses (and refreshes the glass from the frozen frame) everywhere else. */
    void setPlaying(boolean play) {
        if (play == wantPlay) return;
        wantPlay = play;
        if (player == null || !prepared) return;
        if (play) {
            player.start();
            // If the poster is covering the screen (we came back paused), lift it once a new frame is drawn.
            if (poster.getAlpha() > 0f) fadeOnFrame = true;
        } else {
            player.pause();
            // The frame on screen is now fixed: one grab makes the glass match it.
            ui.postDelayed(this::grab, 60);
        }
    }

    /** Activity went to the background: free the decoder; the poster covers the screen until playback is back. */
    void stop() {
        release();
        poster.animate().cancel();
        poster.setAlpha(1f);
    }

    void resume() {
        if (video != null && player == null) start(video);
    }

    private void start(File clip) {
        video = clip;
        if (clip != null && !clip.equals(trackFor)) loadTrack(clip);
        release();
        if (clip == null || !clip.isFile() || surface == null) return;
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(clip.getPath());
            mp.setSurface(surface);
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                if (p != player) return;
                prepared = true;
                p.start();
                // Paused states still need a picture: start, show the first frame, then pause.
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (p == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    rendering = true;
                    if (!wantPlay) {
                        // Paused state (tray open): keep the poster up; a paused TextureView can come back
                        // empty after the launcher was in the background.
                        p.pause();
                    } else {
                        poster.animate().cancel();
                        poster.animate().alpha(0f).setDuration(700).withEndAction(null).start();
                    }
                }
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (p == player) {
                    release();
                    poster.setAlpha(1f);
                }
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            release();
        }
    }

    private void release() {
        prepared = false;
        rendering = false;
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
            player = null;
        }
    }

    /** Loads the clip's glass track, building it first (in the background) if it doesn't exist yet. */
    private void loadTrack(File clip) {
        track = null;
        trackFor = clip;
        builder.execute(() -> {
            if (!clip.equals(trackFor)) return;
            File f = GlassTrack.fileFor(ctx, clip);
            GlassTrack t = GlassTrack.load(f);
            if (t == null && clip.isFile() && GlassTrack.build(clip, f, () -> !clip.equals(trackFor))) {
                t = GlassTrack.load(f);
            }
            GlassTrack done = t;
            if (done != null) ui.post(() -> {
                if (clip.equals(trackFor)) track = done;
            });
            buildAll(); // then every other clip without a track, once
        });
    }

    /**
     * Queues glass tracks for every clip in the folder (after the one playing), so any wallpaper is smooth when picked,
     * and deletes tracks whose clip is gone or changed. Clips added later are handled the same way the next time.
     */
    void prepareAll(File dir) {
        allDir = dir;
        if (trackFor == null) builder.execute(this::buildAll); // nothing playing yet: start right away
    }

    /** Runs on the builder thread after the clip on screen has its track. */
    private void buildAll() {
        File dir = allDir;
        if (dir == null || builtAll) return;
        builtAll = true;
        File[] clips = dir.listFiles((d, n) -> n.endsWith(".mp4"));
        if (clips == null) return;
        java.util.List<File> keep = new java.util.ArrayList<>();
        for (File clip : clips) keep.add(GlassTrack.fileFor(ctx, clip));
        GlassTrack.prune(ctx, dir, keep);
        for (File clip : clips) {
            File f = GlassTrack.fileFor(ctx, clip);
            if (!f.isFile()) GlassTrack.build(clip, f, () -> false);
        }
    }

    private void grab() {
        if (track != null && player != null && prepared) {
            try {
                sink.onBlur(track.frameAt(player.getCurrentPosition(), player.getDuration()));
                return;
            } catch (IllegalStateException ignored) {
                // The player is changing state: copy from the video this once.
            }
        }
        if (!rendering || !texture.isAvailable()) return;
        // Copies the current frame into the reused buffer (scaled to 160×90 by the GPU), then blurs it in place.
        Bitmap b = texture.getBitmap(grabBuf);
        if (b != null) sink.onBlur(blurBuf.blur(b, 4, 3));
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        if (video != null && player == null) start(video);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release();
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
        // A new video frame: refresh the glass from it, at most 30 times a second.
        long now = android.os.SystemClock.uptimeMillis();
        if (wantPlay && rendering && now - lastGrab >= (track != null ? GLASS_MS : COPY_MS)) {
            lastGrab = now;
            grab();
        }
        if (fadeOnFrame && wantPlay) {
            fadeOnFrame = false;
            poster.animate().cancel();
            poster.animate().alpha(0f).setDuration(500).withEndAction(null).start();
        }
    }
}
