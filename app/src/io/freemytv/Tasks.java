package io.freemytv;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.StatFs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The task manager behind NeuView's Gerenciador: memory and storage from Android itself, the apps' memory and
 * stopping them through the TV's own ADB (as HomeMode does). Everything here blocks: call it off the UI thread.
 */
final class Tasks {
    private Tasks() {}

    static final class App {
        String pkg, name;
        long kb;
        boolean screen;     // has a window (activities)
        boolean system;     // needed by the TV: never stopped from here
    }

    static final class Memory {
        long total, available;   // bytes
        long diskTotal, diskFree;
    }

    /** What may never be stopped from the task manager: the TV's own pieces and us. */
    private static final Pattern KEEP = Pattern.compile("android|system|io\\.freemytv.*|com\\.android\\..*"
            + "|com\\.google\\.android\\.(gms|gsf|tv\\.remote\\.service|inputmethod\\..*|permissioncontroller|ext\\..*)"
            + "|com\\.tcl\\.(ttvs|tv|tvinput|system\\.server|settings|keyhelp|guard|partnercustomizer|tcl_bt_rcu_service"
            + "|micmanager|autopair|suspension|providers\\.config|dashboard)"
            + "|com\\.mediatek\\..*|[a-z_]+(@[0-9.]+)?(-service)?");

    private static final Pattern LINE = Pattern.compile("^\\s*([\\d,]+)K: (\\S+) \\(pid \\d+( / activities)?\\)");

    static Memory memory(Context c) {
        Memory m = new Memory();
        ActivityManager am = c.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        m.total = mi.totalMem;
        m.available = mi.availMem;
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            m.diskTotal = fs.getTotalBytes();
            m.diskFree = fs.getAvailableBytes();
        } catch (RuntimeException ignored) {
        }
        return m;
    }

    /** The processes using memory, one per app (an app's extra processes added up), biggest first. */
    static List<App> apps(Context c) throws IOException {
        String out;
        try (Adb adb = Adb.connect(c, 30_000, null)) {
            out = adb.shell("dumpsys meminfo");
        }
        PackageManager pm = c.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        boolean inList = false;
        for (String line : out.split("\n")) {
            if (line.startsWith("Total PSS by process")) { inList = true; continue; }
            if (!inList) continue;
            if (line.trim().isEmpty() || line.startsWith("Total PSS by OOM")) break;
            Matcher mt = LINE.matcher(line);
            if (!mt.find()) continue;
            String proc = mt.group(2), pkg = proc.contains(":") ? proc.substring(0, proc.indexOf(':')) : proc;
            long kb = Long.parseLong(mt.group(1).replace(",", ""));
            App a = byPkg.get(pkg);
            if (a == null) {
                a = new App();
                a.pkg = pkg;
                a.name = label(pm, pkg);
                a.system = KEEP.matcher(pkg).matches() || !pkg.contains(".");
                byPkg.put(pkg, a);
            }
            a.kb += kb;
            a.screen |= mt.group(3) != null;
        }
        List<App> list = new ArrayList<>(byPkg.values());
        Collections.sort(list, (x, y) -> Long.compare(y.kb, x.kb));
        return list;
    }

    static void stop(Context c, String pkg) throws IOException {
        if (KEEP.matcher(pkg).matches() || !pkg.contains(".")) return;
        try (Adb adb = Adb.connect(c, 30_000, null)) {
            adb.shell("am force-stop " + pkg);
        }
    }

    /** Closes what runs in the background (Android's own "kill background processes"): nothing on screen stops. */
    static void freeMemory(Context c) throws IOException {
        try (Adb adb = Adb.connect(c, 30_000, null)) {
            adb.shell("am kill-all");
        }
    }

    private static String label(PackageManager pm, String pkg) {
        switch (pkg) {
            case "system": return "Sistema Android";
            case "io.freemytv": return "Esta tela inicial";
            case "surfaceflinger": return "Desenho da tela";
            case "com.android.systemui": return "Interface do sistema";
            case "com.google.android.gms": return "Serviços do Google";
            default: break;
        }
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static String size(long bytes) {
        double gb = bytes / 1073741824.0;
        if (gb >= 1) return String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f GB", gb);
        return Math.round(bytes / 1048576.0) + " MB";
    }
}
