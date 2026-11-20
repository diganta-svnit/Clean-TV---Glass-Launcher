package tv.glasshome.launcher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // ---- this TV ----
    // The tray's first apps, the starting city and the streaming region come from glass.properties at build time.
    private static final String[] DOCK = BuildConfig.DOCK.isEmpty() ? new String[0] : BuildConfig.DOCK.split(",");
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "com.android.tv", "com.mitv.gallery", "com.gathin.tvhome"));
    private static final String LIVE_TV = "com.android.tv";
    private static final String[] LANG_NAMES = {"All languages", "English", "Hindi", "Spanish", "French", "German",
            "Italian", "Portuguese", "Japanese", "Korean", "Chinese", "Arabic", "Turkish", "Tamil", "Telugu",
            "Kannada", "Malayalam", "Bengali", "Marathi"};
    private static final String[] LANG_CODES = {"", "en", "hi", "es", "fr", "de", "it", "pt", "ja", "ko", "zh", "ar",
            "tr", "ta", "te", "kn", "ml", "bn", "mr"};
    /** Language codes in the order version 0.4 stored them as a number, to carry an earlier choice over. */
    private static final String[] OLD_LANG_CODES = {"", "hi", "en", "kn", "ta", "te", "ml", "bn", "mr", "ko", "ja", "es"};
    private static final long TRENDING_TTL = 6 * 3600 * 1000L;

    // ---- geometry, dp ----
    private static final int COLS = 7, TILE_W = 110, TILE_H = 62, GAP = 12, PAD = 18, CAPTION = 30;
    private static final int TRAY_W = PAD * 2 + COLS * TILE_W + (COLS - 1) * GAP;   // 878
    private static final int H0 = PAD * 2 + TILE_H;                                    // one row
    private static final int H1 = PAD * 2 + TILE_H * 2 + GAP;                          // two rows
    private static final int MARGIN = 30, TOP_STATE2 = 100, ALL_COLS = 6;

    // ---- focus groups, kept in each focusable view's tag as int[]{group, index} ----
    private static final int G_PILL = 0, G_TRAY = 2, G_STACK = 3, G_ALL = 4, G_MENU = 5, G_CARD = 6, G_LIST = 7, G_WALL = 8;

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<GlassFrame> panes = new ArrayList<>();
    /** Pills, clock and tray: faded out while All apps is open, so no glass shows under glass. */
    private final List<View> homeLayers = new ArrayList<>();
    private float dp;
    private int screenW, screenH;
    private SharedPreferences prefs;

    private VideoWall wall;
    private String wallKey;
    private TextView weatherGlyph, weatherTemp, weatherLine, timeView, dateView;
    private Weather weather;
    private long weatherAt;

    private GlassFrame[] pills;
    private FrameLayout trayHost;
    private GlassFrame tray;
    private TextView caption;
    /** The tray: one 2×7 grid. Home shows row 1 (your 6 apps + All apps); Down adds row 2 and All apps moves last. */
    private LinearLayout row1, row2;
    private final AppTile[] tiles = new AppTile[COLS * 2];
    private int trayCount;
    private FrameLayout stacksHost;
    private GlassFrame[] stacks;
    private int state;
    private boolean homePressed;
    // Holding OK on an app tile: the tile being held, its timer, and whether the menu already opened.
    private static final int LONG_PRESS_MS = 600;
    private AppTile pressTile;
    private Runnable longPress;
    private boolean longFired;
    private ValueAnimator stateAnim;

    /** One glass menu for Inputs, Settings and a title's sheet. */
    private GlassFrame menu;
    private LinearLayout menuBox;
    private final List<View> menuRows = new ArrayList<>();
    private Runnable menuBack;

    private int langIndex;
    /** The weather city: the one from glass.properties until another is picked in Settings. */
    private String cityName;
    private double cityLat, cityLon;
    /** Wallpaper picker: a chosen clip stays until "Automatic" (time of day + weather) is picked again. */
    private GlassFrame wallPanel;
    private GridLayout wallGrid;
    private final List<View> wallTiles = new ArrayList<>();
    private static final int WALL_COLS = 4;
    private final Tmdb tmdb = new Tmdb(BuildConfig.TMDB_TOKEN);
    private Images images;
    private List<Tmdb.Item> trending = new ArrayList<>();
    private String trendingStatus = "Loading\u2026";
    private long trendingAt;
    private String trendingLang;
    private boolean trendingBusy;
    private final ImageView[] fan = new ImageView[3];
    private TextView trendTop, trendMeta;
    private final ImageView[] cwFan = new ImageView[3];
    private TextView cwTop, cwMeta;
    private List<Watching.Item> watching = new ArrayList<>();
    /** Apps that carry films and series: the dock plus every app TMDB lists as a streaming service here. */
    private final java.util.Set<String> videoApps = new java.util.HashSet<>(Arrays.asList(DOCK));

    /** State 3: one stack opened as a wide panel, the other as a list beside it. */
    private FrameLayout discoverHost;
    private GlassFrame wide, narrow;
    private int expanded = -1;
    private final List<View> cards = new ArrayList<>();
    private final List<View> listRows = new ArrayList<>();
    private TextView detTitle, detMeta, detOverview;
    private int lastCard;
    /** Trending's two rows: where the series row starts in cards (-1: one row), and each row's last focus. */
    private int rowSplit = -1;
    private final int[] rowLast = new int[2];
    private List<Tmdb.Item> shownTrending = new ArrayList<>();
    private GlassFrame allPanel;
    private GridLayout allGrid;
    private final List<AppTile> allTiles = new ArrayList<>();
    private Apps.Lists lists = new Apps.Lists();

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            showTime();
            updateWallpaper();
            if (System.currentTimeMillis() - weatherAt > 30 * 60 * 1000L) fetchWeather();
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        // The launcher is always in English, whatever the TV's language.
        Locale.setDefault(Locale.UK);
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(Locale.UK);
        super.attachBaseContext(base.createConfigurationContext(c));
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        dp = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        cityName = prefs.getString("cityName", BuildConfig.CITY_NAME);
        cityLat = Double.longBitsToDouble(prefs.getLong("cityLat", Double.doubleToLongBits(BuildConfig.CITY_LAT)));
        cityLon = Double.longBitsToDouble(prefs.getLong("cityLon", Double.doubleToLongBits(BuildConfig.CITY_LON)));
        String lang = prefs.getString("langCode", null);
        if (lang == null) {
            int old = prefs.getInt("lang", -1);
            lang = old >= 0 && old < OLD_LANG_CODES.length ? OLD_LANG_CODES[old] : BuildConfig.LANGUAGE;
        }
        langIndex = Math.max(0, Arrays.asList(LANG_CODES).indexOf(lang));
        images = new Images(this);
        setContentView(build());
        String cached = prefs.getString("weather", null);
        if (cached != null) {
            try {
                weather = Weather.parse(cached);
                // A reading from another model is shown but refreshed straight away.
                weatherAt = Weather.MODEL.equals(prefs.getString("weatherModel", null))
                        ? prefs.getLong("weatherAt", 0) : 0;
            } catch (Exception ignored) {
            }
        }
        showWeather();
        updateWallpaper();
        fetchWeather();
        tiles[0].requestFocus();
        homePressed = true; // re-focus Netflix once the window has focus (Android may restore another view)
        refocusHomeSoon();
    }

    /** Android restores an earlier focus (often the Inputs pill) after Home; put it back on the first app. */
    private void refocusHomeSoon() {
        for (int delay : new int[]{150, 500}) {
            ui.postDelayed(() -> {
                if (state == 0 && !overlayOpen()) {
                    View f = getCurrentFocus();
                    if (f != tiles[0]) tiles[0].requestFocus();
                }
            }, delay);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, f);
        showTime();
        loadApps();
        updateWallpaper();
        if (System.currentTimeMillis() - weatherAt > 30 * 60 * 1000L) fetchWeather();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(tick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to the first state.
        closeOverlays();
        setState(0, false);
        tiles[0].requestFocus();
        // The window gets focus back after this, and Android then restores an old focus (often the Inputs
        // pill). Focus Netflix again once the window really has focus.
        homePressed = true;
        refocusHomeSoon();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // =====================================================================================================
    // Layout
    // =====================================================================================================

    private int px(float v) {
        return Math.round(v * dp);
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1B2A44, 0xFF3B3357, 0xFF0E1522}));

        wall = new VideoWall(this, root, this::setBlur);

        // Soft shade at the top and bottom so white text and glass read on any wallpaper.
        View topShade = new View(this);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x55000000, 0x00000000}));
        root.addView(topShade, new FrameLayout.LayoutParams(-1, px(170), Gravity.TOP));
        View bottomShade = new View(this);
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x66000000, 0x00000000}));
        root.addView(bottomShade, new FrameLayout.LayoutParams(-1, px(280), Gravity.BOTTOM));

        homeLayers.clear();
        View pillsRow = buildPills();
        homeLayers.add(pillsRow);
        root.addView(pillsRow, lp(-2, -2, Gravity.TOP | Gravity.START, MARGIN + 4, MARGIN, 0, 0));
        View clockPane = buildClock();
        homeLayers.add(clockPane);
        root.addView(clockPane, lp(-2, -2, Gravity.TOP | Gravity.END, 0, MARGIN - 4, MARGIN + 4, 0));
        root.addView(buildStacks());
        root.addView(buildTray());
        homeLayers.add(trayHost);
        root.addView(buildDiscover());
        root.addView(buildMenu());
        root.addView(buildAllApps(), lp(-1, -1, Gravity.CENTER, 40, 36, 40, 36));
        root.addView(buildWallPicker(), lp(-1, -1, Gravity.CENTER, 40, 36, 40, 36));
        return root;
    }

    private ImageView wallView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    /** Frame params in dp; -1 and -2 pass through as MATCH_PARENT and WRAP_CONTENT. */
    private FrameLayout.LayoutParams lp(float w, float h, int gravity, float l, float t, float r, float b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w < 0 ? (int) w : px(w), h < 0 ? (int) h : px(h), gravity);
        p.setMargins(px(l), px(t), px(r), px(b));
        return p;
    }

    private GlassFrame pane() {
        GlassFrame g = new GlassFrame(this);
        panes.add(g);
        return g;
    }

    private TextView text(float sp, int color, String font) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(font, Typeface.NORMAL));
        t.setShadowLayer(px(6), 0, px(1), 0x80000000); // keeps white text readable over bright glass
        t.setIncludeFontPadding(false);
        return t;
    }

    private View buildPills() {
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        String[] names = {"Inputs", "Settings"};
        pills = new GlassFrame[names.length];
        for (int i = 0; i < names.length; i++) {
            GlassFrame p = pane();
            p.setRadius(px(21));
            p.setFocusable(true);
            p.setClickable(true);
            p.setTag(new int[]{G_PILL, i});
            TextView t = text(14, 0xF2FFFFFF, "sans-serif-medium");
            t.setText(names[i]);
            t.setPadding(px(22), 0, px(22), 0);
            t.setGravity(Gravity.CENTER);
            p.addView(t, new FrameLayout.LayoutParams(-2, px(42)));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.setMarginEnd(px(12));
            row.addView(p, l);
            pills[i] = p;
        }
        pills[0].setOnClickListener(v -> openInputs());
        pills[1].setOnClickListener(v -> openSettings());
        return row;
    }

    private View buildClock() {
        GlassFrame p = pane();
        p.setRadius(px(24));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(22), px(12), px(22), px(12));

        LinearLayout weatherBox = new LinearLayout(this);
        weatherBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        weatherGlyph = text(24, 0xFFFFFFFF, "sans-serif");
        weatherTemp = text(30, 0xFFFFFFFF, "sans-serif-light");
        top.addView(weatherGlyph);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.setMarginStart(px(8));
        top.addView(weatherTemp, tl);
        weatherLine = text(12.5f, 0xD9FFFFFF, "sans-serif");
        weatherBox.addView(top);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.topMargin = px(5);
        weatherBox.addView(weatherLine, ll);
        weatherBox.setGravity(Gravity.END);

        View divider = new View(this);
        divider.setBackgroundColor(0x4DFFFFFF);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(1), px(40));
        dl.setMargins(px(18), 0, px(18), 0);

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        timeView = text(34, 0xFFFFFFFF, "sans-serif-light");
        dateView = text(12.5f, 0xD9FFFFFF, "sans-serif");
        timeView.setGravity(Gravity.END);
        dateView.setGravity(Gravity.END);
        showTime();
        clockBox.addView(timeView, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams dtl = new LinearLayout.LayoutParams(-2, -2);
        dtl.topMargin = px(4);
        clockBox.addView(dateView, dtl);

        row.addView(weatherBox);
        row.addView(divider, dl);
        row.addView(clockBox);
        p.addView(row);
        return p;
    }

    private void showTime() {
        java.util.Date now = new java.util.Date();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
        timeView.setText(new java.text.SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.UK).format(now));
        dateView.setText(new java.text.SimpleDateFormat("EEE d MMM", Locale.UK).format(now));
    }

    private View buildTray() {
        trayHost = new FrameLayout(this);
        trayHost.setClipChildren(false);
        trayHost.setLayoutParams(lp(TRAY_W, H1 + CAPTION, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, MARGIN));

        caption = text(14, 0xE6FFFFFF, "sans-serif-medium");
        caption.setPadding(px(6), 0, 0, 0);
        trayHost.addView(caption, lp(-1, CAPTION - 8, Gravity.TOP, 0, 0, 0, 0));

        tray = pane();
        tray.setRadius(px(28));
        tray.setShowRing(false);
        trayHost.addView(tray, lp(-1, H1, Gravity.TOP, 0, CAPTION, 0, 0));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        row1 = tileRow(tiles, 0, G_TRAY);
        row2 = tileRow(tiles, COLS, G_TRAY);
        grid.addView(row1);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-2, -2);
        l2.topMargin = px(GAP);
        grid.addView(row2, l2);
        tray.addView(grid, lp(-2, -2, Gravity.TOP | Gravity.START, PAD, PAD, 0, 0));
        row2.setAlpha(0);
        row2.setVisibility(View.INVISIBLE);

        // Collapsed: the glass starts lower and row 1 sits at the bottom of it.
        tray.setInsetTop(px(H1 - H0));
        caption.setTranslationY(px(H1 - H0));
        row1.setTranslationY(px(H1 - H0));
        return trayHost;
    }

    private LinearLayout tileRow(AppTile[] into, int offset, int group) {
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        for (int i = 0; i < COLS; i++) {
            AppTile t = new AppTile(this);
            t.setTag(new int[]{group, offset + i});
            t.setOnClickListener(v -> onTile((AppTile) v));
            t.setOnFocusChangeListener((v, has) -> {
                if (has) updateCaption();
            });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(px(TILE_W), px(TILE_H));
            if (i > 0) l.setMarginStart(px(GAP));
            row.addView(t, l);
            into[offset + i] = t;
        }
        return row;
    }

    private View buildStacks() {
        stacksHost = new FrameLayout(this);
        stacksHost.setClipChildren(false);
        int top = TOP_STATE2 + CAPTION + H1 + 20;
        int height = Math.round(screenH / dp) - top - MARGIN;
        stacksHost.setLayoutParams(lp(TRAY_W, height, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, MARGIN));
        stacks = new GlassFrame[2];
        int w = (TRAY_W - 18) / 2;
        for (int i = 0; i < 2; i++) {
            GlassFrame s = pane();
            s.setRadius(px(24));
            s.setStackLayers(2);
            s.setFocusable(true);
            s.setClickable(true);
            s.setTag(new int[]{G_STACK, i});
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(px(22), px(18), px(22), px(18));
            TextView title = text(19, 0xFFFFFFFF, "sans-serif-medium");
            title.setText(i == 0 ? "Trending" : "Continue watching");
            TextView sub = text(13.5f, 0xE6FFFFFF, "sans-serif-medium");
            TextView meta = text(12.5f, 0xB3FFFFFF, "sans-serif");
            box.addView(title);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(px(i == 0 ? 220 : 380), -2);
            sl.topMargin = px(14);
            box.addView(sub, sl);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(px(i == 0 ? 220 : 380), -2);
            ml.topMargin = px(6);
            box.addView(meta, ml);
            sub.setMaxLines(2);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            s.addView(box);
            if (i == 0) {
                trendTop = sub;
                trendMeta = meta;
                FrameLayout fanBox = new FrameLayout(this);
                fanBox.setClipChildren(false);
                for (int k = 2; k >= 0; k--) {
                    ImageView img = posterView(10);
                    img.setRotation((k - 1) * 7f);
                    fanBox.addView(img, lp(72, 108, Gravity.CENTER, 0, 0, 0, 0));
                    img.setTranslationX(px((k - 1) * 34));
                    img.setTranslationY(px(Math.abs(k - 1) * 6));
                    if (k == 1) img.setElevation(px(2));
                    fan[k] = img;
                }
                s.addView(fanBox, lp(170, -1, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
            } else {
                cwTop = sub;
                cwMeta = meta;
                sub.setText("Loading\u2026");
                // Landscape fan: what was playing is usually a 16:9 still.
                FrameLayout fanBox = new FrameLayout(this);
                fanBox.setClipChildren(false);
                for (int k = 2; k >= 0; k--) {
                    ImageView img = posterView(8);
                    img.setRotation((k - 1) * 5f);
                    fanBox.addView(img, lp(118, 66, Gravity.CENTER, 0, 0, 0, 0));
                    img.setTranslationX(px((k - 1) * 30));
                    img.setTranslationY(px((k - 1) * 10));
                    if (k == 1) img.setElevation(px(2));
                    cwFan[k] = img;
                }
                s.addView(fanBox, lp(190, -1, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
                sub.getLayoutParams().width = px(200);
                meta.getLayoutParams().width = px(200);
            }
            int which = i;
            s.setOnClickListener(v -> openExpanded(which));
            s.setOnFocusChangeListener((v, has) -> {
                if (has) updateCaption();
            });
            stacksHost.addView(s, lp(w, -1, Gravity.START, i == 0 ? 0 : w + 18, 18, 0, 0));
            stacks[i] = s;
        }
        stacksHost.setAlpha(0);
        stacksHost.setTranslationY(px(60));
        stacksHost.setVisibility(View.INVISIBLE);
        showTrendingCard();
        return stacksHost;
    }

    /** Wraps a scrolling view so it is clipped to its own bounds inside an unclipped glass pane. */
    private FrameLayout clipBox(View scroller) {
        FrameLayout box = new FrameLayout(this);
        box.setClipChildren(true);
        box.addView(scroller, new FrameLayout.LayoutParams(-1, -2));
        return box;
    }

    /** A rounded poster image. */
    private ImageView posterView(float radiusDp) {
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x26FFFFFF);
        bg.setCornerRadius(px(radiusDp));
        img.setBackground(bg);
        float r = px(radiusDp);
        img.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        img.setClipToOutline(true);
        return img;
    }

    private View buildDiscover() {
        discoverHost = new FrameLayout(this);
        discoverHost.setClipChildren(false);
        int height = Math.round(screenH / dp) - TOP_STATE2 - MARGIN;
        discoverHost.setLayoutParams(lp(TRAY_W, height, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, MARGIN));
        wide = pane();
        wide.setRadius(px(28));
        wide.setShowRing(false);
        narrow = pane();
        narrow.setRadius(px(28));
        narrow.setShowRing(false);
        discoverHost.addView(wide, lp(TRAY_W - 268, -1, Gravity.START, 0, 0, 0, 0));
        discoverHost.addView(narrow, lp(250, -1, Gravity.END, 0, 0, 0, 0));
        discoverHost.setAlpha(0);
        discoverHost.setVisibility(View.INVISIBLE);
        return discoverHost;
    }

    private View buildMenu() {
        menu = pane();
        menu.setRadius(px(24));
        menu.setShowRing(false);
        menuBox = new LinearLayout(this);
        menuBox.setOrientation(LinearLayout.VERTICAL);
        menuBox.setPadding(px(10), px(10), px(10), px(10));
        // Long lists (12 languages) scroll; the focused row is kept in view.
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.addView(menuBox);
        // A view is clipped to its bounds only when its parent clips children; glass panes don't, so add one.
        menu.addView(clipBox(scroll), new FrameLayout.LayoutParams(-1, -2));
        menu.setVisibility(View.GONE);
        return menu;
    }

    private View buildAllApps() {
        allPanel = pane();
        allPanel.setRadius(px(30));
        allPanel.setShowRing(false);
        TextView title = text(22, 0xFFFFFFFF, "sans-serif-medium");
        title.setText("All apps");
        allPanel.addView(title, lp(-2, -2, Gravity.TOP | Gravity.START, 34, 24, 0, 0));
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, px(14), 0, px(24));
        allGrid = new GridLayout(this);
        allGrid.setColumnCount(ALL_COLS);
        allGrid.setClipChildren(false);
        FrameLayout center = new FrameLayout(this);
        center.setClipChildren(false);
        center.addView(allGrid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
        scroll.addView(center);
        allPanel.addView(scroll, lp(-1, -1, Gravity.TOP, 0, 64, 0, 0));
        allPanel.setVisibility(View.GONE);
        return allPanel;
    }

    // =====================================================================================================
    // Apps
    // =====================================================================================================

    private void loadApps() {
        io.execute(() -> {
            Apps.Lists l = Apps.load(this, HIDDEN, DOCK, COLS * 2 - 1);
            ui.post(() -> bindApps(l));
        });
    }

    private void bindApps(Apps.Lists l) {
        lists = l;
        applySlots();
        View f = getCurrentFocus();
        if ((f == null || !f.isShown()) && state != 2 && !overlayOpen()) tiles[0].requestFocus();
        updateCaption();
        if (allPanel.getVisibility() == View.VISIBLE) fillAllApps();
        loadTrending(false);
        loadWatching();
    }

    /** Fills the tiles for the current state: All apps is slot 7 at home and the last slot when expanded. */
    private void applySlots() {
        int n = lists.tray.size();
        int moreAt = state == 0 ? Math.min(n, COLS - 1) : Math.min(n, COLS * 2 - 1);
        for (int i = 0; i < tiles.length; i++) {
            AppTile t = tiles[i];
            if (i < moreAt) {
                t.setApp(lists.tray.get(i));
                t.setVisibility(View.VISIBLE);
            } else if (i == moreAt) {
                t.setMore();
                t.setVisibility(View.VISIBLE);
            } else {
                t.setVisibility(i < COLS ? View.INVISIBLE : View.GONE);
            }
        }
        trayCount = moreAt + 1;
    }

    private void onTile(AppTile t) {
        if (t.isMore()) openAllApps();
        else if (t.app != null) open(t.app.launch);
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateCaption() {
        View f = getCurrentFocus();
        if (!(f instanceof AppTile) || f.getTag() == null || ((int[]) f.getTag())[0] != G_TRAY) {
            caption.setText(state == 0 ? "Your apps" : "Your apps and frequently used");
            return;
        }
        AppTile t = (AppTile) f;
        int i = ((int[]) t.getTag())[1];
        String section = t.isMore() ? null : i < lists.dockCount ? "Your apps"
                : lists.usageAccess ? "Frequently used" : "More apps";
        caption.setText(section == null ? t.label() : section + "  \u00B7  " + t.label());
    }

    // =====================================================================================================
    // States: 0 = your 6 apps + All apps, 1 = + 7 frequent apps and All apps last, 2 = tray up, stacks below
    // =====================================================================================================

    private void setState(int n, boolean animate) {
        if (n == state && animate) return;
        int old = state;
        state = n;
        if ((old == 0) != (n == 0) || !animate) {
            applySlots();
            fade(row2, n != 0);
        }
        if (n == 2) stacksHost.setVisibility(View.VISIBLE);
        if (n == 3) discoverHost.setVisibility(View.VISIBLE);
        trayHost.setVisibility(View.VISIBLE);

        float fromInset = tray.getInsetTop(), toInset = n == 0 ? px(H1 - H0) : 0;
        float shift2 = px(TOP_STATE2) - trayHost.getTop();
        float fromShift = trayHost.getTranslationY(), toShift = n == 2 ? shift2 : n == 3 ? shift2 - px(50) : 0;
        float fromStack = stacksHost.getAlpha(), toStack = n == 2 ? 1 : 0;
        float fromTray = trayHost.getAlpha(), toTray = n == 3 ? 0 : 1;
        float fromDisc = discoverHost.getAlpha(), toDisc = n == 3 ? 1 : 0;
        if (stateAnim != null) stateAnim.cancel();
        stateAnim = ValueAnimator.ofFloat(0, 1);
        stateAnim.setDuration(animate ? 340 : 0);
        stateAnim.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        stateAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float inset = fromInset + (toInset - fromInset) * t;
            tray.setInsetTop(inset);
            caption.setTranslationY(inset);
            row1.setTranslationY(inset);
            trayHost.setTranslationY(fromShift + (toShift - fromShift) * t);
            float s = fromStack + (toStack - fromStack) * t;
            stacksHost.setAlpha(s);
            stacksHost.setTranslationY((1 - s) * px(60));
            trayHost.setAlpha(fromTray + (toTray - fromTray) * t);
            float d = fromDisc + (toDisc - fromDisc) * t;
            discoverHost.setAlpha(d);
            discoverHost.setTranslationY((1 - d) * px(40));
            for (GlassFrame g : stacks) g.invalidate();
            wide.invalidate();
            narrow.invalidate();
            tray.invalidate();
        });
        stateAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (state != 2) stacksHost.setVisibility(View.INVISIBLE);
                if (state != 3) discoverHost.setVisibility(View.INVISIBLE);
                if (state == 3) trayHost.setVisibility(View.INVISIBLE);
            }
        });
        stateAnim.start();
        updateCaption();
        updatePlayback();
    }

    private void fade(View v, boolean in) {
        v.animate().cancel();
        if (in) {
            v.setVisibility(View.VISIBLE);
            v.animate().alpha(1).setStartDelay(90).setDuration(200).withEndAction(null).start();
        } else {
            v.animate().alpha(0).setStartDelay(0).setDuration(110).withEndAction(() -> {
                if (v.getAlpha() == 0) v.setVisibility(View.INVISIBLE);
            }).start();
        }
    }

    // =====================================================================================================
    // Remote control: every D-pad move is decided here, so focus never wanders into a hidden layer.
    // =====================================================================================================

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) navigate(k);
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && !overlayOpen()) {
            openAllApps();
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (okKey(e)) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /**
     * OK on an app tile (tray or All apps): a short press opens the app, holding it opens the app's menu.
     * Timed here rather than from key repeats, since some Bluetooth remotes send no repeats.
     */
    private boolean okKey(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                ui.removeCallbacks(longPress);
                longFired = false;
                View f = getCurrentFocus();
                pressTile = f instanceof AppTile && !((AppTile) f).isMore() && ((AppTile) f).app != null
                        && menu.getVisibility() != View.VISIBLE ? (AppTile) f : null;
                if (pressTile != null) {
                    AppTile t = pressTile;
                    longPress = () -> {
                        longFired = true;
                        pressTile = null;
                        openAppMenu(t);
                    };
                    ui.postDelayed(longPress, LONG_PRESS_MS);
                }
            } else if (pressTile != null && !longFired && e.isLongPress()) {
                // The remote's own key repeat says "held": open now rather than wait for the timer.
                ui.removeCallbacks(longPress);
                longPress.run();
            }
            return pressTile != null || longFired;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            ui.removeCallbacks(longPress);
            if (longFired) {
                longFired = false;
                return true;
            }
            if (pressTile != null) {
                AppTile t = pressTile;
                pressTile = null;
                t.performClick();
                return true;
            }
        }
        return false;
    }

    /** The app's own menu, from holding OK on its tile: Open, Uninstall (apps you installed) and App info. */
    private void openAppMenu(AppTile t) {
        Apps.Entry app = t.app;
        boolean inAll = allPanel.getVisibility() == View.VISIBLE;
        boolean builtIn = true;
        try {
            builtIn = (getPackageManager().getApplicationInfo(app.pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        List<String[]> rows = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        rows.add(new String[]{"Open", null});
        actions.add(() -> {
            if (inAll) closeOverlays();
            else t.requestFocus();
            open(app.launch);
        });
        if (!builtIn) {
            rows.add(new String[]{"Uninstall", null});
            actions.add(() -> {
                t.requestFocus();
                open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)));
            });
        }
        rows.add(new String[]{"App info", null});
        actions.add(() -> {
            t.requestFocus();
            open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)));
        });
        openMenu(menuHeader(app.label, builtIn ? "Built into the TV, so it can't be uninstalled" : null),
                rows, actions, true, -1, t::requestFocus);
    }

    @Override
    public void onBackPressed() {
        if (menu.getVisibility() == View.VISIBLE && allPanel.getVisibility() == View.VISIBLE) {
            // An app's menu over All apps: close only the menu.
            menu.setVisibility(View.GONE);
            Runnable back = menuBack;
            menuBack = null;
            if (back != null) back.run();
            updatePlayback();
            return;
        }
        if (overlayOpen()) {
            closeOverlays();
            return;
        }
        if (state == 3) {
            closeExpanded();
            return;
        }
        if (state != 0) {
            setState(0, true);
            tiles[0].requestFocus();
        }
        // At home with nothing open, Back does nothing: this is the launcher.
    }

    private void navigate(int k) {
        boolean up = k == KeyEvent.KEYCODE_DPAD_UP, down = k == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean left = k == KeyEvent.KEYCODE_DPAD_LEFT, right = k == KeyEvent.KEYCODE_DPAD_RIGHT;
        View f = getCurrentFocus();
        int[] p = f != null && f.getTag() instanceof int[] ? (int[]) f.getTag() : null;
        if (p == null) {
            // Nothing focused yet (just started, or focus was lost): focus home, then still act on this key.
            focusHome();
            f = getCurrentFocus();
            p = f != null && f.getTag() instanceof int[] ? (int[]) f.getTag() : null;
            if (p == null) return;
        }
        int i = p[1];
        switch (p[0]) {
            case G_PILL:
                if (left && i > 0) pills[i - 1].requestFocus();
                else if (right && i < pills.length - 1) pills[i + 1].requestFocus();
                else if (down) tiles[0].requestFocus();
                break;
            case G_TRAY: {
                int row = i / COLS, col = i % COLS, rowEnd = Math.min((row + 1) * COLS, trayCount) - 1;
                if (left && col > 0) tiles[i - 1].requestFocus();
                else if (right && i < rowEnd) tiles[i + 1].requestFocus();
                else if (up && state == 0) pills[0].requestFocus();
                else if (up && row == 1) tiles[col].requestFocus();
                else if (up) {
                    setState(0, true);
                    tiles[Math.min(i, trayCount - 1)].requestFocus();
                } else if (down && state == 0) {
                    // Down once: the tray grows and focus lands on the new second row.
                    setState(1, true);
                    tiles[Math.min(i + COLS, trayCount - 1)].requestFocus();
                } else if (down && row == 0 && trayCount > COLS) {
                    tiles[Math.min(i + COLS, trayCount - 1)].requestFocus();
                } else if (down) {
                    // Down twice: the tray moves up and the stacks appear.
                    setState(2, true);
                    stacks[col < COLS / 2 + 1 ? 0 : 1].requestFocus();
                }
                break;
            }
            case G_STACK:
                if (left && i > 0) stacks[0].requestFocus();
                else if (right && i == 0) stacks[1].requestFocus();
                else if (up) {
                    setState(1, true);
                    int rowStart = trayCount > COLS ? COLS : 0;
                    tiles[Math.min(rowStart + (i == 0 ? 1 : 5), trayCount - 1)].requestFocus();
                }
                break;
            case G_WALL: {
                int n = wallTiles.size(), target = i;
                if (left && i % WALL_COLS > 0) target = i - 1;
                else if (right && i % WALL_COLS < WALL_COLS - 1 && i + 1 < n) target = i + 1;
                else if (up && i >= WALL_COLS) target = i - WALL_COLS;
                else if (down && i + WALL_COLS < n) target = i + WALL_COLS;
                else if (down && i / WALL_COLS < (n - 1) / WALL_COLS) target = n - 1;
                wallTiles.get(target).requestFocus();
                break;
            }
            case G_ALL: {
                int n = allTiles.size(), target = i;
                if (left && i % ALL_COLS > 0) target = i - 1;
                else if (right && i % ALL_COLS < ALL_COLS - 1 && i + 1 < n) target = i + 1;
                else if (up && i >= ALL_COLS) target = i - ALL_COLS;
                else if (down && i + ALL_COLS < n) target = i + ALL_COLS;
                else if (down && i / ALL_COLS < (n - 1) / ALL_COLS) target = n - 1;
                allTiles.get(target).requestFocus();
                break;
            }
            case G_MENU:
                if (up && i > 0) menuRows.get(i - 1).requestFocus();
                else if (down && i < menuRows.size() - 1) menuRows.get(i + 1).requestFocus();
                break;
            case G_CARD: {
                // Trending has two rows (films, series) when rowSplit > 0; Continue watching has one.
                int split = rowSplit > 0 && rowSplit < cards.size() ? rowSplit : cards.size();
                boolean second = i >= split;
                int start = second ? split : 0, end = second ? cards.size() - 1 : split - 1;
                if (left && i > start) cards.get(i - 1).requestFocus();
                else if (right && i < end) cards.get(i + 1).requestFocus();
                else if (right && !listRows.isEmpty()) listRows.get(0).requestFocus();
                else if (down && !second && split < cards.size())
                    cards.get(Math.max(split, Math.min(rowLast[1], cards.size() - 1))).requestFocus();
                else if (up && second) cards.get(Math.min(rowLast[0], split - 1)).requestFocus();
                else if (up) closeExpanded();
                break;
            }
            case G_LIST:
                if (up && i > 0) listRows.get(i - 1).requestFocus();
                else if (up) closeExpanded();
                else if (down && i < listRows.size() - 1) listRows.get(i + 1).requestFocus();
                else if (left && !cards.isEmpty()) cards.get(Math.min(lastCard, cards.size() - 1)).requestFocus();
                break;
            default:
                break;
        }
    }

    private void focusHome() {
        if (allPanel.getVisibility() == View.VISIBLE && !allTiles.isEmpty()) allTiles.get(0).requestFocus();
        else if (menu.getVisibility() == View.VISIBLE && !menuRows.isEmpty()) menuRows.get(0).requestFocus();
        else if (wallPanel.getVisibility() == View.VISIBLE && !wallTiles.isEmpty()) wallTiles.get(0).requestFocus();
        else if (state == 3) focusExpanded();
        else if (state != 2) tiles[0].requestFocus();
        else stacks[0].requestFocus();
    }

    private boolean overlayOpen() {
        return allPanel.getVisibility() == View.VISIBLE || menu.getVisibility() == View.VISIBLE
                || wallPanel.getVisibility() == View.VISIBLE;
    }

    private void closeOverlays() {
        boolean wasAll = allPanel.getVisibility() == View.VISIBLE;
        boolean wasMenu = menu.getVisibility() == View.VISIBLE;
        boolean wasWall = wallPanel.getVisibility() == View.VISIBLE;
        allPanel.setVisibility(View.GONE);
        menu.setVisibility(View.GONE);
        wallPanel.setVisibility(View.GONE);
        updatePlayback();
        if (wasWall) {
            for (View v : homeLayers) v.animate().alpha(1).setDuration(150).start();
            if (state == 2) stacksHost.animate().alpha(1).setDuration(150).start();
            openSettings();
            return;
        }
        if (wasAll) {
            for (View v : homeLayers) v.animate().alpha(1).setDuration(150).start();
            if (state == 2) stacksHost.animate().alpha(1).setDuration(150).start();
            if (state < 2) tiles[trayCount - 1].requestFocus();
            else focusHome();
        } else if (wasMenu && menuBack != null) {
            Runnable back = menuBack;
            menuBack = null;
            back.run();
        }
    }

    // =====================================================================================================
    // All apps
    // =====================================================================================================

    private void openAllApps() {
        fillAllApps();
        allPanel.setAlpha(0);
        allPanel.setScaleX(0.97f);
        allPanel.setScaleY(0.97f);
        allPanel.setVisibility(View.VISIBLE);
        updatePlayback();
        for (View v : homeLayers) v.animate().alpha(0).setDuration(150).start();
        stacksHost.animate().alpha(0).setDuration(150).start();
        allPanel.animate().alpha(1).scaleX(1).scaleY(1).setDuration(200)
                .setUpdateListener(a -> allPanel.invalidate()).start();
        if (!allTiles.isEmpty()) allTiles.get(0).requestFocus();
    }

    private void fillAllApps() {
        // Rebuilt after an uninstall too: keep focus near where it was.
        View f = getCurrentFocus();
        int keep = f instanceof AppTile && ((int[]) f.getTag())[0] == G_ALL ? ((int[]) f.getTag())[1] : -1;
        allGrid.removeAllViews();
        allTiles.clear();
        int gap = 16;
        for (Apps.Entry e : lists.all) {
            AppTile t = new AppTile(this);
            t.setApp(e);
            t.setTag(new int[]{G_ALL, allTiles.size()});
            t.setOnClickListener(v -> {
                closeOverlays();
                onTile((AppTile) v);
            });
            GridLayout.LayoutParams l = new GridLayout.LayoutParams();
            l.width = px(TILE_W);
            l.height = px(TILE_H);
            l.setMargins(px(gap / 2f), px(gap / 2f), px(gap / 2f), px(gap / 2f));
            allGrid.addView(t, l);
            allTiles.add(t);
        }
        if (keep >= 0 && !allTiles.isEmpty()) allTiles.get(Math.min(keep, allTiles.size() - 1)).requestFocus();
    }

    // =====================================================================================================
    // Inputs: HDMI ports (with the name of a connected device) and Live TV
    // =====================================================================================================

    private void openInputs() {
        List<String[]> rows = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> all;
            try {
                all = tim.getTvInputList();
            } catch (RuntimeException e) {
                all = new ArrayList<>();
            }
            Map<String, TvInputInfo> childOf = new HashMap<>();
            for (TvInputInfo in : all) if (in.getParentId() != null) childOf.put(in.getParentId(), in);
            for (TvInputInfo in : all) {
                if (in.getParentId() != null || in.getType() == TvInputInfo.TYPE_TUNER || in.isHidden(this)) continue;
                String label = label(in);
                String id = in.getId();
                TvInputInfo child = childOf.get(id);
                if (child != null) {
                    label = label(child) + "  ·  " + label;
                    id = child.getId();
                }
                String target = id;
                rows.add(new String[]{label, null});
                actions.add(() -> openInput(target));
            }
        }
        Intent live = getPackageManager().getLeanbackLaunchIntentForPackage(LIVE_TV);
        if (live == null) live = getPackageManager().getLaunchIntentForPackage(LIVE_TV);
        if (live != null) {
            Intent l = live;
            rows.add(new String[]{"Live TV", null});
            actions.add(() -> open(l));
        }
        if (rows.isEmpty()) {
            Toast.makeText(this, "No inputs found", Toast.LENGTH_SHORT).show();
            return;
        }
        openMenu(null, rows, actions, false, -1, () -> pills[0].requestFocus());
    }

    private String label(TvInputInfo in) {
        CharSequence c = in.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = in.loadLabel(this);
        return String.valueOf(c);
    }

    /**
     * Shows the glass menu: an optional header, then rows of {label, value}. Anchored under the pills, or centred
     * for a title's sheet. A row's action runs after the menu closes. Back calls onBack.
     */
    private void openMenu(View header, List<String[]> rows, List<Runnable> actions, boolean centred, int selected,
                          Runnable onBack) {
        menuBox.removeAllViews();
        menuRows.clear();
        if (header != null) menuBox.addView(header);
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(18), 0, px(18), 0);
            row.setFocusable(true);
            row.setClickable(true);
            row.setTag(new int[]{G_MENU, i});
            GradientDrawable on = new GradientDrawable();
            on.setColor(0x4DFFFFFF);
            on.setCornerRadius(px(14));
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, on);
            bg.addState(new int[]{}, new GradientDrawable());
            row.setBackground(bg);
            TextView label = text(15, 0xF2FFFFFF, "sans-serif-medium");
            label.setText(i == selected ? "✓  " + r[0] : r[0]);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            if (r[1] != null) {
                TextView value = text(14, 0xB3FFFFFF, "sans-serif");
                value.setText(r[1] + "  ›");
                row.addView(value);
            }
            Runnable action = actions.get(i);
            row.setOnClickListener(v -> {
                // Put focus back where the menu came from, unless the action opened another menu.
                Runnable back = menuBack;
                menuBack = null;
                menu.setVisibility(View.GONE);
                action.run();
                // ...unless the action opened another menu or panel (wallpaper picker), which owns focus now.
                if (!overlayOpen() && back != null) back.run();
                updatePlayback();
            });
            menuBox.addView(row, new LinearLayout.LayoutParams(-1, px(46)));
            menuRows.add(row);
        }
        FrameLayout.LayoutParams l = centred ? lp(560, -2, Gravity.CENTER, 0, 40, 0, 40)
                : lp(360, -2, Gravity.TOP | Gravity.START, MARGIN + 4, MARGIN + 54, 0, MARGIN);
        menu.setLayoutParams(l);
        menuBack = onBack;
        menu.bringToFront(); // above All apps, for an app's menu opened there
        menu.setAlpha(0);
        menu.setVisibility(View.VISIBLE);
        menu.animate().alpha(1).setDuration(160).setUpdateListener(a -> menu.invalidate()).start();
        updatePlayback();
        if (!menuRows.isEmpty()) menuRows.get(Math.max(0, selected)).requestFocus();
    }

    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(new Intent(view).setPackage(LIVE_TV));
        } catch (ActivityNotFoundException | SecurityException e) {
            open(view);
        }
    }

    // =====================================================================================================
    // Weather and wallpaper
    // =====================================================================================================

    private void fetchWeather() {
        if (cityName.isEmpty()) return; // no city yet: Settings > City
        io.execute(() -> {
            try {
                String json = Weather.download(cityLat, cityLon);
                Weather w = Weather.parse(json);
                long now = System.currentTimeMillis();
                prefs.edit().putString("weather", json).putLong("weatherAt", now)
                        .putString("weatherModel", Weather.MODEL).apply();
                ui.post(() -> {
                    weather = w;
                    weatherAt = now;
                    showWeather();
                    updateWallpaper();
                });
            } catch (Exception ignored) {
                // Offline: keep the last weather; the next clock tick after 30 minutes tries again.
            }
        });
    }

    private void showWeather() {
        boolean night = Weather.timeOfDay(System.currentTimeMillis(), weather).equals("night");
        if (weather == null) {
            weatherGlyph.setText("");
            weatherTemp.setText("—°");
            weatherLine.setText(cityName.isEmpty() ? "Pick a city in Settings" : cityName);
        } else {
            weatherGlyph.setText(weather.glyph(night));
            weatherTemp.setText(String.format(Locale.UK, "%d°", Math.round(weather.temp)));
            weatherLine.setText(weather.label() + "  ·  " + cityName);
        }
    }

    /** Picks the wallpaper for the time of day and weather; mist and wind use the cloudy one. */
    private void updateWallpaper() {
        String tod = Weather.timeOfDay(System.currentTimeMillis(), weather);
        String cond = weather == null ? "clear" : weather.condition();
        File dir = getExternalFilesDir("wallpapers");
        if (dir == null) return;
        List<String> keys = new ArrayList<>();
        keys.add(tod + "-" + cond);
        if (cond.equals("mist") || cond.equals("windy")) keys.add(tod + "-cloudy");
        keys.add(tod + "-clear");
        keys.add("day-clear");
        String pinned = prefs.getString("wall", null);
        if (pinned != null) keys.add(0, pinned);
        String key = null;
        for (String k : keys) {
            if (new File(dir, k + ".jpg").isFile()) {
                key = k;
                break;
            }
        }
        if (key == null || key.equals(wallKey)) return;
        wallKey = key;
        String chosen = key;
        io.execute(() -> {
            Bitmap poster = BitmapFactory.decodeFile(new File(dir, chosen + ".jpg").getPath());
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            File blurFile = new File(dir, chosen + "-blur.jpg");
            Bitmap blur = BitmapFactory.decodeFile(blurFile.isFile() ? blurFile.getPath()
                    : new File(dir, chosen + ".jpg").getPath(), o);
            File clip = new File(dir, chosen + ".mp4");
            ui.post(() -> showWallpaper(poster, blur, clip.isFile() ? clip : null));
        });
        showWeather();
    }

    private void showWallpaper(Bitmap poster, Bitmap blur, File clip) {
        if (poster == null) return;
        setBlur(blur);
        wall.show(poster, clip);
    }

    private void setBlur(Bitmap blur) {
        GlassFrame.setBlur(blur, screenW, screenH);
        for (GlassFrame g : panes) g.invalidate();
    }

    /** The clip plays only on the first home state with nothing open; otherwise it holds still under the glass. */
    private void updatePlayback() {
        wall.setPlaying(state == 0 && !overlayOpen() && hasWindowFocus());
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && homePressed) {
            homePressed = false;
            if (!overlayOpen()) {
                setState(0, false);
                tiles[0].requestFocus();
            }
        }
        updatePlayback();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (wall != null) wall.resume();
    }

    @Override
    protected void onStop() {
        super.onStop();
        wall.stop();
    }

    // =====================================================================================================
    // Settings
    // =====================================================================================================

    private void openSettings() {
        List<String[]> rows = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        rows.add(new String[]{"City", cityName});
        actions.add(this::pickCity);
        String pinned = prefs.getString("wall", null);
        rows.add(new String[]{"Wallpaper", pinned == null ? "Automatic" : wallName(pinned)});
        actions.add(this::openWallPicker);
        if (pinned != null) {
            rows.add(new String[]{"Reset wallpaper to automatic", null});
            actions.add(() -> {
                setWallPin(null);
                Toast.makeText(this, "Wallpaper follows time of day and weather again", Toast.LENGTH_SHORT).show();
                openSettings();
            });
        }
        rows.add(new String[]{"Trending language", LANG_NAMES[langIndex]});
        actions.add(this::pickLanguage);
        boolean google = prefs.getBoolean("googleSearch", false);
        rows.add(new String[]{"Find with Google button", google ? "On" : "Off"});
        actions.add(() -> {
            prefs.edit().putBoolean("googleSearch", !google).apply();
            Toast.makeText(this, google ? "Find with Google hidden"
                    : "Find with Google shown (needs Google Assistant set up on the TV)", Toast.LENGTH_LONG).show();
            openSettings();
        });
        rows.add(new String[]{"Refresh Trending", null});
        actions.add(() -> {
            loadTrending(true);
            Toast.makeText(this, "Refreshing Trending", Toast.LENGTH_SHORT).show();
            pills[1].requestFocus();
        });
        rows.add(new String[]{"Android TV settings", null});
        actions.add(() -> open(new Intent(Settings.ACTION_SETTINGS)));
        openMenu(menuHeader("Settings", null), rows, actions, false, -1, () -> pills[1].requestFocus());
    }

    /** City: the cities used before (current one ticked) and a search over Open-Meteo's free place names. */
    private void pickCity() {
        List<String[]> rows = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        rows.add(new String[]{"Search for a city…", null});
        actions.add(this::searchCity);
        List<Places.Place> recent = Places.recent(prefs);
        int selected = -1;
        for (Places.Place p : recent) {
            if (p.name.equals(cityName)) selected = rows.size();
            rows.add(new String[]{p.name, p.where.isEmpty() ? null : p.where});
            actions.add(() -> setCity(p));
        }
        if (selected < 0 && !cityName.isEmpty()) {
            selected = rows.size();
            rows.add(new String[]{cityName, null});
            actions.add(this::openSettings);
        }
        openMenu(menuHeader("City", "Weather, sunrise and sunset"), rows, actions, false, selected,
                this::openSettings);
    }

    private void searchCity() {
        android.widget.EditText box = new android.widget.EditText(this);
        box.setSingleLine(true);
        box.setHint("City name");
        box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Search for a city").setView(box)
                .setPositiveButton("Search", (di, w) -> findCity(box.getText().toString()))
                .setNegativeButton("Cancel", (di, w) -> openSettings())
                .setOnCancelListener(di -> openSettings())
                .create();
        box.setOnEditorActionListener((v, action, ev) -> {
            d.dismiss();
            findCity(box.getText().toString());
            return true;
        });
        d.show();
        box.requestFocus();
    }

    private void findCity(String q) {
        if (q.trim().isEmpty()) {
            openSettings();
            return;
        }
        Toast.makeText(this, "Searching…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            List<Places.Place> found;
            try {
                found = Places.search(q.trim());
            } catch (Exception e) {
                found = null;
            }
            List<Places.Place> res = found;
            ui.post(() -> {
                if (res == null || res.isEmpty()) {
                    Toast.makeText(this, res == null ? "No connection, try again" : "No place called \"" + q + "\"",
                            Toast.LENGTH_LONG).show();
                    pickCity();
                    return;
                }
                List<String[]> rows = new ArrayList<>();
                List<Runnable> actions = new ArrayList<>();
                for (Places.Place p : res) {
                    rows.add(new String[]{p.name, p.where.isEmpty() ? null : p.where});
                    actions.add(() -> setCity(p));
                }
                openMenu(menuHeader("Which " + q.trim() + "?", null), rows, actions, false, -1, this::pickCity);
            });
        });
    }

    private void setCity(Places.Place p) {
        cityName = p.name;
        cityLat = p.lat;
        cityLon = p.lon;
        prefs.edit().putString("cityName", p.name).putLong("cityLat", Double.doubleToLongBits(p.lat))
                .putLong("cityLon", Double.doubleToLongBits(p.lon)).remove("weather").apply();
        Places.remember(prefs, p);
        weather = null;
        weatherAt = 0;
        showWeather();
        fetchWeather();
        openSettings();
    }

    private void pickLanguage() {
        List<String[]> rows = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (int i = 0; i < LANG_NAMES.length; i++) {
            int c = i;
            rows.add(new String[]{LANG_NAMES[i], null});
            actions.add(() -> {
                langIndex = c;
                prefs.edit().putString("langCode", LANG_CODES[c]).apply();
                loadTrending(true);
                openSettings();
            });
        }
        openMenu(menuHeader("Trending language", "Original language of films and series"), rows, actions, false,
                langIndex, this::openSettings);
    }

    private View menuHeader(String title, String sub) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(px(18), px(10), px(18), px(10));
        TextView t = text(18, 0xFFFFFFFF, "sans-serif-medium");
        t.setText(title);
        h.addView(t);
        if (sub != null) {
            TextView s = text(12.5f, 0xB3FFFFFF, "sans-serif");
            s.setText(sub);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.topMargin = px(4);
            h.addView(s, l);
        }
        return h;
    }

    // =====================================================================================================
    // Trending (TMDB): what is popular on the apps installed here
    // =====================================================================================================

    /** Installed app for a TMDB provider name ("Amazon Prime Video" → Prime Video), or null. */
    private Apps.Entry appFor(String provider, List<Apps.Entry> apps) {
        String n = Tmdb.norm(provider);
        String lower = provider.toLowerCase(Locale.ROOT);
        if (n.length() < 3 || lower.contains("store") || lower.contains("channel") || lower.contains("kids")) return null;
        Apps.Entry best = null;
        for (Apps.Entry e : apps) {
            String a = Tmdb.norm(e.label);
            if (a.equals(n)) return e;
            if (a.length() >= 3 && (a.contains(n) || n.contains(a))
                    && (best == null || e.label.length() < best.label.length())) best = e;
        }
        return best;
    }

    private void loadTrending(boolean force) {
        String lang = LANG_CODES[langIndex];
        boolean fresh = System.currentTimeMillis() - trendingAt < TRENDING_TTL && lang.equals(trendingLang);
        if (trendingBusy || (!force && fresh && !trending.isEmpty()) || lists.all.isEmpty()) return;
        if (!tmdb.hasKey()) {
            trendingStatus = "Add a TMDB key to see Trending";
            showTrendingCard();
            return;
        }
        trendingBusy = true;
        List<Apps.Entry> apps = lists.all;
        io.execute(() -> {
            List<Tmdb.Item> items = null;
            String status;
            try {
                java.util.Set<Integer> ids = new java.util.LinkedHashSet<>();
                java.util.Set<String> pkgs = new java.util.HashSet<>();
                for (Map.Entry<Integer, String> p : tmdb.providers().entrySet()) {
                    Apps.Entry a = appFor(p.getValue(), apps);
                    if (a != null) {
                        ids.add(p.getKey());
                        pkgs.add(a.pkg);
                    }
                }
                ui.post(() -> {
                    if (videoApps.addAll(pkgs)) loadWatching();
                });
                items = ids.isEmpty() ? new ArrayList<>() : tmdb.trending(ids, lang);
                status = items.isEmpty() ? "Nothing trending in " + LANG_NAMES[langIndex] + " on your apps" : null;
            } catch (java.net.UnknownHostException e) {
                status = "Can't reach TMDB: check the internet or Private DNS";
            } catch (Exception e) {
                status = "Can't load Trending right now";
            }
            List<Tmdb.Item> result = items;
            String st = status;
            ui.post(() -> {
                trendingBusy = false;
                if (result != null) {
                    trending = result;
                    trendingAt = System.currentTimeMillis();
                    trendingLang = lang;
                }
                trendingStatus = st;
                showTrendingCard();
                if (state == 3) fillDiscover();
            });
        });
    }

    private void showTrendingCard() {
        if (trendTop == null) return;
        if (trending.isEmpty()) {
            trendTop.setText(trendingStatus == null ? "Loading…" : trendingStatus);
            trendMeta.setText(LANG_NAMES[langIndex]);
            for (ImageView f : fan) f.setImageDrawable(null);
            return;
        }
        Tmdb.Item top = trending.get(0);
        trendTop.setText(top.title);
        int nSeries = 0;
        for (Tmdb.Item it : trending) if (it.tv) nSeries++;
        int nFilms = trending.size() - nSeries;
        trendMeta.setText(top.meta() + "\n" + LANG_NAMES[langIndex] + "  ·  " + nFilms + (nFilms == 1 ? " film" : " films")
                + "  ·  " + nSeries + " series");
        for (int k = 0; k < 3; k++) {
            if (k < trending.size()) images.load(Tmdb.IMG + "w185" + trending.get(k == 1 ? 0 : k == 0 ? 1 : 2).poster, fan[k]);
        }
    }

    // ---- state 3: an opened stack ----

    private void openExpanded(int which) {
        if (expanded != which) lastCard = 0;
        expanded = which;
        fillDiscover();
        setState(3, true);
        focusExpanded();
    }

    private void closeExpanded() {
        int which = Math.max(0, expanded);
        setState(2, true);
        stacks[which].requestFocus();
        expanded = -1;
    }

    private void focusExpanded() {
        // The opened stack's cards first; its list neighbour only when the opened stack is empty.
        if (!cards.isEmpty()) cards.get(Math.min(lastCard, cards.size() - 1)).requestFocus();
        else if (!listRows.isEmpty()) listRows.get(0).requestFocus();
    }

    private void fillDiscover() {
        View had = getCurrentFocus();
        boolean hadFocus = had != null && had.getTag() instanceof int[]
                && (((int[]) had.getTag())[0] == G_CARD || ((int[]) had.getTag())[0] == G_LIST);
        wide.removeAllViews();
        narrow.removeAllViews();
        cards.clear();
        listRows.clear();
        rowSplit = -1;
        lastCard = Math.min(lastCard, Math.max(0, trending.size() - 1));
        if (expanded == 0) {
            fillTrendingWide();
            fillWatchingList();
        } else {
            fillWatchingWide();
            fillTrendingList();
        }
        if (hadFocus) focusExpanded();
    }

    private void fillTrendingWide() {
        TextView head = text(14, 0xCCFFFFFF, "sans-serif-medium");
        head.setText("Trending  ·  " + LANG_NAMES[langIndex]);
        wide.addView(head, lp(-2, -2, Gravity.TOP | Gravity.START, 26, 16, 0, 0));
        detTitle = text(22, 0xFFFFFFFF, "sans-serif-medium");
        detTitle.setSingleLine(true);
        detTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        wide.addView(detTitle, lp(-1, -2, Gravity.TOP | Gravity.START, 26, 38, 26, 0));
        detMeta = text(13, 0xCCFFFFFF, "sans-serif");
        wide.addView(detMeta, lp(-1, -2, Gravity.TOP | Gravity.START, 26, 70, 26, 0));
        detOverview = text(13, 0xE6FFFFFF, "sans-serif");
        detOverview.setMaxLines(3);
        detOverview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detOverview.setLineSpacing(px(2), 1f);
        wide.addView(detOverview, lp(-1, -2, Gravity.TOP | Gravity.START, 26, 92, 26, 0));
        if (trending.isEmpty()) {
            detTitle.setText(trendingStatus == null ? "Loading…" : trendingStatus);
            return;
        }
        // Two rows: films, then series (each row only if it has titles). Cards are one list, films first.
        detOverview.setMaxLines(2);
        List<Tmdb.Item> films = new ArrayList<>(), series = new ArrayList<>();
        for (Tmdb.Item it : trending) (it.tv ? series : films).add(it);
        shownTrending = new ArrayList<>(films);
        shownTrending.addAll(series);
        rowSplit = films.isEmpty() || series.isEmpty() ? -1 : films.size();
        rowLast[0] = 0;
        rowLast[1] = Math.max(0, rowSplit);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        if (!films.isEmpty()) rows.addView(posterRow("Films", films, 0));
        if (!series.isEmpty()) rows.addView(posterRow("Series", series, films.size()));
        wide.addView(rows, lp(-1, -2, Gravity.BOTTOM, 0, 0, 0, 8));
        showDetails(shownTrending.get(Math.min(lastCard, shownTrending.size() - 1)));
    }

    /** One labelled, scrolling row of posters; card numbers start at first (an index into cards). */
    private View posterRow(String label, List<Tmdb.Item> items, int first) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        TextView head = text(13, 0xB3FFFFFF, "sans-serif-medium");
        head.setText(label + "  ·  " + items.size());
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.setMarginStart(px(26));
        box.addView(head, hl);
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        // Clip the row to the panel (it scrolls), but let the focused poster grow into the padding.
        scroll.setClipChildren(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(px(26), px(7), px(26), px(10));
        scroll.setSmoothScrollingEnabled(true);
        // The scroll keeps the focused poster this far from the edge (fading edge length), with a soft fade.
        scroll.setHorizontalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(px(48));
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        int rowNo = first == 0 ? 0 : 1;
        for (int k = 0; k < items.size(); k++) {
            Tmdb.Item it = items.get(k);
            int idx = first + k;
            FrameLayout card = new FrameLayout(this);
            card.setFocusable(true);
            card.setClickable(true);
            card.setTag(new int[]{G_CARD, idx});
            // Landscape card, like Continue watching: the title's still (backdrop) with its name on a shade.
            card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(10));
                }
            });
            card.setClipToOutline(true);
            ImageView img = posterView(10);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            boolean still = it.backdrop != null && !it.backdrop.isEmpty() && !"null".equals(it.backdrop);
            images.load(Tmdb.IMG + (still ? "w300" + it.backdrop : "w185" + it.poster), img);
            View shade = new View(this);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xCC000000, 0x00000000}));
            card.addView(shade, new FrameLayout.LayoutParams(-1, px(46), Gravity.BOTTOM));
            TextView name = text(12, 0xFFFFFFFF, "sans-serif-medium");
            name.setText(it.title);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(name, lp(-1, -2, Gravity.BOTTOM, 9, 0, 9, 6));
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(px(10));
            ring.setStroke(px(2.5f), 0xFFFFFFFF);
            StateListDrawable fg = new StateListDrawable();
            fg.addState(new int[]{android.R.attr.state_focused}, ring);
            fg.addState(new int[]{}, new GradientDrawable());
            card.setForeground(fg);
            card.setOnFocusChangeListener((v, has) -> {
                float sc = has ? 1.08f : 1f;
                v.animate().scaleX(sc).scaleY(sc).translationZ(has ? px(10) : 0).setDuration(150).start();
                if (has) {
                    lastCard = idx;
                    rowLast[rowNo] = idx;
                    showDetails(it);
                }
            });
            card.setOnClickListener(v -> openTitle(it, v));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(px(168), px(95));
            if (k > 0) l.setMarginStart(px(14));
            row.addView(card, l);
            cards.add(card);
        }
        scroll.addView(row);
        box.addView(clipBox(scroll), new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void showDetails(Tmdb.Item it) {
        if (detTitle == null) return;
        detTitle.setText(it.title);
        detMeta.setText(it.meta());
        detOverview.setText(it.overview);
    }

    private void fillWatchingWide() {
        TextView head = text(14, 0xCCFFFFFF, "sans-serif-medium");
        head.setText("Continue watching");
        wide.addView(head, lp(-2, -2, Gravity.TOP | Gravity.START, 26, 20, 0, 0));
        detTitle = text(24, 0xFFFFFFFF, "sans-serif-medium");
        detTitle.setSingleLine(true);
        detTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        wide.addView(detTitle, lp(-1, -2, Gravity.TOP | Gravity.START, 26, 46, 26, 0));
        detMeta = text(13, 0xCCFFFFFF, "sans-serif");
        wide.addView(detMeta, lp(-1, -2, Gravity.TOP | Gravity.START, 26, 80, 26, 0));
        detOverview = text(13, 0xE6FFFFFF, "sans-serif");
        detOverview.setMaxLines(2);
        detOverview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        wide.addView(detOverview, lp(-1, -2, Gravity.TOP | Gravity.START, 26, 104, 26, 0));
        if (watching.isEmpty()) {
            detTitle.setText("Nothing to continue yet");
            detOverview.setText("Start a show in any app; it appears here when you come back.");
            return;
        }
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(true);
        scroll.setPadding(px(26), px(14), px(26), px(14));
        scroll.setHorizontalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(px(48));
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        for (int i = 0; i < watching.size(); i++) {
            Watching.Item it = watching.get(i);
            FrameLayout card = new FrameLayout(this);
            card.setFocusable(true);
            card.setClickable(true);
            card.setTag(new int[]{G_CARD, i});
            card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(12));
                }
            });
            card.setClipToOutline(true);
            ImageView img = posterView(12);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            setWatchImage(it, img);
            // Name strip at the bottom, then the progress bar when the app reported one.
            View shade = new View(this);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xCC000000, 0x00000000}));
            card.addView(shade, new FrameLayout.LayoutParams(-1, px(54), Gravity.BOTTOM));
            TextView name = text(12.5f, 0xFFFFFFFF, "sans-serif-medium");
            name.setText(it.title);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(name, lp(-1, -2, Gravity.BOTTOM, 10, 0, 10, it.progress() > 0 ? 12 : 8));
            if (it.progress() > 0) {
                View track = new View(this);
                track.setBackgroundColor(0x55FFFFFF);
                card.addView(track, lp(-1, 3, Gravity.BOTTOM, 0, 0, 0, 0));
                View bar = new View(this);
                bar.setBackgroundColor(0xFFFFFFFF);
                card.addView(bar, new FrameLayout.LayoutParams(Math.round(px(208) * it.progress()), px(3), Gravity.BOTTOM));
            }
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(px(12));
            ring.setStroke(px(2.5f), 0xFFFFFFFF);
            StateListDrawable fg = new StateListDrawable();
            fg.addState(new int[]{android.R.attr.state_focused}, ring);
            fg.addState(new int[]{}, new GradientDrawable());
            card.setForeground(fg);
            int idx = i;
            card.setOnFocusChangeListener((v, has) -> {
                float sc = has ? 1.08f : 1f;
                v.animate().scaleX(sc).scaleY(sc).translationZ(has ? px(10) : 0).setDuration(150).start();
                if (has) {
                    lastCard = idx;
                    showWatchDetails(it);
                }
            });
            card.setOnClickListener(v -> open(it.open));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(px(208), px(117));
            if (i > 0) l.setMarginStart(px(16));
            row.addView(card, l);
            cards.add(card);
        }
        scroll.addView(row);
        wide.addView(clipBox(scroll), lp(-1, -2, Gravity.BOTTOM, 0, 0, 0, 40));
        showWatchDetails(watching.get(Math.min(lastCard, watching.size() - 1)));
    }

    private void showWatchDetails(Watching.Item it) {
        if (detTitle == null) return;
        detTitle.setText(it.title);
        detMeta.setText(it.meta());
        detOverview.setText(it.subtitle);
    }

    /** The item's picture, or its app's TV banner when there is none (an app-only entry). */
    private void setWatchImage(Watching.Item it, ImageView img) {
        if (it.image != null && !it.image.isEmpty()) {
            images.load(it.image, img);
        } else if (it.app != null) {
            img.setImageDrawable(it.app.banner != null ? it.app.banner : it.app.icon);
            img.setScaleType(it.app.banner != null ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.CENTER_INSIDE);
        }
    }

    private void loadWatching() {
        if (lists.all.isEmpty()) return;
        List<Apps.Entry> apps = lists.all;
        java.util.Set<String> video = new java.util.HashSet<>(videoApps);
        io.execute(() -> {
            NowPlaying.capture(this, getPackageName());
            List<Watching.Item> items = Watching.load(this, apps, video);
            ui.post(() -> {
                watching = items;
                showWatchingCard();
                if (state == 3) fillDiscover();
            });
        });
    }

    private void showWatchingCard() {
        if (cwTop == null) return;
        if (watching.isEmpty()) {
            cwTop.setText("Nothing to continue yet");
            cwMeta.setText("Shows you start appear here");
            for (ImageView f : cwFan) f.setImageDrawable(null);
            return;
        }
        Watching.Item top = watching.get(0);
        cwTop.setText(top.title);
        cwMeta.setText(top.meta());
        int[] order = {1, 0, 2};   // centre card = newest
        for (int k = 0; k < 3; k++) {
            int idx = order[k];
            if (idx < watching.size()) {
                cwFan[k].setVisibility(View.VISIBLE);
                setWatchImage(watching.get(idx), cwFan[k]);
            } else {
                cwFan[k].setVisibility(View.INVISIBLE);
            }
        }
    }

    private void fillTrendingList() {
        LinearLayout box = listBox("Trending");
        if (trending.isEmpty()) {
            listRow(box, trendingStatus == null ? "Loading…" : trendingStatus, null);
        }
        for (int i = 0; i < Math.min(7, trending.size()); i++) {
            Tmdb.Item it = trending.get(i);
            listRow(box, (i + 1) + "   " + it.title, v -> openTitle(it, v));
        }
    }

    private void fillWatchingList() {
        LinearLayout box = listBox("Continue watching");
        if (watching.isEmpty()) listRow(box, "Nothing to continue yet", null);
        for (Watching.Item it : watching) {
            listRow(box, it.title + "  \u00B7  " + it.appLabel, v -> open(it.open));
        }
    }

    private LinearLayout listBox(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(10), px(16), px(10), px(10));
        TextView head = text(14, 0xCCFFFFFF, "sans-serif-medium");
        head.setText(title);
        head.setPadding(px(14), 0, 0, px(8));
        box.addView(head);
        narrow.addView(box, new FrameLayout.LayoutParams(-1, -1));
        return box;
    }

    private void listRow(LinearLayout box, String label, View.OnClickListener click) {
        TextView row = text(14, 0xF2FFFFFF, "sans-serif");
        row.setText(label);
        row.setSingleLine(true);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(14), 0, px(14), 0);
        row.setFocusable(true);
        row.setClickable(true);
        row.setTag(new int[]{G_LIST, listRows.size()});
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x4DFFFFFF);
        on.setCornerRadius(px(12));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, new GradientDrawable());
        row.setBackground(bg);
        if (click != null) row.setOnClickListener(click);
        box.addView(row, new LinearLayout.LayoutParams(-1, px(40)));
        listRows.add(row);
    }

    // ---- a title's sheet: open it in the right app, find it with Google, or watch the trailer ----

    private void openTitle(Tmdb.Item it, View from) {
        List<Apps.Entry> apps = lists.all;
        io.execute(() -> {
            List<String> where = new ArrayList<>();
            String trailer = null;
            try {
                where = tmdb.whereToWatch(it);
            } catch (Exception ignored) {
            }
            try {
                trailer = tmdb.trailer(it);
            } catch (Exception ignored) {
            }
            List<Apps.Entry> on = new ArrayList<>();
            for (String w : where) {
                Apps.Entry e = appFor(w, apps);
                if (e != null && !on.contains(e)) on.add(e);
            }
            String key = trailer;
            ui.post(() -> showTitleSheet(it, on, key, from));
        });
    }

    private void showTitleSheet(Tmdb.Item it, List<Apps.Entry> on, String trailerKey, View from) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(px(18), px(12), px(18), px(14));
        TextView t = text(21, 0xFFFFFFFF, "sans-serif-medium");
        t.setText(it.title);
        TextView m = text(13, 0xCCFFFFFF, "sans-serif");
        m.setText(it.meta() + (on.isEmpty() ? "" : "  ·  on " + on.get(0).label));
        TextView o = text(13, 0xE6FFFFFF, "sans-serif");
        o.setText(it.overview);
        o.setMaxLines(4);
        o.setEllipsize(android.text.TextUtils.TruncateAt.END);
        o.setLineSpacing(px(2), 1f);
        h.addView(t);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.topMargin = px(5);
        h.addView(m, ml);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-1, -2);
        ol.topMargin = px(10);
        h.addView(o, ol);

        List<String[]> rows = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (int i = 0; i < Math.min(2, on.size()); i++) {
            Apps.Entry e = on.get(i);
            rows.add(new String[]{"Open " + e.label, null});
            actions.add(() -> open(e.launch));
        }
        // Off by default: until Google Assistant is set up, it opens Assistant's setup (and its intro video).
        if (prefs.getBoolean("googleSearch", false)) {
            rows.add(new String[]{"Find with Google", null});
            actions.add(() -> googleSearch(it.title));
        }
        if (trailerKey != null) {
            rows.add(new String[]{"Watch the trailer", null});
            actions.add(() -> {
                Intent yt = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + trailerKey));
                try {
                    startActivity(new Intent(yt).setPackage("com.google.android.youtube.tv")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException e) {
                    open(yt);
                }
            });
        }
        openMenu(h, rows, actions, true, -1, () -> {
            if (from != null && from.isShown()) from.requestFocus();
            else focusHome();
        });
    }

    /** Android TV's Google search: its result for a film usually has "Watch on <app>" links into that app. */
    private void googleSearch(String query) {
        Intent search = new Intent("android.search.action.GLOBAL_SEARCH")
                .putExtra(android.app.SearchManager.QUERY, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(search);
        } catch (ActivityNotFoundException e) {
            open(new Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, query));
        }
    }

    // =====================================================================================================
    // Wallpaper picker
    // =====================================================================================================

    private static final String[] TIMES = {"morning", "day", "evening", "night"};
    private static final String[] WEATHERS = {"clear", "cloudy", "rain", "mist", "windy"};

    private View buildWallPicker() {
        wallPanel = pane();
        wallPanel.setRadius(px(30));
        wallPanel.setShowRing(false);
        TextView title = text(22, 0xFFFFFFFF, "sans-serif-medium");
        title.setText("Wallpaper");
        wallPanel.addView(title, lp(-2, -2, Gravity.TOP | Gravity.START, 34, 22, 0, 0));
        TextView sub = text(13, 0xB3FFFFFF, "sans-serif");
        sub.setText("Pick a clip to keep it, or Automatic to follow the time of day and the weather");
        wallPanel.addView(sub, lp(-2, -2, Gravity.TOP | Gravity.START, 34, 54, 0, 0));
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, px(14), 0, px(24));
        wallGrid = new GridLayout(this);
        wallGrid.setColumnCount(WALL_COLS);
        wallGrid.setClipChildren(false);
        FrameLayout center = new FrameLayout(this);
        center.setClipChildren(false);
        center.addView(wallGrid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
        scroll.addView(center);
        FrameLayout clip = new FrameLayout(this);
        clip.setClipChildren(true);
        clip.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        wallPanel.addView(clip, lp(-1, -1, Gravity.TOP, 0, 80, 0, 0));
        wallPanel.setVisibility(View.GONE);
        return wallPanel;
    }

    /** "evening-cloudy" → "Evening · Cloudy". */
    private static String wallName(String key) {
        StringBuilder b = new StringBuilder();
        for (String part : key.split("-")) {
            if (part.isEmpty()) continue;
            if (b.length() > 0) b.append("  ·  ");
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.toString();
    }

    /** Wallpapers on the TV, in time-of-day then weather order; any other names come last. */
    private List<String> wallKeys() {
        List<String> out = new ArrayList<>();
        File dir = getExternalFilesDir("wallpapers");
        if (dir == null) return out;
        String[] files = dir.list();
        if (files == null) return out;
        java.util.Set<String> found = new java.util.TreeSet<>();
        for (String f : files) {
            if (f.endsWith(".jpg") && !f.endsWith("-blur.jpg")) found.add(f.substring(0, f.length() - 4));
        }
        for (String t : TIMES) {
            for (String w : WEATHERS) {
                if (found.remove(t + "-" + w)) out.add(t + "-" + w);
            }
        }
        out.addAll(found);
        return out;
    }

    private void openWallPicker() {
        wallGrid.removeAllViews();
        wallTiles.clear();
        String pinned = prefs.getString("wall", null);
        File dir = getExternalFilesDir("wallpapers");
        List<String> keys = wallKeys();
        keys.add(0, null); // Automatic
        int focusIndex = 0;
        for (String key : keys) {
            boolean current = key == null ? pinned == null : key.equals(pinned);
            FrameLayout tile = new FrameLayout(this);
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setTag(new int[]{G_WALL, wallTiles.size()});
            tile.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(14));
                }
            });
            tile.setClipToOutline(true);
            if (key == null) {
                tile.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{0xFF3A6EA5, 0xFF7A5C9E, 0xFFE39A6B}));
                TextView t = text(16, 0xFFFFFFFF, "sans-serif-medium");
                t.setText("Automatic");
                TextView d = text(11.5f, 0xE6FFFFFF, "sans-serif");
                d.setText("Time of day + weather");
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER);
                t.setGravity(Gravity.CENTER);
                d.setGravity(Gravity.CENTER);
                box.addView(t);
                box.addView(d);
                tile.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            } else {
                ImageView img = posterView(14);
                tile.addView(img, new FrameLayout.LayoutParams(-1, -1));
                images.load(new File(dir, key + ".jpg").getPath(), img);
                View shade = new View(this);
                shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                        new int[]{0xB3000000, 0x00000000}));
                tile.addView(shade, new FrameLayout.LayoutParams(-1, px(44), Gravity.BOTTOM));
                TextView name = text(12.5f, 0xFFFFFFFF, "sans-serif-medium");
                name.setText(wallName(key) + (new File(dir, key + ".mp4").isFile() ? "" : "  (still)"));
                tile.addView(name, lp(-2, -2, Gravity.BOTTOM | Gravity.START, 10, 0, 0, 8));
            }
            if (current) {
                TextView tick = text(12, 0xFF000000, "sans-serif-medium");
                tick.setText("✓ In use");
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(0xF2FFFFFF);
                pill.setCornerRadius(px(10));
                tick.setBackground(pill);
                tick.setPadding(px(8), px(3), px(8), px(3));
                tile.addView(tick, lp(-2, -2, Gravity.TOP | Gravity.END, 0, 8, 8, 0));
                focusIndex = wallTiles.size();
            }
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(px(14));
            ring.setStroke(px(2.5f), 0xFFFFFFFF);
            StateListDrawable fg = new StateListDrawable();
            fg.addState(new int[]{android.R.attr.state_focused}, ring);
            fg.addState(new int[]{}, new GradientDrawable());
            tile.setForeground(fg);
            tile.setOnFocusChangeListener((v, has) -> {
                float sc = has ? 1.06f : 1f;
                v.animate().scaleX(sc).scaleY(sc).translationZ(has ? px(8) : 0).setDuration(150).start();
            });
            tile.setOnClickListener(v -> {
                setWallPin(key);
                Toast.makeText(this, key == null ? "Wallpaper follows time of day and weather"
                        : "Wallpaper: " + wallName(key), Toast.LENGTH_SHORT).show();
                closeOverlays();
            });
            GridLayout.LayoutParams l = new GridLayout.LayoutParams();
            l.width = px(176);
            l.height = px(99);
            l.setMargins(px(8), px(8), px(8), px(8));
            wallGrid.addView(tile, l);
            wallTiles.add(tile);
        }
        menu.setVisibility(View.GONE);
        menuBack = null;
        wallPanel.setAlpha(0);
        wallPanel.setVisibility(View.VISIBLE);
        updatePlayback();
        for (View v : homeLayers) v.animate().alpha(0).setDuration(150).start();
        stacksHost.animate().alpha(0).setDuration(150).start();
        wallPanel.animate().alpha(1).setDuration(200).setUpdateListener(a -> wallPanel.invalidate()).start();
        wallTiles.get(focusIndex).requestFocus();
    }

    /** null = automatic. Applies straight away. */
    private void setWallPin(String key) {
        if (key == null) prefs.edit().remove("wall").apply();
        else prefs.edit().putString("wall", key).apply();
        wallKey = null;
        updateWallpaper();
    }
}
