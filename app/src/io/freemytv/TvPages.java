package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.Locale;

/**
 * Shortcuts to the TV's own picture and sound pages. Android has no API to change the picture or sound mode, and
 * TCL's is private: so Ajustes > TV opens TCL's pages straight away, and shows a shortcut only where one was found.
 */
final class TvPages {
    private TvPages() {}

    private static final String[] PACKAGES = {"com.tcl.settings", "com.android.tv.settings", "com.tcl.tv"};
    private static Intent picture, sound;
    private static boolean looked;

    static synchronized Intent picture(Context c) {
        look(c);
        return picture;
    }

    static synchronized Intent sound(Context c) {
        look(c);
        return sound;
    }

    private static void look(Context c) {
        if (looked) return;
        looked = true;
        PackageManager pm = c.getPackageManager();
        Intent std = new Intent(Settings.ACTION_SOUND_SETTINGS);
        if (std.resolveActivity(pm) != null) sound = std;
        for (String pkg : PACKAGES) {
            PackageInfo info;
            try {
                info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            if (info.activities == null) continue;
            for (ActivityInfo a : info.activities) {
                if (!a.exported || !a.enabled) continue;
                String n = a.name.toLowerCase(Locale.ROOT), simple = n.substring(n.lastIndexOf('.') + 1);
                if (simple.matches(".*(picture|pq|sound|audio).*")) AppLog.w("tv page: " + a.name);
                if (picture == null && (simple.contains("picture") || simple.startsWith("pq")) && !simple.contains("advance")) {
                    picture = new Intent().setComponent(new ComponentName(pkg, a.name));
                }
                if (pkg.startsWith("com.tcl") && (simple.contains("sound") || simple.contains("audio"))
                        && !simple.contains("advance") && !simple.contains("output")) {
                    sound = new Intent().setComponent(new ComponentName(pkg, a.name));
                }
            }
        }
    }
}
