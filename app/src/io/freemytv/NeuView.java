package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bruno's home screen: a soft ("neumorphic") design in light, dark or automatic theme with an accent color, four tabs
 * (Início, Programação, Gerenciador, Ajustes). Drawn by hand on one Canvas in a 1920×1080 frame scaled to the screen
 * (TCL's 1 GB models draw their UI at 1280×720). It only draws when something changes: no animations, nothing running
 * while another app is in front.
 *
 * Focus moves by rows: every target belongs to a row, and rows to a group (the header, a side menu, the content).
 * ← → walk the row; ↑ ↓ go to the next row in the same group, landing on the item last used there, else the one under.
 */
final class NeuView extends View implements HomeData.Listener, Recs.Listener {
    private static final float W = 1920, PAD = 96, TOP = 54, CONTENT_TOP = 170, CONTENT_BOTTOM = 1020;
    private static final int HOME = 0, GUIDE = 1, TASKS = 2, SETTINGS = 3, ALL_APPS = 4, WEATHER = 5;
    private static final String[] TAB_NAMES = {"Início", "Programação", "Gerenciador", "Ajustes"};
    private static final long GUIDE_SPAN_MIN = 180;
    private static final int GUIDE_ROWS = 6, APP_COLS = 6, APP_ROWS = 4;
    private static final long VOLUME_CLOSE_MS = 5_000, AUTO_FREE_EVERY = 10 * 60_000L;
    private static final int[] SLEEP_STEPS = {30, 60, 120};

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Neu neu = new Neu();
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final SharedPreferences prefs;
    private final AudioManager audio;

    // State
    private int screen = HOME, sub;
    private String theme;
    private int accent;
    private boolean showWeather, showSystem, showReleases, autoFree, darkNow;
    private List<HomeApps.Item> shelf = new ArrayList<>(), inputs = new ArrayList<>();
    private List<Recs.Pick> picks = new ArrayList<>();
    private List<String[]> allApps = new ArrayList<>();
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Set<String> hidden;
    private boolean editHidden;
    private Guide.Result guide;
    private boolean guideLoading, guideFromTv;
    private long guideFrom, guideAt;
    private int guideTop;
    private Guide.Program guideSel;
    private Guide.Channel guideSelChannel;
    private Tasks.Memory mem;
    private List<Tasks.App> taskApps;
    private boolean tasksLoading;
    private String tasksError;
    private long autoFreeAt;
    private int allTop, allMaxTop;
    private boolean running, warmed;
    private int volume, volumeMax = 1;
    private boolean volumeOpen, muted;

    // Modal confirm and toast
    private String cTitle, cBody, cOk;
    private Runnable cRun;
    private String toast, focusBeforeModal;

    // Text cached per minute
    private String clock = "", date = "";
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.forLanguageTag("pt-BR"));
    private final SimpleDateFormat longDate = new SimpleDateFormat("EEEE, d 'de' MMMM", Locale.forLanguageTag("pt-BR"));
    private final SimpleDateFormat weekday = new SimpleDateFormat("EEE", Locale.forLanguageTag("pt-BR"));

    // Focus targets, rebuilt on every pass (pooled)
    private static final class Target {
        String key, row, group;
        final RectF r = new RectF();
        float radius;
        Runnable action;
        boolean first;
    }
    private final List<Target> pool = new ArrayList<>();
    private int count;
    private String focusKey;
    private boolean focusFirst = true, modal, inModal;
    private float pendingX = -1;
    private int pendingDir;
    private final Map<String, String> rowMemory = new HashMap<>(), groupMemory = new HashMap<>();
    private final Map<Integer, String> screenMemory = new HashMap<>();
    private int lastKey;
    private long lastKeyAt;

    // Paints and scratch
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r1 = new RectF(), r2 = new RectF(), r3 = new RectF(), r4 = new RectF();
    private final Path path = new Path();
    private final float[] tabWidths = new float[4];
    private float scale = 1;

    NeuView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        audio = a.getSystemService(AudioManager.class);
        prefs = a.getSharedPreferences("neu", Context.MODE_PRIVATE);
        theme = prefs.getString("theme", "auto");
        accent = prefs.getInt("accent", Neu.ACCENTS[0]);
        showWeather = prefs.getBoolean("weather", true);
        showSystem = prefs.getBoolean("system", true);
        showReleases = prefs.getBoolean("releases", true);
        autoFree = prefs.getBoolean("autofree", false);
        hidden = new HashSet<>(prefs.getStringSet("hidden", new HashSet<>()));
        stroke.setStyle(Paint.Style.STROKE);
        setFocusable(true);
        setFocusableInTouchMode(true);
        recs.setLight(true);
        shelf = byUse(HomeApps.shelf(a, false));
        inputs = readInputs(a);
        mem = Tasks.memory(a);
        updateClock();
        updateTheme();
    }

    // MARK: - Lifecycle (HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        mem = Tasks.memory(getContext());
        updatePicks();
        updateClock();
        updateTheme();
        final Context c = getContext().getApplicationContext();
        io.execute(() -> {
            final List<HomeApps.Item> s = byUse(HomeApps.shelf(c, true));
            final List<HomeApps.Item> in = readInputs(c);
            handler.post(() -> { shelf = s; inputs = in; invalidate(); });
        });
        loadGuide(false);   // the guide on file (or a new one after 8 h): "no ar" on the TV card
        if (screen == TASKS) loadTasks();
        maybeFreeMemory();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 60_000 - System.currentTimeMillis() % 60_000);
        requestFocus();
        invalidate();
    }

    void pause() {
        running = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(closeVolume);
        volumeOpen = false;
        data.removeListener(this);
        recs.removeListener(this);
    }

    void destroy() {
        pause();
        io.shutdownNow();
    }

    /** The Home button on the home screen: back to Início, focus on TV ao vivo. */
    void reset() {
        closeConfirm();
        volumeOpen = false;
        editHidden = false;
        screen = HOME;
        focusFirst = true;
        invalidate();
    }

    void showAllApps() {
        openAllApps(false);
    }

    void permissionsResult() {
        loadGuide(true);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            updateClock();
            updateTheme();
            data.refreshIfStale();
            recs.refreshIfStale();
            mem = Tasks.memory(getContext());
            updatePicks();
            if (System.currentTimeMillis() - guideAt > NetGuide.KEEP_MS) loadGuide(false);
            invalidate();
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    @Override public void onData() {
        updateTheme();
        invalidate();
    }

    @Override public void onRecs() {
        updatePicks();
        invalidate();
    }

    private void updateClock() {
        Date now = new Date();
        clock = hhmm.format(now);
        String d = longDate.format(now);
        date = d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1);
    }

    private void updateTheme() {
        boolean dark;
        if ("escuro".equals(theme)) dark = true;
        else if ("claro".equals(theme)) dark = false;
        else {
            long now = System.currentTimeMillis();
            HomeData.Weather wx = data.weather;
            if (wx != null && wx.sunrise > 0 && wx.sunset > 0 && Math.abs(now - wx.sunrise) < 86_400_000L) {
                dark = now < wx.sunrise || now >= wx.sunset;
            } else {
                int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                dark = h >= 18 || h < 6;
            }
        }
        if (dark != darkNow || neu.bg == 0 || neu.accent != accent) {
            darkNow = dark;
            neu.apply(dark, accent);
            neu.clear();
            warmed = false;
            activity.getWindow().setStatusBarColor(neu.bg);
        }
    }

    private void updatePicks() {
        if (!Recs.hasKey(getContext())) {
            picks = new ArrayList<>();
            return;
        }
        List<Recs.Pick> out = new ArrayList<>();
        for (Recs.Pick p : recs.wanted()) {
            if (p.pkg == null) continue;
            out.add(p);
            recs.backdrop(p);   // builds the poster in the background (light design) and tells us when it's ready
            if (out.size() == 5) break;
        }
        picks = out;
    }

    /** The shelf in order of real use (how many times each app was opened from here), ties as they came. */
    private List<HomeApps.Item> byUse(List<HomeApps.Item> list) {
        List<HomeApps.Item> out = new ArrayList<>(list);
        final Map<String, Integer> uses = new HashMap<>();
        for (HomeApps.Item it : out) uses.put(it.pkg, prefs.getInt("use:" + it.pkg, 0));
        Collections.sort(out, (a, b) -> Integer.compare(uses.get(b.pkg), uses.get(a.pkg)));
        return out;
    }

    /** HDMI and AV inputs (all of them: TCL reports every port as "standby", device or not). */
    private static List<HomeApps.Item> readInputs(Context c) {
        List<HomeApps.Item> out = new ArrayList<>();
        for (HomeApps.Item it : HomeApps.allInputs(c)) if (it.kind == HomeApps.INPUT) out.add(it);
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        try {
            if (tim != null) for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_COMPOSITE || info.getParentId() != null) continue;
                HomeApps.Item it = new HomeApps.Item();
                it.kind = HomeApps.INPUT;
                it.key = "av";
                it.label = "AV";
                it.inputId = info.getId();
                it.number = "0";
                out.add(it);
                break;
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        scale = getWidth() / W;
        c.drawColor(neu.bg);
        // Geometry first when the focus has to land somewhere new (a new tab, a scrolled list, a closed dialog).
        if (focusFirst || pendingDir != 0 || !hasFocusKey()) {
            pass(null);
            resolveFocus();
        }
        c.save();
        c.scale(scale, scale);
        pass(c);
        drawFocus(c);
        c.restore();
        if (!warmed) {
            warmed = true;
            handler.postDelayed(this::warm, 400);
        }
    }

    /**
     * Builds every screen's shadows ahead, one screen per message, on a tiny canvas (only the masks matter): the first
     * visit to a tab then costs no more than the next ones.
     */
    private void warm() {
        final int[] screens = {SETTINGS, TASKS, GUIDE, WEATHER, ALL_APPS};
        final Canvas tiny = new Canvas(Bitmap.createBitmap(2, 2, Bitmap.Config.ALPHA_8));
        for (int i = 0; i < screens.length; i++) {
            final int s = screens[i];
            handler.postDelayed(() -> {
                if (!running || cTitle != null || volumeOpen || screen == s) return;
                int was = screen, wasSub = sub;
                for (int k = 0; k < (s == SETTINGS ? 3 : 1); k++) {
                    screen = s;
                    sub = k;
                    pass(tiny);
                }
                screen = was;
                sub = wasSub;
                pass(null);   // the targets of the screen in view again
            }, 120L * i);
        }
    }

    private boolean hasFocusKey() {
        return focused() != null;
    }

    /** Rows of the lists that scroll: the guide's channels and all the apps. */
    private static boolean scrolls(Target t) {
        return t.row.startsWith("g") || t.row.startsWith("aa") && !t.row.equals("aa-top");
    }

    private void resolveFocus() {
        if (pendingDir != 0) {
            // After scrolling: the target in the row that came in, nearest the column we were on.
            Target best = null;
            float bestD = Float.MAX_VALUE, edge = pendingDir > 0 ? -Float.MAX_VALUE : Float.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                Target t = pool.get(i);
                if (!scrolls(t)) continue;
                edge = pendingDir > 0 ? Math.max(edge, t.r.centerY()) : Math.min(edge, t.r.centerY());
            }
            for (int i = 0; i < count; i++) {
                Target t = pool.get(i);
                if (!scrolls(t) || Math.abs(t.r.centerY() - edge) > 1) continue;
                float d = Math.abs(Math.max(t.r.left, Math.min(pendingX, t.r.right)) - pendingX);
                if (d < bestD) { bestD = d; best = t; }
            }
            pendingDir = 0;
            if (best != null) { setFocus(best); focusFirst = false; return; }
        }
        if (focusFirst || !hasFocusKey()) {
            Target first = null;
            for (int i = 0; i < count; i++) if (pool.get(i).first) { first = pool.get(i); break; }
            if (first == null) for (int i = 0; i < count; i++) if (!"hdr".equals(pool.get(i).group)) { first = pool.get(i); break; }
            if (first == null && count > 0) first = pool.get(0);
            if (first != null) setFocus(first); else focusKey = null;
            focusFirst = false;
        }
    }

    /** Lays out (and, with a canvas, draws) the whole screen; registers the focus targets on the way. */
    private void pass(Canvas c) {
        count = 0;
        modal = cTitle != null || volumeOpen;
        inModal = false;
        header(c);
        switch (screen) {
            case GUIDE: guideScreen(c); break;
            case TASKS: tasksScreen(c); break;
            case SETTINGS: settingsScreen(c); break;
            case ALL_APPS: allAppsScreen(c); break;
            case WEATHER: weatherScreen(c); break;
            default: homeScreen(c); break;
        }
        text(c, "Setas navegam  ·  OK abre  ·  Voltar retorna", W / 2, 1062, 22, Ui.regular, neu.muted, 1);
        if (toast != null && c != null) {
            tp.setTypeface(Ui.semibold);
            tp.setTextSize(26);
            float w = Math.min(1500, tp.measureText(toast) + 120);
            r1.set(W / 2 - w / 2, 940, W / 2 + w / 2, 1024);
            neu.raised(c, r1, 42, Neu.RAISED, 0);
            fill.setColor(neu.accent);
            c.drawCircle(r1.left + 42, r1.centerY(), 22, fill);
            check(c, r1.left + 42, r1.centerY(), 12, neu.onAccent);
            text(c, ellipsize(toast, w - 110, 26, Ui.semibold), r1.left + 82, r1.centerY() + 9, 26, Ui.semibold, neu.text, 0);
        }
        if (volumeOpen) volumePanel(c);
        if (cTitle != null) confirmDialog(c);
    }

    // MARK: Header

    private void header(Canvas c) {
        text(c, clock, PAD, TOP + 50, 56, Ui.displaySemibold, neu.text, 0);
        text(c, date, PAD, TOP + 86, 24, Ui.regular, neu.muted, 0);
        tp.setTypeface(Ui.semibold);
        tp.setTextSize(26);
        float total = 20 + 12 * 3;
        for (int i = 0; i < 4; i++) {
            tabWidths[i] = tp.measureText(TAB_NAMES[i]) + 52 + 40;
            total += tabWidths[i];
        }
        float x = W / 2 - total / 2;
        r1.set(x, TOP + 2, x + total, TOP + 86);
        if (c != null) neu.inset(c, r1, 42, neu.bg);
        x += 10;
        int current = screen == ALL_APPS || screen == WEATHER ? HOME : screen;
        for (int i = 0; i < 4; i++) {
            r2.set(x, TOP + 12, x + tabWidths[i], TOP + 76);
            final int tab = i;
            boolean on = current == i;
            if (c != null) {
                if (on) neu.raised(c, r2, 32, Neu.RAISED_SMALL, neu.accent);
                int col = on ? neu.onAccent : neu.text;
                tabIcon(c, i, r2.left + 26 + 14, r2.centerY(), col);
                text(c, TAB_NAMES[i], r2.left + 26 + 40, r2.centerY() + 9, 26, Ui.semibold, col, 0);
            }
            target("tab" + i, "hdr", r2, 32, () -> openTab(tab), false);
            x += tabWidths[i] + 12;
        }
        // Volume and the TV's settings, top right
        r2.set(W - PAD - 64 - 24 - 64, TOP + 12, W - PAD - 64 - 24, TOP + 76);
        if (c != null) {
            neu.raised(c, r2, 32, Neu.RAISED_SMALL, 0);
            speaker(c, r2.centerX(), r2.centerY(), neu.text, muted);
        }
        target("volume", "hdr", r2, 32, this::openVolume, false);
        r2.set(W - PAD - 64, TOP + 12, W - PAD, TOP + 76);
        if (c != null) {
            neu.raised(c, r2, 32, Neu.RAISED_SMALL, 0);
            Symbols.draw(getContext(), c, "settings", r2.centerX(), r2.centerY(), 30, neu.text);
        }
        target("gear", "hdr", r2, 32, this::openTvSettings, false);
    }

    private void tabIcon(Canvas c, int i, float cx, float cy, int color) {
        switch (i) {
            case 0: Symbols.draw(getContext(), c, "home", cx, cy, 28, color); break;
            case 1: Symbols.draw(getContext(), c, "live_tv", cx, cy, 28, color); break;
            case 2:
                stroke.setColor(color);
                stroke.setStrokeWidth(2.6f);
                r3.set(cx - 9, cy - 9, cx + 9, cy + 9);
                c.drawRoundRect(r3, 3, 3, stroke);
                r3.set(cx - 4, cy - 4, cx + 4, cy + 4);
                c.drawRect(r3, stroke);
                for (int k = -1; k <= 1; k += 2) {
                    c.drawLine(cx - 4, cy + k * 9, cx - 4, cy + k * 13, stroke);
                    c.drawLine(cx + 4, cy + k * 9, cx + 4, cy + k * 13, stroke);
                    c.drawLine(cx + k * 9, cy - 4, cx + k * 13, cy - 4, stroke);
                    c.drawLine(cx + k * 9, cy + 4, cx + k * 13, cy + 4, stroke);
                }
                break;
            default: Symbols.draw(getContext(), c, "tv_settings", cx, cy, 28, color); break;
        }
    }

    // MARK: Início

    private void homeScreen(Canvas c) {
        float top = CONTENT_TOP, h = 340, right = W - PAD;
        float tvRight = right;
        if (showSystem) tvRight -= 480 + 24;
        if (showWeather) tvRight -= 440 + 24;
        // TV ao vivo: the picture area opens the antenna, the chips below it the inputs
        r1.set(PAD, top, tvRight, top + h);
        if (c != null) neu.raised(c, r1, 32, Neu.RAISED, 0);
        r4.set(r1.left + 20, r1.top + 20, r1.right - 20, r1.bottom - 104);
        if (c != null) {
            fill.setColor(0xFF101317);
            c.drawRoundRect(r4, 20, 20, fill);
            r3.set(r4.left + 18, r4.top + 18, r4.left + 150, r4.top + 58);
            fill.setColor(0xFFD92D20);
            c.drawRoundRect(r3, 20, 20, fill);
            text(c, "AO VIVO", r3.centerX(), r3.centerY() + 9, 24, Ui.semibold, 0xFFFFFFFF, 1);
            fill.setColor(neu.accent);
            c.drawCircle(r4.left + 64, r4.bottom - 56, 36, fill);
            play(c, r4.left + 67, r4.bottom - 56, 16, neu.onAccent);
            String ch = prefs.getString("lastCh", "12.1");
            Guide.Program now = onAirNow(ch);
            text(c, "TV ao vivo  ·  Canal " + ch, r4.left + 118, r4.bottom - 66, 28, Ui.semibold, 0xFFFFFFFF, 0);
            text(c, ellipsize(now == null ? "OK para assistir" : "No ar: " + now.title, r4.width() - 140, 24, Ui.regular),
                    r4.left + 118, r4.bottom - 32, 24, Ui.regular, 0xFFB8C0CC, 0);
        }
        target("tv", "top", r4, 20, this::openLiveTv, true);
        // Inputs
        float cx = r1.left + 20;
        for (final HomeApps.Item it : inputs) {
            String label = ellipsize(it.label, 150, 24, Ui.semibold);
            tp.setTypeface(Ui.semibold);
            tp.setTextSize(24);
            float w = tp.measureText(label) + 48;
            if (cx + w > r1.right - 20) break;
            r2.set(cx, r1.bottom - 80, cx + w, r1.bottom - 24);
            if (c != null) {
                neu.raised(c, r2, 28, Neu.RAISED_SMALL, 0);
                text(c, label, r2.centerX(), r2.centerY() + 9, 24, Ui.semibold, neu.text, 1);
            }
            target("in:" + it.key, "tvin", r2, 28, () -> openItem(it), false);
            cx += w + 16;
        }
        float x = tvRight + 24;
        if (showWeather) {
            r1.set(x, top, x + 440, top + h);
            if (c != null) weatherCard(c, r1);
            target("wx", "top", r1, 32, () -> openScreen(WEATHER), false);
            x += 440 + 24;
        }
        if (showSystem) {
            r1.set(x, top, x + 480, top + h);
            if (c != null) systemCard(c, r1);
            target("sys", "top", r1, 32, () -> openTab(TASKS), false);
        }
        // Mais usados
        float y = top + h + 28;
        text(c, "Mais usados", PAD, y + 28, 28, Ui.semibold, neu.text, 0);
        y += 40;
        float colW = (W - 2 * PAD - 6 * 24) / 7f;
        int n = Math.min(6, shelf.size());
        for (int i = 0; i < n; i++) {
            final HomeApps.Item it = shelf.get(i);
            r1.set(PAD + i * (colW + 24), y, PAD + i * (colW + 24) + colW, y + 150);
            if (c != null) appTile(c, r1, it);
            target("app:" + it.pkg, "apps", r1, 28, () -> openItem(it), false);
        }
        r1.set(PAD + 6 * (colW + 24), y, W - PAD, y + 150);
        if (c != null) {
            neu.raised(c, r1, 28, Neu.RAISED_SMALL, 0);
            r2.set(r1.centerX() - 32, r1.top + 24, r1.centerX() + 32, r1.top + 88);
            neu.inset(c, r2, 32, 0);
            Symbols.draw(getContext(), c, "apps", r2.centerX(), r2.centerY(), 30, neu.text);
            text(c, "Todos os apps", r1.centerX(), r1.bottom - 24, 24, Ui.semibold, neu.text, 1);
        }
        target("allapps", "apps", r1, 28, () -> openAllApps(false), false);
        // Últimos lançamentos
        if (!showReleases) return;
        y += 150 + 28;
        text(c, "Últimos lançamentos", PAD, y + 28, 28, Ui.semibold, neu.text, 0);
        tp.setTypeface(Ui.semibold);
        tp.setTextSize(28);
        text(c, "só dos apps que você tem", PAD + tp.measureText("Últimos lançamentos") + 16, y + 28, 24, Ui.regular, neu.muted, 0);
        y += 40;
        if (!Recs.hasKey(getContext())) {
            r1.set(PAD, y, W - PAD, y + 184);
            if (c != null) {
                neu.raised(c, r1, 24, Neu.RAISED_SMALL, 0);
                text(c, "Falta a chave gratuita do TMDB para mostrar os lançamentos.", PAD + 36, y + 80, 28, Ui.semibold, neu.text, 0);
                text(c, "OK para abrir os ajustes e colar a chave pelo celular (QR Code).", PAD + 36, y + 124, 24, Ui.regular, neu.muted, 0);
            }
            target("tmdb", "rel", r1, 24, this::openFreeMyTvSettings, false);
            return;
        }
        float cw = (W - 2 * PAD - 4 * 24) / 5f;
        for (int i = 0; i < 5; i++) {
            r1.set(PAD + i * (cw + 24), y, PAD + i * (cw + 24) + cw, y + 184);
            final Recs.Pick p = i < picks.size() ? picks.get(i) : null;
            if (c != null) releaseCard(c, r1, p);
            if (p != null) target("pick:" + Recs.key(p), "rel", r1, 24, () -> openPick(p), false);
        }
    }

    private Guide.Program onAirNow(String number) {
        if (guide == null) return null;
        long now = System.currentTimeMillis();
        for (Guide.Channel ch : guide.channels) {
            if (!ch.number.equals(number)) continue;
            for (Guide.Program p : ch.programs) if (p.start <= now && now < p.end) return p;
        }
        return null;
    }

    private void weatherCard(Canvas c, RectF r) {
        neu.raised(c, r, 32, Neu.RAISED, 0);
        HomeData.Weather wx = data.weather;
        String city = Config.CITY.isEmpty() ? "Tempo" : Config.CITY;
        text(c, city, r.left + 28, r.top + 50, 26, Ui.semibold, neu.text, 0);
        if (wx == null || Double.isNaN(wx.temp)) {
            text(c, "Carregando a previsão…", r.left + 28, r.top + 120, 24, Ui.regular, neu.muted, 0);
            return;
        }
        text(c, HomeData.highLow(wx, " "), r.right - 28, r.top + 50, 24, Ui.regular, neu.muted, 2);
        Symbols.draw(getContext(), c, Symbols.weather(wx.code, wx.isDay), r.left + 70, r.top + 130, 64, neu.accent);
        text(c, HomeData.degrees(wx.temp), r.left + 130, r.top + 150, 72, Ui.displaySemibold, neu.text, 0);
        text(c, ellipsize(HomeData.condition(wx.code, wx.isDay), 260, 24, Ui.regular), r.left + 130, r.top + 188, 24, Ui.regular, neu.muted, 0);
        int n = Math.min(4, wx.hours.size());
        float cw = (r.width() - 56 - 36) / 4f;
        for (int i = 0; i < n; i++) {
            r2.set(r.left + 28 + i * (cw + 12), r.bottom - 112, r.left + 28 + i * (cw + 12) + cw, r.bottom - 28);
            neu.inset(c, r2, 16, neu.bg);
            text(c, HomeData.hour(wx.hours.get(i)), r2.centerX(), r2.top + 34, 22, Ui.regular, neu.muted, 1);
            text(c, HomeData.degrees(wx.hourTemp.get(i)), r2.centerX(), r2.top + 68, 26, Ui.semibold, neu.text, 1);
        }
    }

    private void systemCard(Canvas c, RectF r) {
        neu.raised(c, r, 32, Neu.RAISED, 0);
        text(c, "Sistema", r.left + 28, r.top + 50, 26, Ui.semibold, neu.text, 0);
        text(c, "Gerenciador ›", r.right - 28, r.top + 50, 24, Ui.regular, neu.muted, 2);
        float used = mem == null || mem.total == 0 ? 0 : 1f - mem.available / (float) mem.total;
        ring(c, r.left + 28 + 66, r.top + 146, 66, 18, used, (Math.round(used * 100)) + "%", 30);
        text(c, "Memória em uso", r.left + 186, r.top + 118, 24, Ui.regular, neu.muted, 0);
        text(c, mem == null ? "" : Tasks.size(mem.total - mem.available) + " de " + Tasks.size(mem.total), r.left + 186, r.top + 156, 28, Ui.semibold, neu.text, 0);
        text(c, mem == null ? "" : Tasks.size(mem.available) + " livres", r.left + 186, r.top + 190, 24, Ui.regular, neu.muted, 0);
        if (mem == null || mem.diskTotal == 0) return;
        float du = 1f - mem.diskFree / (float) mem.diskTotal;
        text(c, "Armazenamento", r.left + 28, r.bottom - 92, 24, Ui.regular, neu.muted, 0);
        text(c, Tasks.size(mem.diskTotal - mem.diskFree) + " de " + Tasks.size(mem.diskTotal), r.right - 28, r.bottom - 92, 24, Ui.semibold, neu.text, 2);
        bar(c, r.left + 28, r.bottom - 74, r.width() - 56, 14, du);
        text(c, Tasks.size(mem.diskFree) + " livres", r.left + 28, r.bottom - 26, 24, Ui.regular, neu.muted, 0);
    }

    private void appTile(Canvas c, RectF r, HomeApps.Item it) {
        neu.raised(c, r, 28, Neu.RAISED_SMALL, 0);
        r2.set(r.left + 20, r.top + 18, r.right - 20, r.top + 96);
        Bitmap logo = darkNow ? it.logo : (it.logoDark != null ? it.logoDark : it.logo);
        if (logo != null) {
            fitInto(c, logo, r2, Math.min(1.2f, it.logoScale));
        } else if (it.tile != null) {
            r3.set(r2.centerX() - 69, r2.top, r2.centerX() + 69, r2.top + 78);
            c.drawBitmap(it.tile, null, r3, bmp);
        } else {
            text(c, initials(it.label), r2.centerX(), r2.centerY() + 12, 32, Ui.semibold, neu.text, 1);
        }
        text(c, ellipsize(it.label, r.width() - 24, 24, Ui.semibold), r.centerX(), r.bottom - 22, 24, Ui.semibold, neu.text, 1);
    }

    private void releaseCard(Canvas c, RectF r, Recs.Pick p) {
        neu.raised(c, r, 24, Neu.RAISED_SMALL, 0);
        r2.set(r.left + 14, r.top + 14, r.left + 14 + 113, r.bottom - 14);
        Recs.Poster poster = p == null ? null : recs.poster(p);
        if (poster != null) {
            c.drawBitmap(poster.bitmap, null, r2, bmp);
        } else {
            neu.inset(c, r2, 14, 0);
        }
        float tx = r2.right + 18, tw = r.right - 18 - tx;
        if (p == null) {
            text(c, "Carregando…", tx, r.top + 60, 24, Ui.regular, neu.muted, 0);
            return;
        }
        String[] lines = wrap(p.title == null ? "" : p.title, tw, 24, Ui.semibold, 3);
        float y = r.top + 46;
        for (String line : lines) {
            text(c, line, tx, y, 24, Ui.semibold, neu.text, 0);
            y += 32;
        }
        text(c, ellipsize(Recs.serviceName(p), tw, 22, Ui.regular), tx, r.bottom - 52, 22, Ui.regular, neu.muted, 0);
        text(c, "movie".equals(p.kind) ? "Filme" : "Série", tx, r.bottom - 22, 22, Ui.regular, neu.muted, 0);
    }

    // MARK: Previsão do tempo

    private void weatherScreen(Canvas c) {
        HomeData.Weather wx = data.weather;
        boolean ready = wx != null && !Double.isNaN(wx.temp);
        String title = Config.CITY.isEmpty() ? "Previsão do tempo" : "Previsão do tempo em " + Config.CITY;
        r1.set(PAD, CONTENT_TOP, W - PAD, CONTENT_TOP + 300);
        if (c != null) neu.raised(c, r1, 32, Neu.RAISED, 0);
        if (!ready) {
            text(c, "Carregando a previsão…", r1.left + 40, r1.top + 80, 30, Ui.semibold, neu.text, 0);
        } else if (c != null) {
            text(c, title, r1.left + 40, r1.top + 60, 30, Ui.semibold, neu.text, 0);
            Symbols.draw(getContext(), c, Symbols.weather(wx.code, wx.isDay), r1.left + 110, r1.top + 175, 96, neu.accent);
            text(c, HomeData.degrees(wx.temp), r1.left + 200, r1.top + 205, 110, Ui.displaySemibold, neu.text, 0);
            text(c, HomeData.condition(wx.code, wx.isDay), r1.left + 480, r1.top + 160, 32, Ui.semibold, neu.text, 0);
            text(c, HomeData.highLow(wx, "  "), r1.left + 480, r1.top + 204, 26, Ui.regular, neu.muted, 0);
            if (wx.sunrise > 0) {
                text(c, "Nascer do sol " + hhmm.format(new Date(wx.sunrise)) + "   ·   Pôr do sol " + hhmm.format(new Date(wx.sunset)),
                        r1.right - 40, r1.top + 60, 24, Ui.regular, neu.muted, 2);
            }
            // The next 8 hours
            int n = Math.min(8, wx.hours.size());
            float cw = 110, gx = r1.right - 40 - n * cw - (n - 1) * 12;
            for (int i = 0; i < n; i++) {
                r2.set(gx + i * (cw + 12), r1.top + 110, gx + i * (cw + 12) + cw, r1.bottom - 40);
                neu.inset(c, r2, 18, neu.bg);
                text(c, HomeData.hour(wx.hours.get(i)), r2.centerX(), r2.top + 36, 22, Ui.regular, neu.muted, 1);
                Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), r2.centerX(), r2.centerY() + 2, 32, neu.text);
                text(c, HomeData.degrees(wx.hourTemp.get(i)), r2.centerX(), r2.bottom - 22, 26, Ui.semibold, neu.text, 1);
            }
        }
        // The next 5 days
        float y = CONTENT_TOP + 300 + 32;
        int n = ready ? Math.min(5, wx.days.size()) : 0;
        float dw = (W - 2 * PAD - 4 * 24) / 5f;
        for (int i = 0; i < n; i++) {
            r1.set(PAD + i * (dw + 24), y, PAD + i * (dw + 24) + dw, y + 300);
            if (c != null) {
                neu.raised(c, r1, 28, Neu.RAISED_SMALL, 0);
                String d = weekday.format(new Date(wx.days.get(i))).replace(".", "");
                text(c, d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1), r1.centerX(), r1.top + 56, 28, Ui.semibold, neu.text, 1);
                Symbols.draw(getContext(), c, Symbols.weather(wx.dayCode.get(i), true), r1.centerX(), r1.top + 140, 64, neu.accent);
                text(c, HomeData.degrees(wx.dayMax.get(i)), r1.centerX(), r1.top + 226, 36, Ui.displaySemibold, neu.text, 1);
                text(c, "mín " + HomeData.degrees(wx.dayMin.get(i)), r1.centerX(), r1.top + 266, 24, Ui.regular, neu.muted, 1);
            }
            target("day" + i, "days", r1, 28, null, i == 0);
        }
        if (n == 0) {
            r1.set(PAD, y, PAD + 320, y + 80);
            if (c != null) {
                neu.raised(c, r1, 28, Neu.RAISED_SMALL, 0);
                text(c, "Voltar", r1.centerX(), r1.centerY() + 9, 26, Ui.semibold, neu.text, 1);
            }
            target("wxback", "days", r1, 28, () -> openScreen(HOME), true);
        }
    }

    // MARK: Programação

    private void guideScreen(Canvas c) {
        if (guide == null || guideLoading && guide.channels.isEmpty()) {
            message(c, "Buscando a programação…", "A grade vem da internet e fica guardada por 8 horas.", null, null);
            return;
        }
        if (guide.channels.isEmpty()) {
            message(c, "Não consegui baixar a grade",
                    "Confira se a TV está conectada à internet e tente de novo.",
                    "Tentar de novo", () -> loadGuide(true));
            return;
        }
        long now = System.currentTimeMillis();
        float labelW = 220, gx = PAD + labelW + 20, gw = W - PAD - gx;
        float pxPerMin = gw / GUIDE_SPAN_MIN;
        // Selected program bar
        r1.set(PAD, CONTENT_TOP, W - PAD, CONTENT_TOP + 112);
        if (c != null) {
            neu.raised(c, r1, 28, Neu.RAISED, 0);
            Guide.Program sel = guideSel;
            if (sel != null && guideSelChannel != null) {
                boolean onAir = sel.start <= now && now < sel.end;
                boolean reminded = !onAir && Reminders.has(getContext(), guideSelChannel, sel);
                text(c, ellipsize(sel.title.isEmpty() ? "Sem informação" : sel.title, 1000, 34, Ui.semibold), r1.left + 32, r1.top + 50, 34, Ui.semibold, neu.text, 0);
                text(c, ellipsize(guideSelChannel.name + "  ·  Canal " + guideSelChannel.number + "  ·  " + hhmm.format(new Date(sel.start))
                        + " – " + hhmm.format(new Date(sel.end))
                        + (onAir ? "  ·  OK para assistir" : reminded ? "  ·  OK tira o lembrete" : "  ·  OK para lembrar"), 1250, 24, Ui.regular),
                        r1.left + 32, r1.top + 88, 24, Ui.regular, neu.muted, 0);
                String chip = onAir ? "No ar agora" : reminded ? "Lembrete às " + hhmm.format(new Date(sel.start - 60_000L)) : "Começa às " + hhmm.format(new Date(sel.start));
                tp.setTypeface(Ui.semibold);
                tp.setTextSize(24);
                float cw = tp.measureText(chip) + 44;
                r2.set(r1.right - 32 - cw, r1.centerY() - 24, r1.right - 32, r1.centerY() + 24);
                if (onAir || reminded) {
                    fill.setColor(neu.accent);
                    c.drawRoundRect(r2, 24, 24, fill);
                } else {
                    neu.inset(c, r2, 24, neu.bg);
                }
                text(c, chip, r2.centerX(), r2.centerY() + 9, 24, Ui.semibold, onAir || reminded ? neu.onAccent : neu.text, 1);
            } else {
                text(c, "Escolha um programa", r1.left + 32, r1.top + 66, 30, Ui.semibold, neu.text, 0);
            }
        }
        // Time scale
        float ty = CONTENT_TOP + 112 + 28;
        for (int i = 0; i < 6; i++) {
            long at = guideFrom + i * 30 * 60_000L;
            text(c, hhmm.format(new Date(at)), gx + i * 30 * pxPerMin + 8, ty + 26, 24, Ui.semibold, neu.muted, 0);
        }
        float nowX = gx + (now - guideFrom) / 60_000f * pxPerMin;
        if (c != null && nowX >= gx && nowX <= gx + gw) {
            r2.set(nowX - 46, ty - 2, nowX + 46, ty + 34);
            fill.setColor(neu.accent);
            c.drawRoundRect(r2, 18, 18, fill);
            text(c, clock, nowX, ty + 25, 24, Ui.semibold, neu.onAccent, 1);
        }
        // Rows
        float y = ty + 50;
        List<Guide.Channel> chs = guide.channels;
        guideTop = Math.max(0, Math.min(guideTop, chs.size() - GUIDE_ROWS));
        boolean firstSet = false;
        for (int row = guideTop; row < Math.min(chs.size(), guideTop + GUIDE_ROWS); row++) {
            final Guide.Channel ch = chs.get(row);
            r1.set(PAD, y, PAD + labelW, y + 96);
            if (c != null) {
                neu.raised(c, r1, 20, Neu.RAISED_SMALL, 0);
                text(c, "Canal " + ch.number, r1.left + 22, r1.top + 38, 22, Ui.regular, neu.muted, 0);
                text(c, ellipsize(ch.name, labelW - 40, 26, Ui.semibold), r1.left + 22, r1.top + 72, 26, Ui.semibold, neu.text, 0);
            }
            r2.set(gx, y, gx + gw, y + 96);
            if (c != null) neu.inset(c, r2, 20, 0);
            String rowId = "g" + row;
            boolean any = false;
            for (int pi = 0; pi < ch.programs.size(); pi++) {
                final Guide.Program p = ch.programs.get(pi);
                float a = Math.max(0, (p.start - guideFrom) / 60_000f), b = Math.min(GUIDE_SPAN_MIN, (p.end - guideFrom) / 60_000f);
                if (b <= 0 || a >= GUIDE_SPAN_MIN) continue;
                any = true;
                r3.set(gx + a * pxPerMin + 4, y + 8, gx + Math.max(a * pxPerMin + 40, b * pxPerMin - 4), y + 88);
                boolean onAir = p.start <= now && now < p.end;
                if (c != null) {
                    if (onAir) {
                        fill.setColor(neu.bg);
                        c.drawRoundRect(r3, 14, 14, fill);
                    }
                    boolean bell = !onAir && Reminders.has(getContext(), ch, p);
                    c.save();
                    c.clipRect(r3);
                    float tx = r3.left + 16;
                    if (bell) {
                        Symbols.draw(getContext(), c, "bell", tx + 12, r3.top + 28, 22, neu.accent);
                        tx += 32;
                    }
                    text(c, ellipsize(p.title, r3.right - tx - 16, 24, Ui.semibold), tx, r3.top + 36, 24, Ui.semibold, neu.text, 0);
                    text(c, hhmm.format(new Date(p.start)) + " – " + hhmm.format(new Date(p.end)), r3.left + 16, r3.top + 68, 22, Ui.regular, neu.muted, 0);
                    c.restore();
                }
                boolean first = onAir && !firstSet;
                if (first) firstSet = true;
                Target t = target("g:" + row + ":" + p.start, rowId, r3, 14, () -> pickProgram(ch, p), first);
                if (t != null && t.key.equals(focusKey) && guideSel != p) {
                    guideSel = p;
                    guideSelChannel = ch;
                    if (c != null) postInvalidate();
                }
            }
            if (!any) {
                r3.set(gx + 4, y + 8, gx + gw - 4, y + 88);
                text(c, "Sem informação de programação", r3.left + 20, r3.centerY() + 9, 24, Ui.regular, neu.muted, 0);
                target("g:" + row + ":x", rowId, r3, 14, () -> tuneChannel(ch), false);
            }
            if (c != null && nowX >= gx && nowX <= gx + gw) {
                fill.setColor(neu.accent);
                c.drawRect(nowX - 2, y, nowX + 2, y + 96, fill);
            }
            y += 96 + 12;
        }
        if (!guideFromTv) {
            text(c, "Grade nacional de cada rede: os programas locais das afiliadas de Curitiba podem variar.",
                    W - PAD, CONTENT_BOTTOM + 4, 22, Ui.regular, neu.muted, 2);
        }
    }

    private void pickProgram(Guide.Channel ch, Guide.Program p) {
        long now = System.currentTimeMillis();
        if (p.start <= now && now < p.end) {
            tuneChannel(ch);
            return;
        }
        boolean on = Reminders.toggle(getContext(), ch, p);
        if (on) allowOverlay();
        showToast(on ? "Lembrete às " + hhmm.format(new Date(p.start - 60_000L)) + ": " + p.title : "Lembrete removido");
    }

    /** The reminder's banner draws over other apps: allowed once through the TV's own ADB (Original takes it back). */
    private void allowOverlay() {
        if (Settings.canDrawOverlays(getContext())) return;
        final Context ctx = getContext().getApplicationContext();
        io.execute(() -> {
            try (Adb adb = Adb.connect(ctx, 30_000, null)) {
                adb.shell("appops set " + HomeMode.PKG + " SYSTEM_ALERT_WINDOW allow");
            } catch (Exception e) {
                AppLog.w("reminders: overlay not allowed: " + e.getMessage());
            }
        });
    }

    /**
     * Loads the guide: the TV's own, where it shares it (and we may read it), else the one from the internet. Without
     * `force` only when the copy in memory is older than 8 hours (or there is none).
     */
    private void loadGuide(boolean force) {
        if (guideLoading) return;
        if (!force && guide != null && System.currentTimeMillis() - guideAt < NetGuide.KEEP_MS && !guide.channels.isEmpty()) return;
        guideLoading = true;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.MINUTE, cal.get(Calendar.MINUTE) < 30 ? 0 : 30);
        final long from = cal.getTimeInMillis(), to = from + 24 * 3600_000L;
        final Context ctx = getContext().getApplicationContext();
        invalidate();
        io.execute(() -> {
            Guide.Result r = null;
            boolean tv = false;
            if (Guide.allowed(ctx)) {
                r = Guide.read(ctx, from, to);
                tv = r.programCount > 0;
            }
            if (!tv) r = NetGuide.read(ctx, from, to);
            final Guide.Result result = r;
            final boolean fromTv = tv;
            handler.post(() -> {
                guide = result;
                guideFromTv = fromTv;
                guideFrom = from;
                guideAt = System.currentTimeMillis();
                guideLoading = false;
                guideSel = null;
                if (screen == GUIDE) focusFirst = true;
                invalidate();
            });
        });
    }

    // MARK: Gerenciador

    private void tasksScreen(Canvas c) {
        r1.set(PAD, CONTENT_TOP, PAD + 520, CONTENT_BOTTOM);
        if (c != null) {
            neu.raised(c, r1, 32, Neu.RAISED, 0);
            text(c, "Memória em uso", r1.left + 40, r1.top + 60, 32, Ui.semibold, neu.text, 0);
            float used = mem == null || mem.total == 0 ? 0 : 1f - mem.available / (float) mem.total;
            ring(c, r1.centerX(), r1.top + 220, 120, 32, used, Math.round(used * 100) + "%", 52);
            text(c, "em uso", r1.centerX(), r1.top + 268, 24, Ui.regular, neu.muted, 1);
            text(c, mem == null ? "" : Tasks.size(mem.total - mem.available) + " de " + Tasks.size(mem.total), r1.centerX(), r1.top + 390, 28, Ui.semibold, neu.text, 1);
            text(c, mem == null ? "" : Tasks.size(mem.available) + " livres agora", r1.centerX(), r1.top + 428, 24, Ui.regular, neu.muted, 1);
            if (mem != null && mem.diskTotal > 0) {
                r2.set(r1.left + 40, r1.top + 462, r1.right - 40, r1.top + 594);
                neu.inset(c, r2, 22, neu.bg);
                text(c, "Armazenamento", r2.left + 24, r2.top + 40, 24, Ui.semibold, neu.text, 0);
                text(c, Tasks.size(mem.diskTotal - mem.diskFree) + " de " + Tasks.size(mem.diskTotal), r2.right - 24, r2.top + 40, 24, Ui.regular, neu.text, 2);
                bar(c, r2.left + 24, r2.top + 60, r2.width() - 48, 14, 1f - mem.diskFree / (float) mem.diskTotal);
                text(c, Tasks.size(mem.diskFree) + " livres", r2.left + 24, r2.top + 112, 24, Ui.regular, neu.muted, 0);
            }
            text(c, "Fecha só o que está em segundo plano", r1.centerX(), r1.bottom - 40, 24, Ui.regular, neu.muted, 1);
        }
        r2.set(r1.left + 40, r1.bottom - 160, r1.right - 40, r1.bottom - 84);
        if (c != null) {
            neu.raised(c, r2, 38, Neu.RAISED_SMALL, neu.accent);
            text(c, "Liberar memória", r2.centerX(), r2.centerY() + 10, 28, Ui.semibold, neu.onAccent, 1);
        }
        target("free", "L/free", r2, 38, this::askFreeMemory, true);
        // Open apps
        r1.set(PAD + 520 + 32, CONTENT_TOP, W - PAD, CONTENT_BOTTOM);
        if (c != null) {
            neu.raised(c, r1, 32, Neu.RAISED, 0);
            text(c, "Apps abertos", r1.left + 40, r1.top + 60, 32, Ui.semibold, neu.text, 0);
        }
        r2.set(r1.right - 40 - 210, r1.top + 22, r1.right - 40, r1.top + 80);
        if (c != null) {
            neu.raised(c, r2, 29, Neu.RAISED_SMALL, 0);
            text(c, tasksLoading ? "Lendo…" : "Atualizar", r2.centerX(), r2.centerY() + 9, 24, Ui.semibold, neu.text, 1);
        }
        target("refresh", "R/refresh", r2, 29, this::loadTasks, false);
        float y = r1.top + 104;
        if (taskApps == null) {
            String msg = tasksError != null ? tasksError : "Lendo os apps abertos…";
            for (String line : wrap(msg, r1.width() - 80, 26, Ui.regular, 4)) {
                text(c, line, r1.left + 40, y + 40, 26, Ui.regular, neu.muted, 0);
                y += 38;
            }
            return;
        }
        long max = 1;
        for (Tasks.App a : taskApps) max = Math.max(max, a.kb);
        int shown = 0;
        for (final Tasks.App a : taskApps) {
            if (shown == 8) break;
            r2.set(r1.left + 24, y, r1.right - 24, y + 88);
            if (c != null) {
                neu.inset(c, r2, 22, neu.bg);
                r3.set(r2.left + 16, r2.top + 14, r2.left + 76, r2.top + 74);
                neu.raised(c, r3, 30, Neu.RAISED_SMALL, 0);
                text(c, initials(a.name), r3.centerX(), r3.centerY() + 8, 22, Ui.semibold, neu.text, 1);
                float nameW = r2.width() - 92 - 380 - 210 - 48;
                text(c, ellipsize(a.name, nameW, 26, Ui.semibold), r2.left + 96, r2.top + 40, 26, Ui.semibold, neu.text, 0);
                text(c, a.system ? "Necessário para a TV" : a.screen ? "Com tela aberta" : "Em segundo plano",
                        r2.left + 96, r2.top + 72, 22, Ui.regular, a.screen && !a.system ? neu.text : neu.muted, 0);
                float bx = r2.right - 210 - 24 - 380;
                bar(c, bx, r2.centerY() - 7, 250, 14, a.kb / (float) max);
                text(c, Math.round(a.kb / 1024f) + " MB", bx + 370, r2.centerY() + 9, 24, Ui.semibold, neu.text, 2);
            }
            r3.set(r2.right - 16 - 200, r2.top + 14, r2.right - 16, r2.bottom - 14);
            if (a.system) {
                if (c != null) {
                    lock(c, r3.left + 44, r3.centerY(), neu.muted);
                    text(c, "Protegido", r3.left + 70, r3.centerY() + 9, 24, Ui.regular, neu.muted, 0);
                }
            } else {
                if (c != null) {
                    neu.raised(c, r3, 30, Neu.RAISED_SMALL, 0);
                    text(c, "Encerrar", r3.centerX(), r3.centerY() + 9, 24, Ui.semibold, neu.danger, 1);
                }
                target("end:" + a.pkg, "R/" + shown, r3, 30, () -> askStop(a), false);
            }
            y += 88 + 12;
            shown++;
        }
    }

    private void loadTasks() {
        if (tasksLoading) return;
        tasksLoading = true;
        tasksError = null;
        mem = Tasks.memory(getContext());
        final Context ctx = getContext().getApplicationContext();
        invalidate();
        io.execute(() -> {
            List<Tasks.App> list = null;
            String error = null;
            try {
                list = Tasks.apps(ctx);
            } catch (Exception e) {
                AppLog.w("tasks: " + e.getMessage());
                error = "Não consegui ler os apps abertos. Se a TV perguntar “Permitir depuração USB?”, marque “Sempre permitir” e aceite. Depois, OK em Atualizar.";
            }
            final List<Tasks.App> l = list;
            final String err = error;
            handler.post(() -> {
                tasksLoading = false;
                taskApps = l;
                tasksError = err;
                mem = Tasks.memory(getContext());
                invalidate();
            });
        });
    }

    private void askStop(Tasks.App a) {
        confirm("Encerrar " + a.name + "?",
                a.screen ? "Ele está com a tela aberta: o que está passando ou tocando vai parar."
                        : "Ele fecha agora e abre normalmente da próxima vez que você escolher.",
                "Encerrar", () -> {
                    final Context ctx = getContext().getApplicationContext();
                    final long before = Tasks.memory(ctx).available;
                    io.execute(() -> {
                        String done;
                        try {
                            Tasks.stop(ctx, a.pkg);
                            Thread.sleep(800);
                            long gained = Tasks.memory(ctx).available - before;
                            done = a.name + " encerrado" + (gained > 1_048_576 ? " · " + Tasks.size(gained) + " liberados" : "");
                        } catch (Exception e) {
                            done = "Não consegui encerrar " + a.name;
                        }
                        final String msg = done;
                        handler.post(() -> { showToast(msg); loadTasks(); });
                    });
                });
    }

    private void askFreeMemory() {
        confirm("Liberar memória?",
                "Fecha os apps em segundo plano. O que você está vendo ou ouvindo continua aberto.",
                "Liberar", () -> freeMemory(true));
    }

    private void freeMemory(final boolean asked) {
        final Context ctx = getContext().getApplicationContext();
        final long before = Tasks.memory(ctx).available;
        autoFreeAt = System.currentTimeMillis();
        io.execute(() -> {
            String done;
            try {
                Tasks.freeMemory(ctx);
                Thread.sleep(1200);
                long gained = Tasks.memory(ctx).available - before;
                done = gained > 1_048_576 ? "Memória liberada: " + Tasks.size(gained) : asked ? "Nada a liberar agora" : null;
            } catch (Exception e) {
                done = asked ? "Não consegui liberar: aceite a depuração na TV" : null;
            }
            final String msg = done;
            handler.post(() -> {
                if (msg != null) showToast(msg);
                mem = Tasks.memory(getContext());
                if (asked) loadTasks();
                invalidate();
            });
        });
    }

    /** Back on the home screen with little memory free (and the option on): closes what runs in the background. */
    private void maybeFreeMemory() {
        if (!autoFree || mem == null || mem.total == 0) return;
        long now = System.currentTimeMillis();
        if (now - autoFreeAt < AUTO_FREE_EVERY && now >= autoFreeAt) return;
        if (mem.available / (double) mem.total > 0.15) return;
        freeMemory(false);
    }

    // MARK: Ajustes

    private void settingsScreen(Canvas c) {
        r1.set(PAD, CONTENT_TOP, PAD + 400, CONTENT_BOTTOM);
        if (c != null) neu.raised(c, r1, 32, Neu.RAISED, 0);
        String[] names = {"Aparência", "Tela inicial", "TV"};
        for (int i = 0; i < 3; i++) {
            r2.set(r1.left + 28, r1.top + 28 + i * 96, r1.right - 28, r1.top + 108 + i * 96);
            if (c != null) {
                neu.raised(c, r2, 22, Neu.RAISED_SMALL, sub == i ? neu.accent : 0);
                text(c, names[i], r2.left + 28, r2.centerY() + 10, 28, Ui.semibold, sub == i ? neu.onAccent : neu.text, 0);
            }
            target("sub" + i, "N/" + i, r2, 22, () -> move(1, 0), sub == i);
        }
        r2.set(r1.left + 28, r1.bottom - 220, r1.right - 28, r1.bottom - 28);
        if (c != null) {
            neu.inset(c, r2, 22, neu.bg);
            float y = r2.top + 40;
            for (String l : wrap("Rede, contas, atualizações e o resto ficam no menu da TV", r2.width() - 40, 24, Ui.regular, 3)) {
                text(c, l, r2.left + 20, y, 24, Ui.regular, neu.muted, 0);
                y += 32;
            }
        }
        r3.set(r2.left + 20, r2.bottom - 88, r2.right - 20, r2.bottom - 20);
        if (c != null) {
            neu.raised(c, r3, 34, Neu.RAISED_SMALL, 0);
            text(c, "Configurações da TV", r3.centerX(), r3.centerY() + 9, 24, Ui.semibold, neu.text, 1);
        }
        target("native", "N/native", r3, 34, this::openTvSettings, false);

        r1.set(PAD + 400 + 32, CONTENT_TOP, W - PAD, CONTENT_BOTTOM);
        if (c != null) neu.raised(c, r1, 32, Neu.RAISED, 0);
        float x = r1.left + 40, w = r1.width() - 80, y = r1.top + 56;
        if (sub == 0) {
            text(c, "Tema", x, y, 28, Ui.semibold, neu.text, 0);
            y += 24;
            String[][] themes = {{"claro", "Claro", "sempre claro"}, {"escuro", "Escuro", "sempre escuro"}, {"auto", "Automático", "escuro à noite"}};
            float tw = (w - 48) / 3f;
            for (int i = 0; i < 3; i++) {
                final String id = themes[i][0];
                boolean on = id.equals(theme);
                r2.set(x + i * (tw + 24), y, x + i * (tw + 24) + tw, y + 96);
                if (c != null) {
                    neu.raised(c, r2, 24, Neu.RAISED_SMALL, on ? neu.accent : 0);
                    text(c, themes[i][1], r2.centerX(), r2.top + 44, 26, Ui.semibold, on ? neu.onAccent : neu.text, 1);
                    text(c, themes[i][2], r2.centerX(), r2.top + 76, 22, Ui.regular, on ? neu.onAccent : neu.muted, 1);
                }
                target("theme:" + id, "C/theme", r2, 24, () -> setTheme(id), false);
            }
            y += 96 + 52;
            text(c, "Cor de destaque", x, y, 28, Ui.semibold, neu.text, 0);
            y += 24;
            float sw = (w - 5 * 20) / 6f;
            for (int i = 0; i < Neu.ACCENTS.length; i++) {
                final int col = Neu.ACCENTS[i];
                boolean on = col == accent;
                int row = i / 6, cl = i % 6;
                r2.set(x + cl * (sw + 20), y + row * 124, x + cl * (sw + 20) + sw, y + row * 124 + 104);
                if (c != null) {
                    if (on) neu.inset(c, r2, 22, neu.bg); else neu.raised(c, r2, 22, Neu.RAISED_SMALL, 0);
                    fill.setColor(col);
                    c.drawCircle(r2.centerX(), r2.top + 38, 24, fill);
                    if (on) check(c, r2.centerX(), r2.top + 38, 13, neu.onAccent);
                    text(c, Neu.ACCENT_NAMES[i], r2.centerX(), r2.bottom - 16, 22, on ? Ui.semibold : Ui.regular, on ? neu.text : neu.muted, 1);
                }
                target("accent:" + i, "C/sw" + row, r2, 22, () -> setAccent(col), false);
            }
            y += 2 * 124 + 36;
            text(c, "Prévia", x, y, 28, Ui.semibold, neu.text, 0);
            if (c != null) preview(c, x, y + 20, w);
        } else if (sub == 1) {
            text(c, "O que aparece no Início", x, y, 28, Ui.semibold, neu.text, 0);
            y += 24;
            y = toggle(c, x, y, w, "Previsão do tempo", "weather", showWeather);
            y = toggle(c, x, y, w, "Memória e armazenamento", "system", showSystem);
            y = toggle(c, x, y, w, "Últimos lançamentos", "releases", showReleases);
            y = toggle(c, x, y, w, "Liberar memória sozinho ao voltar ao Início (quando falta)", "autofree", autoFree);
            y = actionRow(c, x, y, w, "Apps ocultos", hidden.isEmpty() ? "Nenhum app oculto em Todos os apps" : hidden.size() + " oculto(s) em Todos os apps",
                    "Escolher", false, () -> openAllApps(true));
            y = actionRow(c, x, y, w, "Chave do TMDB, cidade e outros ajustes", "Abre os ajustes do Free My TV", "Abrir", false, this::openFreeMyTvSettings);
            actionRow(c, x, y, w, "Tela original da TCL", "Desliga esta tela e devolve a de fábrica. Nada é apagado.", "Voltar à original", true, this::openFreeMyTvSettings);
        } else {
            text(c, "Ajustes rápidos", x, y, 28, Ui.semibold, neu.text, 0);
            y += 24;
            int left = SleepTimer.minutesLeft(getContext());
            y = actionRow(c, x, y, w, "Desligar sozinha", left == 0 ? "Desligado  ·  OK escolhe 30 min, 1 h ou 2 h" : "Desliga em " + SleepTimer.duration(left),
                    left == 0 ? "Ligar" : "Mudar", false, this::stepSleep);
            final Intent picture = TvPages.picture(getContext()), sound = TvPages.sound(getContext());
            if (picture != null) y = actionRow(c, x, y, w, "Imagem", "Modo de imagem, brilho e cores, na tela da própria TV", "Abrir", false, () -> openIntent(picture));
            if (sound != null) y = actionRow(c, x, y, w, "Som", "Modo de som e saída de áudio, na tela da própria TV", "Abrir", false, () -> openIntent(sound));
            actionRow(c, x, y, w, "Todas as configurações da TV", "Rede, contas, entradas e atualizações", "Abrir", false, this::openTvSettings);
        }
    }

    private float toggle(Canvas c, float x, float y, float w, String label, final String key, final boolean on) {
        r2.set(x, y, x + w, y + 96);
        if (c != null) {
            neu.inset(c, r2, 22, neu.bg);
            text(c, ellipsize(label, w - 240, 26, Ui.semibold), r2.left + 28, r2.centerY() + 10, 26, Ui.semibold, neu.text, 0);
        }
        r3.set(r2.right - 24 - 156, r2.centerY() - 32, r2.right - 24, r2.centerY() + 32);
        if (c != null) {
            if (on) neu.raised(c, r3, 32, Neu.RAISED_SMALL, neu.accent); else neu.inset(c, r3, 32, 0);
            float kx = on ? r3.right - 32 : r3.left + 32;
            fill.setColor(neu.bg);
            c.drawCircle(kx, r3.centerY(), 26, fill);
            text(c, on ? "LIG" : "DESL", on ? r3.left + 24 : r3.right - 24, r3.centerY() + 9, 24, Ui.semibold, on ? neu.onAccent : neu.muted, on ? 0 : 2);
        }
        target("tg:" + key, "C/tg:" + key, r3, 32, () -> setShow(key, !on), false);
        return y + 96 + 14;
    }

    private float actionRow(Canvas c, float x, float y, float w, String title, String body, String button, boolean danger, Runnable run) {
        r2.set(x, y, x + w, y + 104);
        tp.setTypeface(Ui.semibold);
        tp.setTextSize(24);
        float bw = tp.measureText(button) + 64;
        if (c != null) {
            neu.inset(c, r2, 22, neu.bg);
            text(c, title, r2.left + 28, r2.top + 42, 26, Ui.semibold, neu.text, 0);
            text(c, ellipsize(body, w - bw - 80, 24, Ui.regular), r2.left + 28, r2.top + 78, 24, Ui.regular, neu.muted, 0);
        }
        r3.set(r2.right - 24 - bw, r2.centerY() - 32, r2.right - 24, r2.centerY() + 32);
        if (c != null) {
            neu.raised(c, r3, 32, Neu.RAISED_SMALL, danger ? 0 : neu.accent);
            text(c, button, r3.centerX(), r3.centerY() + 9, 24, Ui.semibold, danger ? neu.danger : neu.onAccent, 1);
        }
        target("act:" + title, "C/act:" + title, r3, 32, run, false);
        return y + 104 + 14;
    }

    private void preview(Canvas c, float x, float y, float w) {
        r2.set(x, y, x + w, y + 128);
        neu.inset(c, r2, 24, neu.bg);
        float cy = r2.centerY();
        r3.set(x + 28, cy - 32, x + 168, cy + 32);
        neu.raised(c, r3, 32, Neu.RAISED_SMALL, neu.accent);
        text(c, "SIM", r3.centerX(), cy + 9, 24, Ui.semibold, neu.onAccent, 1);
        r3.set(x + 196, cy - 32, x + 336, cy + 32);
        neu.raised(c, r3, 32, Neu.RAISED_SMALL, 0);
        text(c, "NÃO", r3.centerX(), cy + 9, 24, Ui.semibold, neu.text, 1);
        r3.set(x + 380, cy - 7, x + 680, cy + 7);
        neu.inset(c, r3, 7, 0);
        fill.setColor(neu.accent);
        r3.right = r3.left + 180;
        c.drawRoundRect(r3, 7, 7, fill);
        r3.set(x + 540, cy - 20, x + 580, cy + 20);
        neu.raised(c, r3, 20, Neu.RAISED_SMALL, 0);
        ring(c, x + 760, cy, 46, 14, 0.25f, "25%", 22);
    }

    /** Off → 30 min → 1 h → 2 h → off: the next step after what's left. */
    private void stepSleep() {
        int left = SleepTimer.minutesLeft(getContext()), next = 0;
        for (int s : SLEEP_STEPS) if (s > left) { next = s; break; }
        SleepTimer.set(getContext(), next);
        showToast(next == 0 ? "Desligar sozinha: desligado" : "A TV desliga em " + SleepTimer.duration(next));
    }

    // MARK: Todos os apps

    private void allAppsScreen(Canvas c) {
        text(c, editHidden ? "Apps ocultos: OK esconde ou mostra" : "Todos os apps", PAD, CONTENT_TOP + 30, 32, Ui.semibold, neu.text, 0);
        if (editHidden) {
            tp.setTypeface(Ui.semibold);
            tp.setTextSize(24);
            float bw = tp.measureText("Pronto") + 80;
            r1.set(W - PAD - bw, CONTENT_TOP - 4, W - PAD, CONTENT_TOP + 56);
            if (c != null) {
                neu.raised(c, r1, 30, Neu.RAISED_SMALL, neu.accent);
                text(c, "Pronto", r1.centerX(), r1.centerY() + 9, 24, Ui.semibold, neu.onAccent, 1);
            }
            target("done", "aa-top", r1, 30, () -> { editHidden = false; openScreen(SETTINGS); }, false);
        }
        List<String[]> list = visibleApps();
        if (list.isEmpty()) {
            text(c, allApps.isEmpty() ? "Carregando…" : "Todos os apps estão ocultos", PAD, CONTENT_TOP + 110, 26, Ui.regular, neu.muted, 0);
            return;
        }
        float cw = (W - 2 * PAD - (APP_COLS - 1) * 24) / APP_COLS, ch = 172, top = CONTENT_TOP + 80;
        allMaxTop = Math.max(0, (list.size() + APP_COLS - 1) / APP_COLS - APP_ROWS);
        allTop = Math.max(0, Math.min(allTop, allMaxTop));
        for (int i = allTop * APP_COLS; i < Math.min(list.size(), (allTop + APP_ROWS) * APP_COLS); i++) {
            final String[] app = list.get(i);
            int row = i / APP_COLS - allTop, col = i % APP_COLS;
            boolean off = hidden.contains(app[0]);
            r1.set(PAD + col * (cw + 24), top + row * (ch + 24), PAD + col * (cw + 24) + cw, top + row * (ch + 24) + ch);
            if (c != null) {
                if (off) neu.inset(c, r1, 26, 0); else neu.raised(c, r1, 26, Neu.RAISED_SMALL, 0);
                Drawable d = icons.get(app[0]);
                if (d != null) {
                    d.setBounds((int) (r1.centerX() - 40), (int) (r1.top + 22), (int) (r1.centerX() + 40), (int) (r1.top + 102));
                    d.setAlpha(off ? 90 : 255);
                    d.draw(c);
                }
                text(c, ellipsize(off ? "Oculto · " + app[1] : app[1], cw - 24, 24, Ui.semibold), r1.centerX(), r1.bottom - 24, 24,
                        Ui.semibold, off ? neu.muted : neu.text, 1);
            }
            target("a:" + app[0], "aa" + (i / APP_COLS), r1, 26, () -> {
                if (editHidden) toggleHidden(app[0]);
                else openItem(HomeApps.app(app[0], app[1]));
            }, i == allTop * APP_COLS);
        }
    }

    private List<String[]> visibleApps() {
        if (editHidden) return allApps;
        List<String[]> out = new ArrayList<>();
        for (String[] a : allApps) if (!hidden.contains(a[0])) out.add(a);
        return out;
    }

    private void toggleHidden(String pkg) {
        if (!hidden.remove(pkg)) hidden.add(pkg);
        prefs.edit().putStringSet("hidden", new HashSet<>(hidden)).apply();
        invalidate();
    }

    private void openAllApps(boolean edit) {
        editHidden = edit;
        allTop = 0;
        openScreen(ALL_APPS);
        final Context ctx = getContext().getApplicationContext();
        io.execute(() -> {
            final List<String[]> list = HomeApps.launchable(ctx);
            Collections.sort(list, (a, b) -> a[1].compareToIgnoreCase(b[1]));
            final Map<String, Drawable> found = new HashMap<>();
            PackageManager pm = ctx.getPackageManager();
            for (String[] a : list) {
                try {
                    found.put(a[0], pm.getApplicationIcon(a[0]));
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            handler.post(() -> {
                allApps = list;
                icons.clear();
                icons.putAll(found);
                if (screen == ALL_APPS) focusFirst = true;
                invalidate();
            });
        });
    }

    // MARK: Volume

    private void openVolume() {
        if (audio == null) return;
        volumeMax = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        volumeOpen = true;
        focusBeforeModal = focusKey;
        focusKey = "v:up";
        armVolume();
        invalidate();
    }

    private final Runnable closeVolume = () -> {
        if (!volumeOpen) return;
        volumeOpen = false;
        focusKey = focusBeforeModal;
        invalidate();
    };

    private void armVolume() {
        handler.removeCallbacks(closeVolume);
        handler.postDelayed(closeVolume, VOLUME_CLOSE_MS);
    }

    private void stepVolume(int dir) {
        if (audio == null) return;
        int step = Math.max(1, Math.round(volumeMax / 50f));   // TCL's 100 steps: 2 a press, as the remote does
        volume = Math.max(0, Math.min(volumeMax, audio.getStreamVolume(AudioManager.STREAM_MUSIC) + dir * step));
        if (muted) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            muted = false;
        }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
        armVolume();
        invalidate();
    }

    private void toggleMute() {
        if (audio == null) return;
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, muted ? AudioManager.ADJUST_UNMUTE : AudioManager.ADJUST_MUTE, 0);
        muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        armVolume();
        invalidate();
    }

    private void volumePanel(Canvas c) {
        inModal = true;
        r1.set(W - PAD - 260, 200, W - PAD, 980);
        float cx = r1.centerX();
        if (c != null) {
            neu.raised(c, r1, 44, Neu.RAISED, 0);
            speaker(c, cx - 50, r1.top + 54, neu.muted, muted);
            text(c, "Volume", cx - 22, r1.top + 63, 24, Ui.semibold, neu.muted, 0);
            text(c, muted ? "Mudo" : String.valueOf(Math.round(volume * 100f / volumeMax)), cx, r1.top + 140, 56, Ui.displaySemibold, neu.text, 1);
        }
        r2.set(cx - 49, r1.top + 172, cx + 49, r1.top + 240);
        if (c != null) {
            neu.raised(c, r2, 22, Neu.RAISED_SMALL, 0);
            text(c, "+", r2.centerX(), r2.centerY() + 14, 40, Ui.semibold, neu.text, 1);
        }
        target("v:up", "v/up", r2, 22, () -> stepVolume(1), true);
        // The level, on a vertical rail
        float railTop = r1.top + 262, railBottom = r1.top + 492;
        if (c != null) {
            r3.set(cx - 14, railTop, cx + 14, railBottom);
            neu.inset(c, r3, 14, 0);
            float v = muted ? 0 : volume / (float) volumeMax;
            r3.top = railBottom - Math.max(28, (railBottom - railTop) * v);
            fill.setColor(muted ? neu.muted : neu.accent);
            c.drawRoundRect(r3, 14, 14, fill);
            r4.set(cx - 24, r3.top - 10, cx + 24, r3.top + 38);
            neu.raised(c, r4, 24, Neu.RAISED_SMALL, 0);
        }
        r2.set(cx - 49, railBottom + 22, cx + 49, railBottom + 90);
        if (c != null) {
            neu.raised(c, r2, 22, Neu.RAISED_SMALL, 0);
            text(c, "−", r2.centerX(), r2.centerY() + 14, 40, Ui.semibold, neu.text, 1);
        }
        target("v:down", "v/down", r2, 22, () -> stepVolume(-1), false);
        r2.set(r1.left + 28, railBottom + 112, r1.right - 28, railBottom + 176);
        if (c != null) {
            neu.raised(c, r2, 32, Neu.RAISED_SMALL, muted ? neu.accent : 0);
            text(c, muted ? "Tirar do mudo" : "Sem som", r2.centerX(), r2.centerY() + 9, 24, Ui.semibold, muted ? neu.onAccent : neu.text, 1);
        }
        target("v:mute", "v/mute", r2, 32, this::toggleMute, false);
        r2.set(r1.left + 28, railBottom + 196, r1.right - 28, railBottom + 260);
        if (c != null) {
            neu.raised(c, r2, 32, Neu.RAISED_SMALL, 0);
            text(c, "Som da TV", r2.centerX(), r2.centerY() + 9, 24, Ui.semibold, neu.text, 1);
        }
        target("v:tv", "v/tv", r2, 32, () -> {
            closeVolume.run();
            Intent sound = TvPages.sound(getContext());
            if (sound != null) openIntent(sound); else openTvSettings();
        }, false);
    }

    // MARK: Confirm dialog

    private void confirm(String title, String body, String ok, Runnable run) {
        cTitle = title;
        cBody = body;
        cOk = ok;
        cRun = run;
        focusBeforeModal = focusKey;
        focusKey = "c:cancel";
        invalidate();
    }

    private void closeConfirm() {
        boolean was = cTitle != null;
        cTitle = cBody = cOk = null;
        cRun = null;
        if (was) focusKey = focusBeforeModal;
    }

    private void confirmDialog(Canvas c) {
        inModal = true;
        if (c != null) {
            fill.setColor(0x8C0A0C10);
            c.drawRect(0, 0, W, 1080, fill);
        }
        String[] lines = wrap(cBody, 740, 26, Ui.regular, 3);
        float h = 48 + 50 + lines.length * 38 + 40 + 72 + 48;
        r1.set(W / 2 - 410, 540 - h / 2, W / 2 + 410, 540 + h / 2);
        float y = r1.top + 48 + 36;
        if (c != null) {
            neu.raised(c, r1, 36, Neu.RAISED, 0);
            text(c, ellipsize(cTitle, 724, 36, Ui.semibold), r1.left + 48, y, 36, Ui.semibold, neu.text, 0);
        }
        y += 18;
        for (String l : lines) {
            y += 38;
            text(c, l, r1.left + 48, y, 26, Ui.regular, neu.muted, 0);
        }
        tp.setTypeface(Ui.semibold);
        tp.setTextSize(26);
        float okW = tp.measureText(cOk) + 80, cancelW = tp.measureText("Cancelar") + 80;
        r2.set(r1.right - 48 - okW, r1.bottom - 48 - 72, r1.right - 48, r1.bottom - 48);
        r3.set(r2.left - 24 - cancelW, r2.top, r2.left - 24, r2.bottom);
        if (c != null) {
            neu.raised(c, r3, 36, Neu.RAISED_SMALL, 0);
            text(c, "Cancelar", r3.centerX(), r3.centerY() + 9, 26, Ui.semibold, neu.text, 1);
            neu.raised(c, r2, 36, Neu.RAISED_SMALL, neu.danger);
            text(c, cOk, r2.centerX(), r2.centerY() + 9, 26, Ui.semibold, neu.onDanger, 1);
        }
        target("c:cancel", "c/buttons", r3, 36, () -> { closeConfirm(); invalidate(); }, true);
        final Runnable run = cRun;
        target("c:ok", "c/buttons", r2, 36, () -> { closeConfirm(); if (run != null) run.run(); invalidate(); }, false);
    }

    private void showToast(String text) {
        toast = text;
        handler.removeCallbacks(hideToast);
        handler.postDelayed(hideToast, 2600);
        invalidate();
    }

    private final Runnable hideToast = () -> { toast = null; invalidate(); };

    // MARK: - Actions

    private void openTab(int tab) {
        if (screen == tab) return;
        openScreen(tab);
        if (tab == GUIDE) loadGuide(false);
        if (tab == TASKS) loadTasks();
    }

    private void openScreen(int s) {
        screen = s;
        String back = screenMemory.get(s);
        if (back != null) {
            focusKey = back;
            focusFirst = false;
        } else {
            focusFirst = true;
        }
        rowMemory.put("hdr", "tab" + (s == ALL_APPS || s == WEATHER ? HOME : s));
        invalidate();
    }

    private void openItem(HomeApps.Item it) {
        click.open();
        if (it.kind == HomeApps.APP && it.pkg != null) {
            prefs.edit().putInt("use:" + it.pkg, prefs.getInt("use:" + it.pkg, 0) + 1).apply();
        }
        if (!HomeApps.open(getContext(), it, null)) showToast("Não consegui abrir " + it.label);
    }

    private void openIntent(Intent intent) {
        click.open();
        try {
            getContext().startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't open " + intent + ": " + e.getMessage());
            showToast("A TV não deixou abrir essa tela");
        }
    }

    private void openPick(Recs.Pick p) {
        if (p.pkg == null) return;
        openItem(HomeApps.app(p.pkg, Recs.serviceName(p), p.link));
    }

    private void openLiveTv() {
        HomeApps.Item it = new HomeApps.Item();
        it.kind = HomeApps.LIVE_TV;
        it.label = "TV ao vivo";
        openItem(it);
    }

    /**
     * A channel: the TV's own entry when the guide came from the TV; otherwise live TV opens and the channel's number
     * is typed on the remote's digits through the TV's own ADB (12.1 → "12": the TV tunes the first of its channels).
     */
    private void tuneChannel(Guide.Channel ch) {
        prefs.edit().putString("lastCh", ch.number).apply();
        if (ch.id >= 0) {
            click.open();
            try {
                getContext().startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(ch.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("guide: couldn't open channel " + ch.number + ": " + e.getMessage());
            }
        }
        openLiveTv();
        final String major = ch.number.contains(".") ? ch.number.substring(0, ch.number.indexOf('.')) : ch.number;
        final Context ctx = getContext().getApplicationContext();
        io.execute(() -> {
            try {
                Thread.sleep(2500);   // the TV app opening on its last channel
                StringBuilder keys = new StringBuilder("input keyevent");
                for (char d : major.toCharArray()) if (Character.isDigit(d)) keys.append(' ').append(7 + (d - '0'));
                try (Adb adb = Adb.connect(ctx, 30_000, null)) {
                    adb.shell(keys.toString());
                }
            } catch (Exception e) {
                AppLog.w("guide: couldn't tune " + ch.number + ": " + e.getMessage());
            }
        });
    }

    private void openTvSettings() {
        openIntent(new Intent(Settings.ACTION_SETTINGS));
    }

    private void openFreeMyTvSettings() {
        openIntent(new Intent().setComponent(new ComponentName(getContext(), SettingsActivity.class)));
    }

    private void setTheme(String id) {
        theme = id;
        prefs.edit().putString("theme", id).apply();
        updateTheme();
        invalidate();
    }

    private void setAccent(int color) {
        accent = color;
        prefs.edit().putInt("accent", color).apply();
        updateTheme();
        invalidate();
    }

    private void setShow(String key, boolean on) {
        prefs.edit().putBoolean(key, on).apply();
        if ("weather".equals(key)) showWeather = on;
        if ("system".equals(key)) showSystem = on;
        if ("releases".equals(key)) showReleases = on;
        if ("autofree".equals(key)) autoFree = on;
        invalidate();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_UP) {
            return code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || isOk(code) || isArrow(code);
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (volumeOpen) armVolume();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (e.getRepeatCount() > 0) return true;
            if (cTitle != null) closeConfirm();
            else if (volumeOpen) closeVolume.run();
            else if (screen == ALL_APPS && editHidden) { editHidden = false; openScreen(SETTINGS); }
            else if (screen == ALL_APPS) { openScreen(HOME); focusKey = "allapps"; focusFirst = false; }
            else if (screen == WEATHER) { openScreen(HOME); focusKey = "wx"; focusFirst = false; }
            else if (screen != HOME) openScreen(HOME);
            invalidate();
            return true;
        }
        if (isArrow(code)) {
            // One press, one step: a held key moves at a steady pace, and a second press of the same key within
            // 50 ms of the first (a remote that sends a press twice) is dropped.
            long now = SystemClock.uptimeMillis();
            long gap = now - lastKeyAt;
            if (e.getRepeatCount() > 0 ? gap < 160 : code == lastKey && gap < 50) return true;
            lastKey = code;
            lastKeyAt = now;
            // Volume panel: ← → change the volume wherever the focus is
            if (volumeOpen && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                stepVolume(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); break;
                default: move(1, 0); break;
            }
            return true;
        }
        if (isOk(code)) {
            Target t = focused();
            if (e.getRepeatCount() > 0) {
                // Held OK on + or −: the volume keeps going
                if (volumeOpen && t != null && t.action != null && ("v:up".equals(t.key) || "v:down".equals(t.key))) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastKeyAt >= 160) { lastKeyAt = now; t.action.run(); }
                }
                return true;
            }
            if (t != null && t.action != null) t.action.run();
            invalidate();
            return true;
        }
        return false;
    }

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private static boolean isArrow(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private Target focused() {
        return byKey(focusKey);
    }

    private Target byKey(String key) {
        if (key == null) return null;
        for (int i = 0; i < count; i++) if (key.equals(pool.get(i).key)) return pool.get(i);
        return null;
    }

    private void setFocus(Target t) {
        focusKey = t.key;
        rowMemory.put(t.row, t.key);
        groupMemory.put(t.group, t.key);
        if (!modal && !"hdr".equals(t.group)) screenMemory.put(screen, t.key);
        if (screen == SETTINGS && t.key.startsWith("sub") && sub != t.key.charAt(3) - '0') {
            sub = t.key.charAt(3) - '0';   // the menu shows its page as the focus passes
        }
    }

    private void move(int dx, int dy) {
        Target cur = focused();
        if (cur == null) {
            focusFirst = true;
            invalidate();
            return;
        }
        Target next = dx != 0 ? sideways(cur, dx) : vertical(cur, dy);
        if (next == null) return;
        setFocus(next);
        click.move();
        invalidate();
    }

    /** ← →: the neighbor in the row; at its end, the nearest item beside it in another row (a side menu, a panel). */
    private Target sideways(Target cur, int dx) {
        Target best = null;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            Target t = pool.get(i);
            if (t == cur || !t.row.equals(cur.row)) continue;
            float d = (t.r.centerX() - cur.r.centerX()) * dx;
            if (d > 4 && d < bestD) { bestD = d; best = t; }
        }
        if (best != null) return best;
        // Beside: overlapping our height first, nearest edge; then anything ahead. A group's last item wins.
        for (int pass = 0; pass < 2 && best == null; pass++) {
            for (int i = 0; i < count; i++) {
                Target t = pool.get(i);
                if (t.row.equals(cur.row) || "hdr".equals(t.group) != "hdr".equals(cur.group) || !ahead(cur, t, dx)) continue;
                if (pass == 0 && overlap(cur.r.top, cur.r.bottom, t.r.top, t.r.bottom) <= 0) continue;
                float d = (dx > 0 ? t.r.left - cur.r.right : cur.r.left - t.r.right) + Math.abs(t.r.centerY() - cur.r.centerY()) * 0.5f;
                if (d < bestD) { bestD = d; best = t; }
            }
        }
        if (best != null && !best.group.equals(cur.group)) {
            Target remembered = byKey(groupMemory.get(best.group));
            if (remembered != null && remembered.group.equals(best.group) && ahead(cur, remembered, dx)) return remembered;
        }
        return best;
    }

    private static boolean ahead(Target cur, Target t, int dx) {
        return dx > 0 ? t.r.left >= cur.r.right - 8 : t.r.right <= cur.r.left + 8;
    }

    /** ↑ ↓: the next row in the same group (the one under first), else the header; the row's last item, else the one under. */
    private Target vertical(Target cur, int dy) {
        if (dy > 0 && "hdr".equals(cur.group)) {
            Target back = byKey(screenMemory.get(screen));
            if (back != null) return back;
            for (int i = 0; i < count; i++) if (pool.get(i).first) return pool.get(i);
            for (int i = 0; i < count; i++) if (!"hdr".equals(pool.get(i).group)) return pool.get(i);
            return null;
        }
        String row = nearestRow(cur, dy, true, true);
        if (row == null && scroll(cur, dy)) return null;
        if (row == null) row = nearestRow(cur, dy, true, false);
        if (row == null && !modal) row = nearestRow(cur, dy, false, true);
        if (row == null && dy < 0 && !modal) row = "hdr";
        if (row == null) return null;
        // The row's last item, except in grids (the guide, all the apps), where the column wins
        Target remembered = byKey(rowMemory.get(row));
        if (remembered != null && remembered.row.equals(row) && !scrolls(remembered)) return remembered;
        // The one under: coming from something wider (a big card), the first it covers; else the most covered
        Target best = null;
        float bestScore = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            Target t = pool.get(i);
            if (!t.row.equals(row)) continue;
            float o = overlap(cur.r.left, cur.r.right, t.r.left, t.r.right);
            float score;
            if (o > 0) score = cur.r.width() > t.r.width() * 1.5f ? 100_000 - t.r.left : 100_000 + o;
            else score = -Math.abs(t.r.centerX() - cur.r.centerX());
            if (score > bestScore) { bestScore = score; best = t; }
        }
        return best;
    }

    /** The nearest row above (dy < 0) or below: in our group or another, overlapping our width or not. */
    private String nearestRow(Target cur, int dy, boolean sameGroup, boolean overlapping) {
        String best = null;
        float bestGap = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            Target t = pool.get(i);
            if (t.row.equals(cur.row) || "hdr".equals(t.group)) continue;
            if (sameGroup != t.group.equals(cur.group)) continue;
            boolean ahead = dy > 0 ? t.r.top >= cur.r.bottom - 8 : t.r.bottom <= cur.r.top + 8;
            if (!ahead) continue;
            if (overlapping && overlap(cur.r.left, cur.r.right, t.r.left, t.r.right) <= 0) continue;
            float gap = dy > 0 ? t.r.top - cur.r.bottom : cur.r.top - t.r.bottom;
            if (gap < bestGap) { bestGap = gap; best = t.row; }
        }
        return best;
    }

    /** At the edge of a list that scrolls (the guide's channels, all the apps): the next one comes in. */
    private boolean scroll(Target cur, int dy) {
        if (!scrolls(cur)) return false;
        if (screen == GUIDE && guide != null) {
            int next = guideTop + dy;
            if (next < 0 || next > guide.channels.size() - GUIDE_ROWS) return false;
            guideTop = next;
        } else if (screen == ALL_APPS) {
            int next = allTop + dy;
            if (next < 0 || next > allMaxTop) return false;
            allTop = next;
        } else {
            return false;
        }
        pendingX = cur.r.centerX();
        pendingDir = dy;
        click.move();
        invalidate();
        return true;
    }

    private static float overlap(float a0, float a1, float b0, float b1) {
        return Math.min(a1, b1) - Math.max(a0, b0);
    }

    // MARK: - Focus targets

    private Target target(String key, String row, RectF r, float radius, Runnable action, boolean first) {
        if (modal && !inModal) return null;   // a dialog or the volume is open: only its buttons take the focus
        Target t;
        if (count < pool.size()) t = pool.get(count);
        else {
            t = new Target();
            pool.add(t);
        }
        count++;
        t.key = key;
        t.row = row;
        int slash = row.indexOf('/');
        t.group = "hdr".equals(row) ? "hdr" : slash > 0 ? row.substring(0, slash) : "main";
        t.r.set(r);
        t.radius = radius;
        t.action = action;
        t.first = first;
        return t;
    }

    private void drawFocus(Canvas c) {
        Target t = focused();
        if (t == null) return;
        r1.set(t.r);
        r1.inset(-6, -6);
        stroke.setColor(neu.bg);
        stroke.setStrokeWidth(6);
        c.drawRoundRect(r1, t.radius + 6, t.radius + 6, stroke);
        r1.inset(-5, -5);
        stroke.setColor(neu.accent);
        stroke.setStrokeWidth(6);
        c.drawRoundRect(r1, t.radius + 11, t.radius + 11, stroke);
    }

    // MARK: - Drawing helpers

    /** align: 0 left, 1 center, 2 right; y is the baseline. */
    private void text(Canvas c, String s, float x, float y, float size, Typeface face, int color, int align) {
        if (c == null || s == null || s.isEmpty()) return;
        tp.setTypeface(face);
        tp.setTextSize(size);
        tp.setColor(color);
        tp.setTextAlign(align == 1 ? Paint.Align.CENTER : align == 2 ? Paint.Align.RIGHT : Paint.Align.LEFT);
        c.drawText(s, x, y, tp);
        tp.setTextAlign(Paint.Align.LEFT);
    }

    private String ellipsize(String s, float max, float size, Typeface face) {
        if (s == null) return "";
        tp.setTypeface(face);
        tp.setTextSize(size);
        if (tp.measureText(s) <= max) return s;
        int n = tp.breakText(s, true, Math.max(0, max - tp.measureText("…")), null);
        return s.substring(0, Math.max(0, n)).trim() + "…";
    }

    private String[] wrap(String s, float max, float size, Typeface face, int maxLines) {
        tp.setTypeface(face);
        tp.setTextSize(size);
        List<String> lines = new ArrayList<>();
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String next = line.length() == 0 ? words[i] : line + " " + words[i];
            if (tp.measureText(next) <= max || line.length() == 0) {
                line.setLength(0);
                line.append(next);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(words[i]);
                if (lines.size() == maxLines - 1) {
                    StringBuilder rest = new StringBuilder(words[i]);
                    for (int k = i + 1; k < words.length; k++) rest.append(' ').append(words[k]);
                    lines.add(ellipsize(rest.toString(), max, size, face));
                    return lines.toArray(new String[0]);
                }
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines.toArray(new String[0]);
    }

    private void message(Canvas c, String title, String body, String button, Runnable action) {
        r1.set(PAD + 280, CONTENT_TOP + 120, W - PAD - 280, CONTENT_TOP + 520);
        if (c != null) {
            neu.raised(c, r1, 36, Neu.RAISED, 0);
            text(c, title, r1.centerX(), r1.top + 100, 36, Ui.semibold, neu.text, 1);
            float y = r1.top + 160;
            for (String l : wrap(body, r1.width() - 120, 26, Ui.regular, 3)) {
                text(c, l, r1.centerX(), y, 26, Ui.regular, neu.muted, 1);
                y += 38;
            }
        }
        if (button == null) return;
        tp.setTypeface(Ui.semibold);
        tp.setTextSize(28);
        float bw = tp.measureText(button) + 96;
        r2.set(r1.centerX() - bw / 2, r1.bottom - 120, r1.centerX() + bw / 2, r1.bottom - 44);
        if (c != null) {
            neu.raised(c, r2, 38, Neu.RAISED_SMALL, neu.accent);
            text(c, button, r2.centerX(), r2.centerY() + 10, 28, Ui.semibold, neu.onAccent, 1);
        }
        target("msg", "msg", r2, 38, action, true);
    }

    private void ring(Canvas c, float cx, float cy, float radius, float thick, float value, String label, float size) {
        r3.set(cx - radius, cy - radius, cx + radius, cy + radius);
        neu.raised(c, r3, radius, Neu.RAISED_SMALL, neu.well);
        stroke.setColor(neu.accent);
        stroke.setStrokeWidth(thick);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        r3.inset(thick / 2, thick / 2);
        c.drawArc(r3, -90, 360 * Math.max(0, Math.min(1, value)), false, stroke);
        r3.set(cx - radius + thick, cy - radius + thick, cx + radius - thick, cy + radius - thick);
        neu.raised(c, r3, radius - thick, Neu.RAISED_SMALL, 0);
        text(c, label, cx, cy + size * 0.36f, size, Ui.displaySemibold, neu.text, 1);
    }

    private void bar(Canvas c, float x, float y, float w, float h, float value) {
        r3.set(x, y, x + w, y + h);
        neu.inset(c, r3, h / 2, 0);
        r3.right = x + Math.max(h, w * Math.max(0, Math.min(1, value)));
        fill.setColor(neu.accent);
        c.drawRoundRect(r3, h / 2, h / 2, fill);
    }

    private void fitInto(Canvas c, Bitmap b, RectF area, float k) {
        float s = Math.min(area.width() / b.getWidth(), area.height() / b.getHeight()) * 0.8f * k;
        float w = b.getWidth() * s, h = b.getHeight() * s;
        r3.set(area.centerX() - w / 2, area.centerY() - h / 2, area.centerX() + w / 2, area.centerY() + h / 2);
        c.drawBitmap(b, null, r3, bmp);
    }

    private void play(Canvas c, float cx, float cy, float r, int color) {
        path.reset();
        path.moveTo(cx - r * 0.6f, cy - r);
        path.lineTo(cx + r, cy);
        path.lineTo(cx - r * 0.6f, cy + r);
        path.close();
        fill.setColor(color);
        c.drawPath(path, fill);
    }

    private void speaker(Canvas c, float cx, float cy, int color, boolean off) {
        path.reset();
        path.moveTo(cx - 14, cy - 5);
        path.lineTo(cx - 7, cy - 5);
        path.lineTo(cx + 2, cy - 13);
        path.lineTo(cx + 2, cy + 13);
        path.lineTo(cx - 7, cy + 5);
        path.lineTo(cx - 14, cy + 5);
        path.close();
        fill.setColor(color);
        c.drawPath(path, fill);
        stroke.setColor(color);
        stroke.setStrokeWidth(2.6f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        if (off) {
            c.drawLine(cx + 8, cy - 6, cx + 18, cy + 6, stroke);
            c.drawLine(cx + 18, cy - 6, cx + 8, cy + 6, stroke);
        } else {
            r3.set(cx - 2, cy - 8, cx + 12, cy + 8);
            c.drawArc(r3, -55, 110, false, stroke);
            r3.set(cx - 4, cy - 15, cx + 20, cy + 15);
            c.drawArc(r3, -55, 110, false, stroke);
        }
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    private void check(Canvas c, float cx, float cy, float r, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(r * 0.38f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        path.moveTo(cx - r * 0.8f, cy);
        path.lineTo(cx - r * 0.2f, cy + r * 0.6f);
        path.lineTo(cx + r * 0.85f, cy - r * 0.6f);
        c.drawPath(path, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    private void lock(Canvas c, float cx, float cy, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(2.5f);
        r3.set(cx - 10, cy - 2, cx + 10, cy + 13);
        c.drawRoundRect(r3, 3, 3, stroke);
        r3.set(cx - 6, cy - 12, cx + 6, cy + 4);
        c.drawArc(r3, 180, 180, false, stroke);
    }

    private static String initials(String name) {
        if (name == null || name.isEmpty()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length >= 2) return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase(Locale.ROOT);
        return name.substring(0, Math.min(2, name.length())).toUpperCase(Locale.ROOT);
    }
}
