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
import android.media.tv.TvContract;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bruno's home screen: a soft ("neumorphic") design in light, dark or automatic theme with an accent color, four tabs
 * (Início, Programação, Gerenciador, Ajustes) and the TV's own guide. Drawn by hand on one Canvas in a 1920×1080 frame
 * scaled to the screen (TCL's 1 GB models draw their UI at 1280×720). It only draws when something changes: no
 * animations, nothing running while another app is in front.
 */
final class NeuView extends View implements HomeData.Listener, Recs.Listener {
    private static final float W = 1920, PAD = 96, TOP = 54, CONTENT_TOP = 170, CONTENT_BOTTOM = 1020;
    private static final int HOME = 0, GUIDE = 1, TASKS = 2, SETTINGS = 3, ALL_APPS = 4;
    private static final String[] TAB_NAMES = {"Início", "Programação", "Gerenciador", "Ajustes"};
    private static final long GUIDE_SPAN_MIN = 180;
    private static final int GUIDE_ROWS = 6;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Neu neu = new Neu();
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final SharedPreferences prefs;

    // State
    private int screen = HOME, sub;
    private String theme;
    private int accent;
    private boolean showWeather, showSystem, showReleases, darkNow;
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private List<Recs.Pick> picks = new ArrayList<>();
    private List<String[]> allApps = new ArrayList<>();
    private final java.util.Map<String, Drawable> icons = new java.util.HashMap<>();
    private Guide.Result guide;
    private boolean guideLoading, guideAsked;
    private long guideFrom;
    private int guideTop;
    private Guide.Program guideSel;
    private String guideSelChannel = "";
    private Tasks.Memory mem;
    private List<Tasks.App> taskApps;
    private boolean tasksLoading;
    private String tasksError;
    private int allTop, allMaxTop;
    private boolean running;

    // Modal confirm and toast
    private String cTitle, cBody, cOk;
    private Runnable cRun;
    private String toast, focusBeforeModal;

    // Text cached per minute
    private String clock = "", date = "";
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.forLanguageTag("pt-BR"));
    private final SimpleDateFormat longDate = new SimpleDateFormat("EEEE, d 'de' MMMM", Locale.forLanguageTag("pt-BR"));

    // Focus targets, rebuilt on every pass (pooled)
    private static final class Target {
        String key;
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

    // Paints and scratch
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r1 = new RectF(), r2 = new RectF(), r3 = new RectF();
    private final Path path = new Path();
    private float scale = 1;

    NeuView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        prefs = a.getSharedPreferences("neu", Context.MODE_PRIVATE);
        theme = prefs.getString("theme", "auto");
        accent = prefs.getInt("accent", Neu.ACCENTS[0]);
        showWeather = prefs.getBoolean("weather", true);
        showSystem = prefs.getBoolean("system", true);
        showReleases = prefs.getBoolean("releases", true);
        stroke.setStyle(Paint.Style.STROKE);
        setFocusable(true);
        setFocusableInTouchMode(true);
        recs.setLight(true);
        shelf = HomeApps.shelf(a, false);
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
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            handler.post(() -> { shelf = s; invalidate(); });
        });
        if (screen == GUIDE) loadGuide();
        if (screen == TASKS) loadTasks();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 60_000 - System.currentTimeMillis() % 60_000);
        requestFocus();
        invalidate();
    }

    void pause() {
        running = false;
        handler.removeCallbacks(tick);
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
        screen = HOME;
        focusFirst = true;
        invalidate();
    }

    void showAllApps() {
        openAllApps();
    }

    void permissionsResult() {
        guideAsked = true;
        loadGuide();
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
            activity.getWindow().setStatusBarColor(neu.bg);
        }
    }

    private void updatePicks() {
        if (!Recs.hasKey(getContext())) {
            picks = new ArrayList<>();
            return;
        }
        List<Recs.Pick> all = recs.wanted(), out = new ArrayList<>();
        for (Recs.Pick p : all) {
            if (p.pkg == null) continue;
            out.add(p);
            recs.backdrop(p);   // builds the poster in the background (light design) and tells us when it's ready
            if (out.size() == 5) break;
        }
        picks = out;
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
    }

    private boolean hasFocusKey() {
        if (focusKey == null) return false;
        for (int i = 0; i < count; i++) if (focusKey.equals(pool.get(i).key)) return true;
        return false;
    }

    private void resolveFocus() {
        if (pendingDir != 0) {
            // After scrolling: the target in the row that came in, nearest the column we were on.
            Target best = null;
            float bestD = Float.MAX_VALUE, edge = pendingDir > 0 ? -Float.MAX_VALUE : Float.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                Target t = pool.get(i);
                if (t.key == null || !t.key.startsWith("g:") && !t.key.startsWith("a:")) continue;
                edge = pendingDir > 0 ? Math.max(edge, t.r.centerY()) : Math.min(edge, t.r.centerY());
            }
            for (int i = 0; i < count; i++) {
                Target t = pool.get(i);
                if (t.key == null || Math.abs(t.r.centerY() - edge) > 1) continue;
                float d = Math.abs(Math.max(t.r.left, Math.min(pendingX, t.r.right)) - pendingX);
                if (d < bestD) { bestD = d; best = t; }
            }
            pendingDir = 0;
            if (best != null) { focusKey = best.key; focusFirst = false; return; }
        }
        if (focusFirst || !hasFocusKey()) {
            Target first = null;
            for (int i = 0; i < count; i++) if (pool.get(i).first) { first = pool.get(i); break; }
            if (first == null && count > 0) first = pool.get(0);
            focusKey = first == null ? null : first.key;
            focusFirst = false;
        }
    }

    /** Lays out (and, with a canvas, draws) the whole screen; registers the focus targets on the way. */
    private void pass(Canvas c) {
        count = 0;
        modal = cTitle != null;
        inModal = false;
        header(c);
        switch (screen) {
            case GUIDE: guideScreen(c); break;
            case TASKS: tasksScreen(c); break;
            case SETTINGS: settingsScreen(c); break;
            case ALL_APPS: allAppsScreen(c); break;
            default: homeScreen(c); break;
        }
        text(c, "Setas navegam  ·  OK abre  ·  Voltar retorna", W / 2, 1062, 22, Ui.regular, neu.muted, 1);
        if (toast != null && c != null) {
            tp.setTypeface(Ui.semibold);
            tp.setTextSize(26);
            float w = tp.measureText(toast) + 120;
            r1.set(W / 2 - w / 2, 940, W / 2 + w / 2, 1024);
            neu.raised(c, r1, 42, Neu.RAISED, 0);
            fill.setColor(neu.accent);
            c.drawCircle(r1.left + 42, r1.centerY(), 22, fill);
            check(c, r1.left + 42, r1.centerY(), 12, neu.onAccent);
            text(c, toast, r1.left + 82, r1.centerY() + 9, 26, Ui.semibold, neu.text, 0);
        }
        if (modal) confirmDialog(c);
    }

    // MARK: Header

    private void header(Canvas c) {
        text(c, clock, PAD, TOP + 50, 56, Ui.displaySemibold, neu.text, 0);
        text(c, date, PAD, TOP + 86, 24, Ui.regular, neu.muted, 0);
        // Tabs
        tp.setTypeface(Ui.semibold);
        tp.setTextSize(26);
        float[] widths = new float[4];
        float total = 20 + 12 * 3;
        for (int i = 0; i < 4; i++) {
            widths[i] = tp.measureText(TAB_NAMES[i]) + 52 + 40;
            total += widths[i];
        }
        float x = W / 2 - total / 2;
        r1.set(x, TOP + 2, x + total, TOP + 86);
        if (c != null) neu.inset(c, r1, 42, neu.bg);
        x += 10;
        int current = screen == ALL_APPS ? HOME : screen;
        for (int i = 0; i < 4; i++) {
            r2.set(x, TOP + 12, x + widths[i], TOP + 76);
            final int tab = i;
            boolean on = current == i;
            if (c != null) {
                if (on) neu.raised(c, r2, 32, Neu.RAISED_SMALL, neu.accent);
                int col = on ? neu.onAccent : neu.text;
                tabIcon(c, i, r2.left + 26 + 14, r2.centerY(), col);
                text(c, TAB_NAMES[i], r2.left + 26 + 40, r2.centerY() + 9, 26, Ui.semibold, col, 0);
            }
            target("tab" + i, r2, 32, () -> openTab(tab), false);
            x += widths[i] + 12;
        }
        // TV settings, top right
        r2.set(W - PAD - 64, TOP + 12, W - PAD, TOP + 76);
        if (c != null) {
            neu.raised(c, r2, 32, Neu.RAISED_SMALL, 0);
            Symbols.draw(getContext(), c, "settings", r2.centerX(), r2.centerY(), 30, neu.text);
        }
        target("gear", r2, 32, this::openTvSettings, false);
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
        // TV ao vivo
        r1.set(PAD, top, tvRight, top + h);
        if (c != null) {
            neu.raised(c, r1, 32, Neu.RAISED, 0);
            r2.set(r1.left + 20, r1.top + 20, r1.right - 20, r1.bottom - 92);
            fill.setColor(0xFF101317);
            c.drawRoundRect(r2, 20, 20, fill);
            r3.set(r2.left + 18, r2.top + 18, r2.left + 150, r2.top + 58);
            fill.setColor(0xFFD92D20);
            c.drawRoundRect(r3, 20, 20, fill);
            text(c, "AO VIVO", r3.centerX(), r3.centerY() + 9, 24, Ui.semibold, 0xFFFFFFFF, 1);
            fill.setColor(neu.accent);
            c.drawCircle(r2.centerX(), r2.centerY(), 48, fill);
            play(c, r2.centerX() + 4, r2.centerY(), 22, neu.onAccent);
            text(c, "TV ao vivo", r1.left + 28, r1.bottom - 50, 32, Ui.semibold, neu.text, 0);
            text(c, "Canais da antena", r1.left + 28, r1.bottom - 18, 24, Ui.regular, neu.muted, 0);
            text(c, "OK para assistir", r1.right - 28, r1.bottom - 18, 24, Ui.regular, neu.muted, 2);
        }
        target("tv", r1, 32, this::openLiveTv, true);
        float x = tvRight + 24;
        if (showWeather) {
            r1.set(x, top, x + 440, top + h);
            if (c != null) weatherCard(c, r1);
            x += 440 + 24;
        }
        if (showSystem) {
            r1.set(x, top, x + 480, top + h);
            if (c != null) systemCard(c, r1);
            target("sys", r1, 32, () -> openTab(TASKS), false);
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
            target("app:" + it.pkg, r1, 28, () -> openItem(it), false);
        }
        r1.set(PAD + 6 * (colW + 24), y, W - PAD, y + 150);
        if (c != null) {
            neu.raised(c, r1, 28, Neu.RAISED_SMALL, 0);
            r2.set(r1.centerX() - 32, r1.top + 24, r1.centerX() + 32, r1.top + 88);
            neu.inset(c, r2, 32, 0);
            Symbols.draw(getContext(), c, "apps", r2.centerX(), r2.centerY(), 30, neu.text);
            text(c, "Todos os apps", r1.centerX(), r1.bottom - 24, 24, Ui.semibold, neu.text, 1);
        }
        target("allapps", r1, 28, this::openAllApps, false);
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
            target("tmdb", r1, 24, this::openFreeMyTvSettings, false);
            return;
        }
        float cw = (W - 2 * PAD - 4 * 24) / 5f;
        for (int i = 0; i < 5; i++) {
            r1.set(PAD + i * (cw + 24), y, PAD + i * (cw + 24) + cw, y + 184);
            final Recs.Pick p = i < picks.size() ? picks.get(i) : null;
            if (c != null) releaseCard(c, r1, p);
            if (p != null) target("pick:" + Recs.key(p), r1, 24, () -> openPick(p), false);
        }
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
        text(c, ellipsize("movie".equals(p.kind) ? "Filme" : "Série", tw, 22, Ui.regular), tx, r.bottom - 22, 22, Ui.regular, neu.muted, 0);
    }

    // MARK: Programação

    private void guideScreen(Canvas c) {
        if (!Guide.allowed(getContext())) {
            message(c, "A grade vem do sinal da antena",
                    "Para ler a programação, a TV precisa deixar esta tela acessar o guia de canais.",
                    guideAsked ? "Permitir de novo" : "Permitir acesso ao guia", this::askGuide);
            return;
        }
        if (guide == null || guideLoading) {
            message(c, "Lendo o guia da TV…", "Isso leva só alguns segundos.", null, null);
            return;
        }
        if (guide.channels.isEmpty()) {
            message(c, "A TV não compartilhou os canais",
                    "O guia da antena não apareceu para outros apps. Na próxima versão, a grade vem da internet.",
                    "Abrir TV ao vivo", this::openLiveTv);
            return;
        }
        long now = System.currentTimeMillis();
        float labelW = 200, gx = PAD + labelW + 20, gw = W - PAD - gx;
        float pxPerMin = gw / GUIDE_SPAN_MIN;
        // Selected program bar
        r1.set(PAD, CONTENT_TOP, W - PAD, CONTENT_TOP + 112);
        if (c != null) {
            neu.raised(c, r1, 28, Neu.RAISED, 0);
            Guide.Program sel = guideSel;
            if (sel != null) {
                boolean onAir = sel.start <= now && now < sel.end;
                text(c, ellipsize(sel.title.isEmpty() ? "Sem informação" : sel.title, 1100, 34, Ui.semibold), r1.left + 32, r1.top + 50, 34, Ui.semibold, neu.text, 0);
                text(c, "Canal " + guideSelChannel + "  ·  " + hhmm.format(new Date(sel.start)) + " – " + hhmm.format(new Date(sel.end)), r1.left + 32, r1.top + 88, 24, Ui.regular, neu.muted, 0);
                String chip = onAir ? "No ar agora" : "Começa às " + hhmm.format(new Date(sel.start));
                tp.setTypeface(Ui.semibold);
                tp.setTextSize(24);
                float cw = tp.measureText(chip) + 44;
                r2.set(r1.right - 32 - cw, r1.centerY() - 24, r1.right - 32, r1.centerY() + 24);
                if (onAir) {
                    fill.setColor(neu.accent);
                    c.drawRoundRect(r2, 24, 24, fill);
                } else {
                    neu.inset(c, r2, 24, neu.bg);
                }
                text(c, chip, r2.centerX(), r2.centerY() + 9, 24, Ui.semibold, onAir ? neu.onAccent : neu.text, 1);
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
            Guide.Channel ch = chs.get(row);
            r1.set(PAD, y, PAD + labelW, y + 96);
            if (c != null) {
                neu.raised(c, r1, 20, Neu.RAISED_SMALL, 0);
                text(c, ch.number.isEmpty() ? "Canal" : "Canal " + ch.number, r1.left + 22, r1.top + 40, 22, Ui.regular, neu.muted, 0);
                text(c, ellipsize(ch.name, labelW - 40, 26, Ui.semibold), r1.left + 22, r1.top + 74, 26, Ui.semibold, neu.text, 0);
            }
            r2.set(gx, y, gx + gw, y + 96);
            if (c != null) neu.inset(c, r2, 20, 0);
            if (ch.programs.isEmpty()) {
                r3.set(gx + 4, y + 8, gx + gw - 4, y + 88);
                if (c != null) text(c, "Sem informação de programação", r3.left + 20, r3.centerY() + 9, 24, Ui.regular, neu.muted, 0);
                final Guide.Channel fch = ch;
                target("g:" + row + ":x", r3, 14, () -> openChannel(fch), false);
            }
            for (int pi = 0; pi < ch.programs.size(); pi++) {
                final Guide.Program p = ch.programs.get(pi);
                float a = Math.max(0, (p.start - guideFrom) / 60_000f), b = Math.min(GUIDE_SPAN_MIN, (p.end - guideFrom) / 60_000f);
                if (b <= 0 || a >= GUIDE_SPAN_MIN) continue;
                r3.set(gx + a * pxPerMin + 4, y + 8, gx + Math.max(a * pxPerMin + 40, b * pxPerMin - 4), y + 88);
                boolean onAir = p.start <= now && now < p.end;
                if (c != null) {
                    if (onAir) {
                        fill.setColor(neu.bg);
                        c.drawRoundRect(r3, 14, 14, fill);
                    }
                    c.save();
                    c.clipRect(r3);
                    text(c, ellipsize(p.title, r3.width() - 32, 24, Ui.semibold), r3.left + 16, r3.top + 36, 24, Ui.semibold, neu.text, 0);
                    text(c, hhmm.format(new Date(p.start)) + " – " + hhmm.format(new Date(p.end)), r3.left + 16, r3.top + 68, 22, Ui.regular, neu.muted, 0);
                    c.restore();
                }
                final Guide.Channel fch = ch;
                boolean first = onAir && !firstSet;
                if (first) firstSet = true;
                Target t = target("g:" + row + ":" + pi, r3, 14, () -> {
                    if (p.start <= System.currentTimeMillis() && System.currentTimeMillis() < p.end) openChannel(fch);
                    else showToast(ellipsize(p.title, 700, 26, Ui.semibold) + " começa às " + hhmm.format(new Date(p.start)));
                }, first);
                if (t != null && t.key.equals(focusKey) && guideSel != p) {
                    guideSel = p;
                    guideSelChannel = ch.number;
                    if (c != null) postInvalidate();
                }
            }
            if (c != null && nowX >= gx && nowX <= gx + gw) {
                fill.setColor(neu.accent);
                c.drawRect(nowX - 2, y, nowX + 2, y + 96, fill);
            }
            y += 96 + 12;
        }
    }

    private void loadGuide() {
        if (!Guide.allowed(getContext()) || guideLoading) {
            invalidate();
            return;
        }
        guideLoading = true;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.MINUTE, cal.get(Calendar.MINUTE) < 30 ? 0 : 30);
        final long from = cal.getTimeInMillis(), to = from + GUIDE_SPAN_MIN * 60_000L;
        final Context ctx = getContext().getApplicationContext();
        invalidate();
        io.execute(() -> {
            final Guide.Result r = Guide.read(ctx, from, to);
            handler.post(() -> {
                guide = r;
                guideFrom = from;
                guideLoading = false;
                guideSel = null;
                focusFirst = screen == GUIDE;
                invalidate();
            });
        });
    }

    private void askGuide() {
        activity.requestPermissions(Guide.permissions(), 7);
    }

    // MARK: Gerenciador

    private void tasksScreen(Canvas c) {
        // Memory and storage
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
        target("free", r2, 38, this::askFreeMemory, true);
        // Open apps
        r1.set(PAD + 520 + 32, CONTENT_TOP, W - PAD, CONTENT_BOTTOM);
        if (c != null) {
            neu.raised(c, r1, 32, Neu.RAISED, 0);
            text(c, "Apps abertos", r1.left + 40, r1.top + 60, 32, Ui.semibold, neu.text, 0);
            text(c, "Memória", r1.right - 40 - 210 - 24 - 380, r1.top + 60, 24, Ui.regular, neu.muted, 0);
        }
        r2.set(r1.right - 40 - 210, r1.top + 22, r1.right - 40, r1.top + 80);
        if (c != null) {
            neu.raised(c, r2, 29, Neu.RAISED_SMALL, 0);
            text(c, tasksLoading ? "Lendo…" : "Atualizar", r2.centerX(), r2.centerY() + 9, 24, Ui.semibold, neu.text, 1);
        }
        target("refresh", r2, 29, this::loadTasks, false);
        float y = r1.top + 104;
        if (taskApps == null) {
            String msg = tasksError != null ? tasksError : "Lendo os apps abertos…";
            String[] lines = wrap(msg, r1.width() - 80, 26, Ui.regular, 4);
            for (String line : lines) {
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
                target("end:" + a.pkg, r3, 30, () -> askStop(a), false);
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
                "Liberar", () -> {
                    final Context ctx = getContext().getApplicationContext();
                    final long before = Tasks.memory(ctx).available;
                    io.execute(() -> {
                        String done;
                        try {
                            Tasks.freeMemory(ctx);
                            Thread.sleep(1200);
                            long gained = Tasks.memory(ctx).available - before;
                            done = gained > 1_048_576 ? Tasks.size(gained) + " liberados" : "Nada a liberar agora";
                        } catch (Exception e) {
                            done = "Não consegui liberar: aceite a depuração na TV";
                        }
                        final String msg = done;
                        handler.post(() -> { showToast(msg); loadTasks(); });
                    });
                });
    }

    // MARK: Ajustes

    private void settingsScreen(Canvas c) {
        r1.set(PAD, CONTENT_TOP, PAD + 400, CONTENT_BOTTOM);
        if (c != null) neu.raised(c, r1, 32, Neu.RAISED, 0);
        String[] names = {"Aparência", "Tela inicial", "TV"};
        for (int i = 0; i < 3; i++) {
            r2.set(r1.left + 28, r1.top + 28 + i * 96, r1.right - 28, r1.top + 108 + i * 96);
            final int s = i;
            if (c != null) {
                neu.raised(c, r2, 22, Neu.RAISED_SMALL, sub == i ? neu.accent : 0);
                text(c, names[i], r2.left + 28, r2.centerY() + 10, 28, Ui.semibold, sub == i ? neu.onAccent : neu.text, 0);
            }
            target("sub" + i, r2, 22, () -> { sub = s; invalidate(); }, sub == i);
        }
        r2.set(r1.left + 28, r1.bottom - 220, r1.right - 28, r1.bottom - 28);
        if (c != null) {
            neu.inset(c, r2, 22, neu.bg);
            String[] lines = wrap("Rede, contas, atualizações e o resto ficam no menu da TV", r2.width() - 40, 24, Ui.regular, 3);
            float y = r2.top + 40;
            for (String l : lines) { text(c, l, r2.left + 20, y, 24, Ui.regular, neu.muted, 0); y += 32; }
        }
        r3.set(r2.left + 20, r2.bottom - 88, r2.right - 20, r2.bottom - 20);
        if (c != null) {
            neu.raised(c, r3, 34, Neu.RAISED_SMALL, 0);
            text(c, "Configurações da TV", r3.centerX(), r3.centerY() + 9, 24, Ui.semibold, neu.text, 1);
        }
        target("native", r3, 34, this::openTvSettings, false);

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
                target("theme:" + id, r2, 24, () -> setTheme(id), false);
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
                target("accent:" + i, r2, 22, () -> setAccent(col), false);
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
            y += 20;
            y = actionRow(c, x, y, w, "Chave do TMDB, cidade e outros ajustes", "Abre os ajustes do Free My TV", "Abrir", false, this::openFreeMyTvSettings);
            actionRow(c, x, y, w, "Tela original da TCL", "Desliga esta tela e devolve a de fábrica, do jeito que era. Nada é apagado.", "Voltar à original", true, this::openFreeMyTvSettings);
        } else {
            text(c, "Imagem, som, entradas e rede", x, y, 28, Ui.semibold, neu.text, 0);
            y += 24;
            y = actionRow(c, x, y, w, "Todas as configurações da TV", "Abre o menu da própria TV, com o visual dela.", "Abrir", false, this::openTvSettings);
            text(c, "Os ajustes rápidos de imagem e som chegam na próxima versão.", x, y + 20, 24, Ui.regular, neu.muted, 0);
        }
    }

    private float toggle(Canvas c, float x, float y, float w, String label, final String key, final boolean on) {
        r2.set(x, y, x + w, y + 96);
        if (c != null) {
            neu.inset(c, r2, 22, neu.bg);
            text(c, label, r2.left + 28, r2.centerY() + 10, 26, Ui.semibold, neu.text, 0);
        }
        r3.set(r2.right - 24 - 156, r2.centerY() - 32, r2.right - 24, r2.centerY() + 32);
        if (c != null) {
            if (on) neu.raised(c, r3, 32, Neu.RAISED_SMALL, neu.accent); else neu.inset(c, r3, 32, 0);
            float kx = on ? r3.right - 32 : r3.left + 32;
            fill.setColor(neu.bg);
            c.drawCircle(kx, r3.centerY(), 26, fill);
            text(c, on ? "LIG" : "DESL", on ? r3.left + 24 : r3.right - 24, r3.centerY() + 9, 24, Ui.semibold, on ? neu.onAccent : neu.muted, on ? 0 : 2);
        }
        target("tg:" + key, r3, 32, () -> setShow(key, !on), false);
        return y + 96 + 16;
    }

    private float actionRow(Canvas c, float x, float y, float w, String title, String body, String button, boolean danger, Runnable run) {
        r2.set(x, y, x + w, y + 120);
        tp.setTypeface(Ui.semibold);
        tp.setTextSize(24);
        float bw = tp.measureText(button) + 64;
        if (c != null) {
            neu.inset(c, r2, 22, neu.bg);
            text(c, title, r2.left + 28, r2.top + 48, 26, Ui.semibold, neu.text, 0);
            text(c, ellipsize(body, w - bw - 80, 24, Ui.regular), r2.left + 28, r2.top + 86, 24, Ui.regular, neu.muted, 0);
        }
        r3.set(r2.right - 24 - bw, r2.centerY() - 32, r2.right - 24, r2.centerY() + 32);
        if (c != null) {
            neu.raised(c, r3, 32, Neu.RAISED_SMALL, danger ? 0 : neu.accent);
            text(c, button, r3.centerX(), r3.centerY() + 9, 24, Ui.semibold, danger ? neu.danger : neu.onAccent, 1);
        }
        target("act:" + title, r3, 32, run, false);
        return y + 120 + 16;
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

    // MARK: Todos os apps

    private void allAppsScreen(Canvas c) {
        text(c, "Todos os apps", PAD, CONTENT_TOP + 30, 32, Ui.semibold, neu.text, 0);
        if (allApps.isEmpty()) {
            text(c, "Carregando…", PAD, CONTENT_TOP + 90, 26, Ui.regular, neu.muted, 0);
            return;
        }
        int cols = 6, rows = 4;
        float cw = (W - 2 * PAD - (cols - 1) * 24) / cols, ch = 172, top = CONTENT_TOP + 70;
        allMaxTop = Math.max(0, (allApps.size() + cols - 1) / cols - rows);
        allTop = Math.max(0, Math.min(allTop, allMaxTop));
        for (int i = allTop * cols; i < Math.min(allApps.size(), (allTop + rows) * cols); i++) {
            final String[] app = allApps.get(i);
            int row = i / cols - allTop, col = i % cols;
            r1.set(PAD + col * (cw + 24), top + row * (ch + 24), PAD + col * (cw + 24) + cw, top + row * (ch + 24) + ch);
            if (c != null) {
                neu.raised(c, r1, 26, Neu.RAISED_SMALL, 0);
                Drawable d = icons.get(app[0]);
                if (d != null) {
                    d.setBounds((int) (r1.centerX() - 40), (int) (r1.top + 22), (int) (r1.centerX() + 40), (int) (r1.top + 102));
                    d.draw(c);
                }
                text(c, ellipsize(app[1], cw - 24, 24, Ui.semibold), r1.centerX(), r1.bottom - 24, 24, Ui.semibold, neu.text, 1);
            }
            target("a:" + app[0], r1, 26, () -> {
                HomeApps.Item it = HomeApps.app(app[0], app[1]);
                openItem(it);
            }, i == allTop * cols && focusFirst);
        }
    }

    private void openAllApps() {
        screen = ALL_APPS;
        allTop = 0;
        focusFirst = true;
        invalidate();
        final Context ctx = getContext().getApplicationContext();
        io.execute(() -> {
            final List<String[]> list = HomeApps.launchable(ctx);
            java.util.Collections.sort(list, (a, b) -> a[1].compareToIgnoreCase(b[1]));
            final java.util.Map<String, Drawable> found = new java.util.HashMap<>();
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
                focusFirst = screen == ALL_APPS;
                invalidate();
            });
        });
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
            text(c, cTitle, r1.left + 48, y, 36, Ui.semibold, neu.text, 0);
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
        target("c:cancel", r3, 36, () -> { closeConfirm(); invalidate(); }, true);
        final Runnable run = cRun;
        target("c:ok", r2, 36, () -> { closeConfirm(); if (run != null) run.run(); invalidate(); }, false);
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
        screen = tab;
        focusFirst = true;
        if (tab == GUIDE) loadGuide();
        if (tab == TASKS) loadTasks();
        invalidate();
    }

    private void openItem(HomeApps.Item it) {
        click.open();
        if (!HomeApps.open(getContext(), it, null)) showToast("Não consegui abrir " + it.label);
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

    private void openChannel(Guide.Channel ch) {
        click.open();
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(ch.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(view);
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("guide: couldn't open channel " + ch.number + ": " + e.getMessage());
            openLiveTv();
        }
    }

    private void openTvSettings() {
        click.open();
        try {
            getContext().startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            showToast("Não consegui abrir as configurações da TV");
        }
    }

    private void openFreeMyTvSettings() {
        click.open();
        getContext().startActivity(new Intent().setComponent(new ComponentName(getContext(), SettingsActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
        invalidate();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_UP) {
            return code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || isOk(code) || isArrow(code);
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (cTitle != null) closeConfirm();
                else if (screen == ALL_APPS) { screen = HOME; focusKey = "allapps"; }
                else if (screen != HOME) { screen = HOME; focusFirst = true; }
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); return true;
            default: break;
        }
        if (isOk(code)) {
            if (e.getRepeatCount() > 0) return true;
            Target t = focused();
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
        if (focusKey == null) return null;
        for (int i = 0; i < count; i++) if (focusKey.equals(pool.get(i).key)) return pool.get(i);
        return null;
    }

    private void move(int dx, int dy) {
        Target cur = focused();
        if (cur == null) {
            focusFirst = true;
            invalidate();
            return;
        }
        float cx = cur.r.centerX(), cy = cur.r.centerY();
        Target best = null;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            Target t = pool.get(i);
            if (t == cur) continue;
            float tx = t.r.centerX() - cx, ty = t.r.centerY() - cy;
            float main = dx != 0 ? tx * dx : ty * dy;
            if (main <= 4) continue;
            // Across: how far the target is from overlapping our row (or column), not just its center.
            float across = dx != 0 ? gap(cur.r.top, cur.r.bottom, t.r.top, t.r.bottom)
                    : gap(cur.r.left, cur.r.right, t.r.left, t.r.right);
            float score = main + across * 3 + (dx != 0 ? Math.abs(ty) : Math.abs(tx)) * 0.3f;
            if (score < bestScore) { bestScore = score; best = t; }
        }
        if (best == null) {
            // Lists that scroll: the guide's channels and all the apps.
            if (screen == GUIDE && guide != null && dy != 0 && cur.key.startsWith("g:")) {
                int next = guideTop + dy;
                if (next >= 0 && next <= guide.channels.size() - GUIDE_ROWS) {
                    guideTop = next;
                    pendingX = cx;
                    pendingDir = dy;
                    click.move();
                    invalidate();
                }
            } else if (screen == ALL_APPS && dy != 0 && cur.key.startsWith("a:")) {
                int next = allTop + dy;
                if (next >= 0 && next <= allMaxTop) {
                    allTop = next;
                    pendingX = cx;
                    pendingDir = dy;
                    click.move();
                    invalidate();
                }
            }
            return;
        }
        focusKey = best.key;
        click.move();
        invalidate();
    }

    private static float gap(float a0, float a1, float b0, float b1) {
        if (b1 < a0) return a0 - b1;
        if (b0 > a1) return b0 - a1;
        return 0;
    }

    // MARK: - Focus targets

    private Target target(String key, RectF r, float radius, Runnable action, boolean first) {
        if (modal && !inModal) return null;   // a dialog is open: only its buttons take the focus
        Target t;
        if (count < pool.size()) t = pool.get(count);
        else {
            t = new Target();
            pool.add(t);
        }
        count++;
        t.key = key;
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
            String[] lines = wrap(body, r1.width() - 120, 26, Ui.regular, 3);
            float y = r1.top + 160;
            for (String l : lines) { text(c, l, r1.centerX(), y, 26, Ui.regular, neu.muted, 1); y += 38; }
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
        target("msg", r2, 38, action, true);
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
