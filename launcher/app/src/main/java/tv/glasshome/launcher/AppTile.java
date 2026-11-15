package tv.glasshome.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;

/** One app on the glass: its TV banner when it has one, else icon and name. Grows and gets a white ring on focus. */
public class AppTile extends View {

    private final float dp;
    private final float radius;
    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    Apps.Entry app;
    private boolean more;

    public AppTile(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        radius = 12 * dp;
        setFocusable(true);
        setClickable(true);
        plate.setColor(0x2EFFFFFF);
        dot.setColor(0xEEFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * dp);
        ring.setColor(0xFFFFFFFF);
        text.setColor(0xF2FFFFFF);
        text.setTextSize(11.5f * dp);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    void setApp(Apps.Entry e) {
        app = e;
        more = false;
        setContentDescription(e.label);
        invalidate();
    }

    void setMore() {
        app = null;
        more = true;
        setContentDescription("All apps");
        invalidate();
    }

    boolean isMore() {
        return more;
    }

    String label() {
        return more ? "All apps" : app != null ? app.label : "";
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        float s = gained ? 1.14f : 1f;
        animate().scaleX(s).scaleY(s).translationZ(gained ? 10 * dp : 0).setDuration(150).start();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        rect.set(0, 0, w, h);
        if (more) {
            c.drawRoundRect(rect, radius, radius, plate);
            float r = 2.6f * dp, gap = 8 * dp, cx = w / 2f, cy = h * 0.40f;
            for (int i = -1; i <= 1; i++)
                for (int j = -1; j <= 1; j++) c.drawCircle(cx + i * gap, cy + j * gap, r, dot);
            c.drawText("All apps", cx, h - 9 * dp, text);
        } else if (app != null && app.banner != null) {
            app.banner.setBounds(0, 0, w, h);
            app.banner.draw(c);
        } else if (app != null) {
            c.drawRoundRect(rect, radius, radius, plate);
            int s = (int) (h * 0.46f);
            int left = (w - s) / 2, top = (int) (7 * dp);
            if (app.icon != null) {
                app.icon.setBounds(left, top, left + s, top + s);
                app.icon.draw(c);
            }
            CharSequence name = TextUtils.ellipsize(app.label, text, w - 10 * dp, TextUtils.TruncateAt.END);
            c.drawText(name, 0, name.length(), w / 2f, h - 8 * dp, text);
        }
        if (isFocused()) {
            float i = ring.getStrokeWidth() / 2;
            rect.inset(i, i);
            c.drawRoundRect(rect, radius - i, radius - i, ring);
        }
    }
}
