package tv.glasshome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.os.Build;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The glass for a wallpaper clip, prepared once: every frame of the clip, shrunk to 160×90 and blurred, stored as
 * a run of small JPEGs in a .glass file next to the clip (wallpapers/convert.sh makes it; so does the TV). While the clip plays, the glass shows the frame that
 * matches the video's position, decoded into one reused bitmap.
 *
 * Why: copying frames back out of the GPU (TextureView.getBitmap) stalls the drawing on budget TV chips, so the
 * glass could only follow the video every 1.5 s. Decoding a 160×90 JPEG takes a millisecond or two on the CPU.
 *
 * Any clip without one gets it automatically: the TV builds it in the background (Android 9+; slow, once per clip),
 * the clip on screen first. A replaced clip gets a new track and the old one is deleted.
 */
final class GlassTrack {

    static final int W = 160, H = 90;
    private static final int BATCH = 4;

    private final byte[] data;
    private final int[] offsets, lengths;
    private final Bitmap frame;
    private final BitmapFactory.Options opts = new BitmapFactory.Options();
    private int shown = -1;

    private GlassTrack(byte[] data, List<int[]> spans) {
        this.data = data;
        offsets = new int[spans.size()];
        lengths = new int[spans.size()];
        for (int i = 0; i < spans.size(); i++) {
            offsets[i] = spans.get(i)[0];
            lengths[i] = spans.get(i)[1];
        }
        frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        opts.inMutable = true;
        opts.inBitmap = frame;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
    }

    int frames() {
        return offsets.length;
    }

    /** The blurred frame for this point of the clip (the same bitmap every time, redrawn in place). */
    Bitmap frameAt(long positionMs, long durationMs) {
        if (durationMs <= 0) return frame;
        int i = (int) ((positionMs % durationMs) * offsets.length / durationMs);
        i = Math.max(0, Math.min(offsets.length - 1, i));
        if (i != shown) {
            try {
                BitmapFactory.decodeByteArray(data, offsets[i], lengths[i], opts);
                shown = i;
            } catch (IllegalArgumentException ignored) {
                // A frame that doesn't fit the reused bitmap: keep the last one.
            }
        }
        return frame;
    }

    /**
     * A clip's track sits next to it: <name>-<clip size in bytes>.glass. The size ties it to that exact clip; a
     * replaced clip (new size) gets a new track, and the old one is pruned. wallpapers/convert.sh makes the same.
     */
    static File fileFor(Context c, File clip) {
        String name = clip.getName().replaceAll("\\.[^.]*$", "");
        return new File(clip.getParentFile(), name + "-" + clip.length() + ".glass");
    }

    /** Loads a finished track, or null. */
    static GlassTrack load(File f) {
        if (!f.isFile() || f.length() < 100) return null;
        byte[] d = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int n = 0;
            while (n < d.length) {
                int r = in.read(d, n, d.length - n);
                if (r < 0) break;
                n += r;
            }
            if (n != d.length) return null;
        } catch (IOException e) {
            return null;
        }
        // The file is JPEGs back to back: each starts with FF D8 and ends with FF D9.
        List<int[]> spans = new ArrayList<>();
        int start = -1;
        for (int i = 0; i + 1 < d.length; i++) {
            if ((d[i] & 0xFF) != 0xFF) continue;
            int m = d[i + 1] & 0xFF;
            if (m == 0xD8 && start < 0) {
                start = i;
            } else if (m == 0xD9 && start >= 0) {
                spans.add(new int[]{start, i + 2 - start});
                start = -1;
                i++;
            }
        }
        return spans.isEmpty() ? null : new GlassTrack(d, spans);
    }

    /**
     * Builds the track for a clip (slow: runs on a background thread). Needs Android 9 for decoding frames in order;
     * returns false where it can't, and the glass keeps its 1.5 s refresh for that clip. cancelled is polled.
     */
    static boolean build(File clip, File out, java.util.function.BooleanSupplier cancelled) {
        if (Build.VERSION.SDK_INT < 28) return false;
        File dir = out.getParentFile();
        if (dir == null || !dir.isDirectory()) return false;
        File tmp = new File(out.getPath() + ".part");
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        Bitmap small = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(small);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        Rect dst = new Rect(0, 0, W, H);
        BoxBlur.Reusable blur = new BoxBlur.Reusable(W, H);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(4096);
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp))) {
            r.setDataSource(clip.getPath());
            String fc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
            int count = fc == null ? 0 : Integer.parseInt(fc);
            if (count <= 0) return false;
            MediaMetadataRetriever.BitmapParams p = new MediaMetadataRetriever.BitmapParams();
            p.setPreferredConfig(Bitmap.Config.RGB_565); // half the memory of the full-size frames while shrinking
            for (int i = 0; i < count; i += BATCH) {
                if (cancelled.getAsBoolean()) return false;
                List<Bitmap> batch = r.getFramesAtIndex(i, Math.min(BATCH, count - i), p);
                for (Bitmap b : batch) {
                    canvas.drawBitmap(b, null, dst, paint);
                    b.recycle(); // the full-size frame is freed at once
                    jpeg.reset();
                    blur.blur(small, 4, 3).compress(Bitmap.CompressFormat.JPEG, 72, jpeg);
                    jpeg.writeTo(os);
                }
            }
        } catch (Exception | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
            small.recycle();
        }
        return tmp.renameTo(out);
    }

    /** Deletes tracks (and unfinished ones) in the folder that belong to no clip there now. */
    static void prune(Context c, File dir, List<File> keep) {
        File[] all = dir == null ? null : dir.listFiles((d, n) -> n.endsWith(".glass") || n.endsWith(".glass.part"));
        if (all == null) return;
        for (File f : all) {
            if (!keep.contains(f)) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }
}
