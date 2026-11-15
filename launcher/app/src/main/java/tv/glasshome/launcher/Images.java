package tv.glasshome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Poster loading: memory cache, then a disk cache in the app's cache folder, then the network. */
final class Images {

    private final File dir;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(10 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private final android.content.ContentResolver resolver;

    Images(Context c) {
        resolver = c.getContentResolver();
        dir = new File(c.getCacheDir(), "posters");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    void load(String url, ImageView into) {
        into.setTag(url);
        Bitmap hit = memory.get(url);
        if (hit != null) {
            into.setImageBitmap(hit);
            return;
        }
        into.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap b = fetch(url);
            if (b == null) return;
            memory.put(url, b);
            ui.post(() -> {
                if (url.equals(into.getTag())) {
                    into.setAlpha(0f);
                    into.setImageBitmap(b);
                    into.animate().alpha(1f).setDuration(200).start();
                }
            });
        });
    }

    private Bitmap fetch(String url) {
        // Local files (pictures saved by MediaListener) and content:// URIs from apps' rows.
        if (url.startsWith("/")) {
            // Downsample big local pictures (1280x720 wallpaper posters) to tile size: memory on a 1 GB TV.
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(url, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 400) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(url, o);
        }
        if (url.startsWith("content:") || url.startsWith("android.resource:")) {
            try (InputStream in = resolver.openInputStream(android.net.Uri.parse(url))) {
                return BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                return null;
            }
        }
        File f = new File(dir, Integer.toHexString(url.hashCode()) + ".jpg");
        if (f.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return b;
        }
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            } finally {
                con.disconnect();
            }
            return BitmapFactory.decodeFile(f.getPath());
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }
}
