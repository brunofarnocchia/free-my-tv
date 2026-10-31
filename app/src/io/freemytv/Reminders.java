package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;

/**
 * Program reminders from the guide: one exact alarm per program (nothing runs while waiting), and when it goes off a
 * banner over whatever is on screen for 9 seconds (a toast where the TV doesn't let apps draw over others).
 */
final class Reminders {
    private Reminders() {}

    static final String ACTION = HomeMode.PKG + ".REMIND";
    private static final String KEY = "reminders";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("neu", Context.MODE_PRIVATE);
    }

    private static String id(Guide.Channel ch, Guide.Program p) {
        return p.start + "|" + ch.number + "|" + p.title.replace('|', ' ');
    }

    static boolean has(Context c, Guide.Channel ch, Guide.Program p) {
        return sp(c).getStringSet(KEY, new HashSet<>()).contains(id(ch, p));
    }

    /** Sets or clears the reminder; true when it's set now. */
    static boolean toggle(Context c, Guide.Channel ch, Guide.Program p) {
        Set<String> all = new HashSet<>(sp(c).getStringSet(KEY, new HashSet<>()));
        long now = System.currentTimeMillis();
        all.removeIf(s -> Long.parseLong(s.substring(0, s.indexOf('|'))) < now - 600_000L);   // old ones
        String id = id(ch, p);
        boolean on = !all.remove(id);
        PendingIntent pi = alarm(c, id, on ? 0 : PendingIntent.FLAG_NO_CREATE);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (on) {
            all.add(id);
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.start - 60_000L, pi);
            } catch (SecurityException e) {
                am.setWindow(AlarmManager.RTC_WAKEUP, p.start - 60_000L, 60_000, pi);
            }
        } else if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
        sp(c).edit().putStringSet(KEY, all).apply();
        return on;
    }

    private static PendingIntent alarm(Context c, String id, int flags) {
        Intent i = new Intent(ACTION).setClass(c, HomeReceiver.class).putExtra("id", id);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The alarm went off (main thread of the receiver): the banner, held for 9 s with the receiver's goAsync. */
    static void fire(Context c, Intent intent, Runnable done) {
        String id = intent.getStringExtra("id");
        if (id == null) { done.run(); return; }
        String[] parts = id.split("\\|", 3);
        String text = "Começa agora no canal " + parts[1] + ": " + (parts.length > 2 ? parts[2] : "");
        Context app = c.getApplicationContext();
        if (!android.provider.Settings.canDrawOverlays(app)) {
            Toast.makeText(app, text, Toast.LENGTH_LONG).show();
            done.run();
            return;
        }
        TextView banner = new TextView(app);
        banner.setText(text);
        banner.setTextColor(Color.WHITE);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * app.getResources().getDisplayMetrics().widthPixels / 1920f);
        int pad = Math.round(28 * app.getResources().getDisplayMetrics().widthPixels / 1920f);
        banner.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6262A31);
        bg.setCornerRadius(pad * 2);
        banner.setBackground(bg);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = pad * 2;
        WindowManager wm = app.getSystemService(WindowManager.class);
        try {
            wm.addView(banner, lp);
        } catch (RuntimeException e) {
            AppLog.w("reminder banner: " + e.getMessage());
            Toast.makeText(app, text, Toast.LENGTH_LONG).show();
            done.run();
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                wm.removeView(banner);
            } catch (RuntimeException ignored) {
            }
            done.run();
        }, 9_000);
    }
}
