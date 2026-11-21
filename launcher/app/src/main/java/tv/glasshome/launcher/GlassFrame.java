package tv.glasshome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.widget.FrameLayout;

/**
 * A pane of frosted glass. It paints the blurred wallpaper that lies under its current position on screen,
 * so it stays correct while it moves, then a white veil, a top sheen and a thin bright rim.
 *
 * The blurred wallpaper is one small shared bitmap (made once per wallpaper), scaled up by the GPU.
 * Nothing is blurred per frame.
 */
public class GlassFrame extends FrameLayout {

    private static Bitmap blur;
    private static int screenW = 1, screenH = 1;
    private static int generation;

    /** Sets the blurred wallpaper every pane samples from; null falls back to a plain smoked fill. */
    static void setBlur(Bitmap b, int w, int h) {
        // The live glass passes the same bitmap with new pixels up to 30 times a second: keep its shader (the GPU
        // re-uploads the changed pixels by itself); only a different bitmap object needs a new shader.
        if (b != blur) bitmapGen++;
        blur = b;
        screenW = w;
        screenH = h;
        generation++;
    }

    /** Bumped only when the blur bitmap object changes; panes rebuild their shader then, not on every frame. */
    private static int bitmapGen;

    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smoke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint layer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int dimKey = Integer.MIN_VALUE, dimValue;
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final RectF tmp = new RectF();
    private final int[] loc = new int[2];
    private final float dp;
    private int shaderGen = -1;
    private float radius;
    private float insetTop;
    private float gradTop = -1, gradBottom = -1;
    private int stackLayers;
    private boolean showRing = true;
    /**
     * How solid the frosted backdrop is. Below 255 the real wallpaper shows faintly through the frost, which makes
     * the blur read as glass rather than a milky slab. Menus that can sit over app tiles stay solid (setSolid).
     */
    static final int CLEAR = 158, SOLID = 255;
    private int frost = CLEAR;

    public GlassFrame(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        radius = 26 * dp;
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.2f * dp);
        rim.setColor(0x66FFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * dp);
        ring.setColor(0xF2FFFFFF);
        smoke.setColor(0x59283040);
        layer.setColor(0x1FFFFFFF);
        // Apple-style vibrancy: the frosted backdrop is a little more saturated and lighter than the wallpaper.
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(1.5f);
        ColorMatrix lift = new ColorMatrix(new float[]{
                0.92f, 0, 0, 0, 14,
                0, 0.92f, 0, 0, 14,
                0, 0, 0.92f, 0, 14,
                0, 0, 0, 1, 0});
        sat.postConcat(lift);
        glass.setColorFilter(new ColorMatrixColorFilter(sat));
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    /** Draws the glass only from this many pixels below the top: lets a pane grow and shrink without relayout. */
    void setInsetTop(float px) {
        insetTop = px;
        invalidate();
    }

    float getInsetTop() {
        return insetTop;
    }

    /** Collapsed-stack look: this many thinner cards peeking out above the top edge. */
    void setStackLayers(int n) {
        stackLayers = n;
        invalidate();
    }

    /** Solid frost for panes that can cover other UI (menus): tiles behind them would show through otherwise. */
    void setSolid(boolean solid) {
        frost = solid ? SOLID : CLEAR;
        invalidate();
    }

    void setShowRing(boolean show) {
        showRing = show;
    }

    /**
     * How much to darken: the average brightness of the blurred wallpaper under this pane, sampled on a small
     * grid, mapped to a tint (none below 40% brightness, up to about 50% black for a white sky). Cached until
     * the wallpaper frame changes or the pane moves.
     */
    private int dimAlpha() {
        if (blur == null || blur.isRecycled()) return 0;
        getLocationInWindow(loc);
        int key = generation * 31 + (loc[0] / 16) * 7919 + (loc[1] / 16) * 104729 + (int) insetTop;
        if (key == dimKey) return dimValue;
        dimKey = key;
        float bx = blur.getWidth() / (float) screenW, by = blur.getHeight() / (float) screenH;
        float w = getWidth() * getScaleX(), h = (getHeight() - insetTop) * getScaleY();
        float top = loc[1] + insetTop * getScaleY();
        double sum = 0;
        int n = 0;
        for (int iy = 0; iy < 4; iy++) {
            for (int ix = 0; ix < 8; ix++) {
                int x = Math.round((loc[0] + w * (ix + 0.5f) / 8) * bx);
                int y = Math.round((top + h * (iy + 0.5f) / 4) * by);
                if (x < 0 || y < 0 || x >= blur.getWidth() || y >= blur.getHeight()) continue;
                int c = blur.getPixel(x, y);
                sum += 0.2126 * (c >> 16 & 0xFF) + 0.7152 * (c >> 8 & 0xFF) + 0.0722 * (c & 0xFF);
                n++;
            }
        }
        float lum = n == 0 ? 0 : (float) (sum / n / 255.0);
        float t = Math.max(0f, Math.min(1f, (lum - 0.40f) / 0.40f));
        dimValue = Math.round(t * 128);
        return dimValue;
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        float s = gained ? 1.04f : 1f;
        animate().scaleX(s).scaleY(s).setDuration(160).setUpdateListener(a -> invalidate()).start();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        rect.set(0, insetTop, getWidth(), getHeight());
        if (rect.height() <= 1) return;

        if (stackLayers > 0) {
            // The cards peeking out above the pane: only the part above its top edge is drawn, or the clearer
            // glass would show their outlines through the pane.
            canvas.save();
            canvas.clipRect(rect.left, rect.top - 8 * dp * stackLayers - 2 * dp, rect.right, rect.top + 1.5f * dp);
            for (int i = stackLayers; i >= 1; i--) {
                float inset = 18 * dp * i, lift = 8 * dp * i;
                tmp.set(rect.left + inset, rect.top - lift, rect.right - inset, rect.top - lift + radius * 2);
                canvas.drawRoundRect(tmp, radius, radius, layer);
                canvas.drawRoundRect(tmp, radius, radius, rim);
            }
            canvas.restore();
        }

        if (blur != null) {
            if (shaderGen != bitmapGen) {
                glass.setShader(new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                shaderGen = bitmapGen;
            }
            // Where this pane is on screen right now (translation and scale included), mapped back into the
            // small blurred bitmap. The launcher window is full screen, so window = screen coordinates.
            getLocationInWindow(loc);
            float s = getScaleX();
            float bx = screenW / (float) blur.getWidth(), by = screenH / (float) blur.getHeight();
            matrix.setScale(bx / s, by / getScaleY());
            matrix.postTranslate(-loc[0] / s, -loc[1] / getScaleY());
            glass.getShader().setLocalMatrix(matrix);
            glass.setAlpha(frost);
            canvas.drawRoundRect(rect, radius, radius, glass);
        } else {
            canvas.drawRoundRect(rect, radius, radius, smoke);
        }

        if (gradTop != rect.top || gradBottom != rect.bottom) {
            gradTop = rect.top;
            gradBottom = rect.bottom;
            boolean clear = frost < SOLID;
            veil.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, clear ? 0x21FFFFFF : 0x33FFFFFF,
                    clear ? 0x0CFFFFFF : 0x12FFFFFF, Shader.TileMode.CLAMP));
            sheen.setShader(new LinearGradient(0, rect.top, 0, rect.top + Math.min(rect.height() * 0.5f, 60 * dp),
                    clear ? 0x24FFFFFF : 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        }
        canvas.drawRoundRect(rect, radius, radius, veil);
        canvas.drawRoundRect(rect, radius, radius, sheen);
        // Over a bright wallpaper (sunrise, sunset, daylight) the white text would vanish: darken the glass
        // in proportion to how bright the picture behind it is. Dark wallpapers get no tint at all.
        int a = dimAlpha();
        if (a > 0) {
            dim.setColor(a << 24 | 0x0A0E14);
            canvas.drawRoundRect(rect, radius, radius, dim);
        }
        canvas.drawRoundRect(rect, radius, radius, rim);
        if (showRing && isFocused()) {
            float h = ring.getStrokeWidth() / 2;
            tmp.set(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h);
            canvas.drawRoundRect(tmp, radius - h, radius - h, ring);
        }
    }
}
