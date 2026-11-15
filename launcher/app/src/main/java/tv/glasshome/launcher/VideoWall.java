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
 * The moving wallpaper: a looping, silent 720p clip on a TextureView, with its poster frame on top until the first
 * video frame is drawn (and while a new clip loads). While it plays, a small frame is grabbed every 1.5 s and blurred
 * for the glass; when paused, one fresh frame is grabbed so the glass matches the frozen picture exactly.
 */
final class VideoWall implements TextureView.SurfaceTextureListener {

    interface BlurSink {
        void onBlur(Bitmap blurred);
    }

    private static final int GRAB_W = 160, GRAB_H = 90, GRAB_MS = 1500;

    private final TextureView texture;
    private final ImageView poster;
    private final BlurSink sink;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Surface surface;
    private File video;
    private boolean prepared, rendering, wantPlay = true, fadeOnFrame;

    private final Runnable grabLoop = new Runnable() {
        @Override
        public void run() {
            grab();
            if (wantPlay && rendering) ui.postDelayed(this, GRAB_MS);
        }
    };

    VideoWall(Context c, FrameLayout root, BlurSink sink) {
        this.sink = sink;
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
        ui.removeCallbacks(grabLoop);
        if (player == null || !prepared) return;
        if (play) {
            player.start();
            // If the poster is covering the screen (we came back paused), lift it once a new frame is drawn.
            if (poster.getAlpha() > 0f) fadeOnFrame = true;
            ui.postDelayed(grabLoop, GRAB_MS);
        } else {
            player.pause();
            // The frame on screen is now fixed: one grab makes the glass match it.
            ui.postDelayed(this::grab, 60);
        }
    }

    /** Activity went to the background: free the decoder; the poster covers the screen until playback is back. */
    void stop() {
        ui.removeCallbacks(grabLoop);
        release();
        poster.animate().cancel();
        poster.setAlpha(1f);
    }

    void resume() {
        if (video != null && player == null) start(video);
    }

    private void start(File clip) {
        video = clip;
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
                        ui.removeCallbacks(grabLoop);
                        ui.postDelayed(grabLoop, 200);
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

    private void grab() {
        if (!rendering || !texture.isAvailable()) return;
        Bitmap b = texture.getBitmap(GRAB_W, GRAB_H);
        if (b != null) sink.onBlur(BoxBlur.blur(b, 4, 3));
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
        ui.removeCallbacks(grabLoop);
        release();
        if (surface != null) surface.release();
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
        if (fadeOnFrame && wantPlay) {
            fadeOnFrame = false;
            poster.animate().cancel();
            poster.animate().alpha(0f).setDuration(500).withEndAction(null).start();
        }
    }
}
