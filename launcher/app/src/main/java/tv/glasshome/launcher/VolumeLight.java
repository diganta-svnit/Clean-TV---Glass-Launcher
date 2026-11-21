package tv.glasshome.launcher;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/**
 * The volume, drawn as a streak of light along the right edge of the screen.
 *
 * A faint track; the lit part glows from ice blue at the bottom to white at the head; a shimmer travels up it.
 * When the volume changes, the head springs to the new level and leaves a fading light trail behind it.
 * Muted: the streak dims and the head becomes a small ember. After a short idle it fades away by itself.
 *
 * Every frame is a function of the spring state (level, velocity, trail), stepped on the display's frames while
 * visible; nothing runs while hidden.
 */
final class VolumeLight extends View {

    interface Gone {
        void onGone();
    }

    private static final long HOLD_MS = 1900, FADE_MS = 420, APPEAR_MS = 200;

    private final float dp;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shimmer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bloom = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flare = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Gone gone;

    // Spring state: level and its velocity (per second), the trail that lags behind, and the mute blend.
    private float level, velocity, trailLevel, target, muteMix, muteTarget;
    private int shown;
    private float alpha, shimmerPhase, reveal;
    private long lastChange, lastFrame, appearAt;
    private boolean running;

    VolumeLight(Context c, Gone gone) {
        super(c);
        this.gone = gone;
        dp = getResources().getDisplayMetrics().density;
        // Blur mask filters are drawn by the GPU from Android 9; before that they need software drawing (small view).
        if (android.os.Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeWidth(2f * dp);
        track.setColor(0x2EFFFFFF);
        lit.setStyle(Paint.Style.STROKE);
        lit.setStrokeCap(Paint.Cap.ROUND);
        lit.setStrokeWidth(3f * dp);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setStrokeWidth(10f * dp);
        glow.setMaskFilter(new BlurMaskFilter(9f * dp, BlurMaskFilter.Blur.NORMAL));
        bloom.setStyle(Paint.Style.STROKE);
        bloom.setStrokeCap(Paint.Cap.ROUND);
        bloom.setStrokeWidth(26f * dp);
        bloom.setMaskFilter(new BlurMaskFilter(18f * dp, BlurMaskFilter.Blur.NORMAL));
        flare.setStyle(Paint.Style.STROKE);
        flare.setStrokeCap(Paint.Cap.ROUND);
        flare.setStrokeWidth(2.2f * dp);
        flare.setMaskFilter(new BlurMaskFilter(2.5f * dp, BlurMaskFilter.Blur.NORMAL));
        trail.setStyle(Paint.Style.STROKE);
        trail.setStrokeCap(Paint.Cap.ROUND);
        trail.setStrokeWidth(7f * dp);
        trail.setMaskFilter(new BlurMaskFilter(6f * dp, BlurMaskFilter.Blur.NORMAL));
        shimmer.setStyle(Paint.Style.STROKE);
        shimmer.setStrokeCap(Paint.Cap.ROUND);
        shimmer.setStrokeWidth(4f * dp);
        shimmer.setMaskFilter(new BlurMaskFilter(4f * dp, BlurMaskFilter.Blur.NORMAL));
        number.setColor(0xFFFFFFFF);
        number.setTextSize(34f * dp);
        number.setTextAlign(Paint.Align.RIGHT);
        number.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        number.setShadowLayer(14f * dp, 0, 0, 0xAA5CC8FF);
        label.setColor(0xCCFFFFFF);
        label.setTextSize(12f * dp);
        label.setTextAlign(Paint.Align.RIGHT);
        label.setLetterSpacing(0.25f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setShadowLayer(6f * dp, 0, 0, 0x99000000);
    }

    /**
     * New volume. from: the level before this change (to animate from it when the light first appears).
     * value: the number to show.
     */
    void show(float from, float to, int value, boolean muted) {
        long now = SystemClock.uptimeMillis();
        if (alpha <= 0.01f && !running) {
            level = trailLevel = from;
            velocity = 0;
            muteMix = muted ? 1 : 0;
            appearAt = now;
            reveal = 0;
        }
        target = to;
        shown = value;
        muteTarget = muted ? 1 : 0;
        lastChange = now;
        if (!running) {
            running = true;
            lastFrame = now;
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        step(now, dt);

        float w = getWidth(), h = getHeight();
        float x = w - 30 * dp, top = 26 * dp, bottom = h - 26 * dp, len = bottom - top;
        // The track draws itself in from the middle when the light appears.
        float half = len / 2 * reveal, mid = (top + bottom) / 2;
        int a = Math.round(255 * alpha);
        float dim = 1 - 0.65f * muteMix;

        track.setAlpha(Math.round(0x2E * alpha));
        canvas.drawLine(x, mid - half, x, mid + half, track);

        float headY = bottom - len * clamp(level);
        if (bottom - headY > 0.5f) {
            // Lit streak: violet at the foot, cyan through the middle, white at the head; a wide soft bloom
            // around it, a tighter glow and the bright line itself.
            Shader s = new LinearGradient(x, bottom, x, headY,
                    new int[]{0x007A5CFF, 0x996FB8FF, 0xCC7FDCFF, 0xFFF2FAFF}, new float[]{0f, 0.35f, 0.75f, 1f},
                    Shader.TileMode.CLAMP);
            bloom.setShader(s);
            bloom.setAlpha(Math.round(a * 0.38f * dim));
            canvas.drawLine(x, bottom, x, headY, bloom);
            lit.setShader(s);
            lit.setAlpha(Math.round(a * dim));
            glow.setShader(s);
            glow.setAlpha(Math.round(a * 0.8f * dim));
            canvas.drawLine(x, bottom, x, headY, glow);
            canvas.drawLine(x, bottom, x, headY, lit);

            // Shimmer: a short bright band travelling up the lit part.
            float p = shimmerPhase % 1f;
            float sy = bottom - (bottom - headY) * p, band = 34 * dp;
            shimmer.setShader(new LinearGradient(x, sy + band, x, sy - band,
                    new int[]{0x00FFFFFF, 0xCCFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            shimmer.setAlpha(Math.round(a * 0.8f * dim * (float) Math.sin(Math.PI * p)));
            canvas.drawLine(x, Math.min(bottom, sy + band), x, Math.max(headY, sy - band), shimmer);
        }

        // Light trail: the stretch between where the light was and where it is, fading towards the old place.
        float trailY = bottom - len * clamp(trailLevel);
        if (Math.abs(trailY - headY) > 2 * dp) {
            trail.setShader(new LinearGradient(x, trailY, x, headY,
                    new int[]{0x00BFE4FF, 0xEEFFFFFF}, null, Shader.TileMode.CLAMP));
            trail.setAlpha(Math.round(a * dim));
            canvas.drawLine(x, trailY, x, headY, trail);
        }

        // Anamorphic flare: a fine horizontal streak of light through the head, like a film lens flare.
        float fl = (58 - 30 * muteMix) * dp * (0.85f + 0.15f * (float) Math.sin(shimmerPhase * 2 * Math.PI));
        flare.setShader(new LinearGradient(x - fl, headY, x + fl, headY,
                new int[]{0x0090D8FF, blend(0xEEFFFFFF, 0xCCFFB49A, muteMix), 0x0090D8FF}, null, Shader.TileMode.CLAMP));
        flare.setAlpha(Math.round(a * 0.85f));
        canvas.drawLine(x - fl, headY, x + fl, headY, flare);

        // The head: a soft halo and a bright core (an ember when muted).
        float r = (24 - 12 * muteMix) * dp;
        halo.setShader(new RadialGradient(x, headY, r, new int[]{
                blend(0xFFFFFFFF, 0xFFFF9A7A, muteMix), blend(0x8890D0FF, 0x66FF7A5A, muteMix), 0x00000000},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        halo.setAlpha(a);
        canvas.drawCircle(x, headY, r, halo);
        core.setColor(blend(0xFFFFFFFF, 0xFFFFC2A8, muteMix));
        core.setAlpha(a);
        canvas.drawCircle(x, headY, (3.2f - 1.2f * muteMix) * dp, core);

        // The number beside the head (and "MUTED").
        float ty = Math.max(top + 24 * dp, Math.min(bottom, headY + 11 * dp));
        number.setAlpha(Math.round(a * (1 - 0.35f * muteMix)));
        canvas.drawText(String.valueOf(shown), x - 26 * dp, ty, number);
        if (muteMix > 0.01f) {
            label.setAlpha(Math.round(a * muteMix));
            canvas.drawText("MUTED", x - 26 * dp, ty + 18 * dp, label);
        }

        if (running) postInvalidateOnAnimation();
    }

    private void step(long now, float dt) {
        reveal = Math.min(1f, (now - appearAt) / (float) APPEAR_MS);
        // Spring with a little overshoot: the head arrives with momentum. It moves from the first frame, while
        // the track is still drawing itself in, so the light answers the button at once.
        float k = 170f, c = 19f;
        velocity += (k * (target - level) - c * velocity) * dt;
        level += velocity * dt;
        // The trail follows more slowly, so a quick change leaves a streak that catches up.
        trailLevel += (level - trailLevel) * Math.min(1f, dt * 5.5f);
        muteMix += (muteTarget - muteMix) * Math.min(1f, dt * 9f);
        shimmerPhase += dt / 1.15f;

        long idle = now - lastChange;
        if (idle < HOLD_MS) {
            alpha = Math.min(1f, alpha + dt / 0.18f);
        } else {
            alpha = Math.max(0f, 1f - (idle - HOLD_MS) / (float) FADE_MS);
            if (alpha <= 0f) {
                running = false;
                if (gone != null) post(gone::onGone);
            }
        }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int blend(int a, int b, float t) {
        int r = 0;
        for (int s = 0; s <= 24; s += 8) {
            int x = a >>> s & 0xFF, y = b >>> s & 0xFF;
            r |= Math.round(x + (y - x) * t) << s;
        }
        return r;
    }
}
