package tv.glasshome.launcher;

import android.graphics.Bitmap;

/** A few passes of box blur on a tiny bitmap (160×90): close to a Gaussian, and well under a millisecond. */
final class BoxBlur {

    private BoxBlur() {
    }

    static Bitmap blur(Bitmap src, int radius, int passes) {
        int w = src.getWidth(), h = src.getHeight();
        int[] a = new int[w * h], b = new int[w * h];
        src.getPixels(a, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            pass(a, b, w, h, radius, true);
            pass(b, a, w, h, radius, false);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(a, 0, w, 0, 0, w, h);
        return out;
    }

    /** One box pass along rows (horizontal) or columns, edges clamped. */
    private static void pass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h, div = r * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[index(line, clamp(i, len), w, horizontal)];
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[index(line, i, w, horizontal)] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = in[index(line, clamp(i + r + 1, len), w, horizontal)];
                int sub = in[index(line, clamp(i - r, len), w, horizontal)];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : i >= len ? len - 1 : i;
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
