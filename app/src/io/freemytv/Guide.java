package io.freemytv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The TV's own program guide (the antenna's channels and their programs, as the tuner wrote them to Android's TV
 * provider). Reading other apps' channels needs READ_EPG_DATA and READ_TV_LISTINGS; without them the provider only
 * answers with our own (none). Runs off the UI thread.
 */
final class Guide {
    static final String READ_EPG = "com.android.providers.tv.permission.READ_EPG_DATA";
    static final String READ_LISTINGS = "android.permission.READ_TV_LISTINGS";

    static final class Program {
        String title;
        long start, end;
    }

    static final class Channel {
        long id;
        String number = "", name = "", inputId;
        final List<Program> programs = new ArrayList<>();
    }

    static final class Result {
        final List<Channel> channels = new ArrayList<>();
        int programCount;
        String error;   // null: read fine (channels may still be empty)
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(READ_EPG) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(READ_LISTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Channels in number order, each with its programs between `from` and `to` (epoch ms). */
    static Result read(Context c, long from, long to) {
        Result out = new Result();
        ContentResolver cr = c.getContentResolver();
        Map<Long, Channel> byId = new HashMap<>();
        try (Cursor cur = cr.query(TvContract.Channels.CONTENT_URI, new String[] {
                TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER, TvContract.Channels.COLUMN_DISPLAY_NAME,
                TvContract.Channels.COLUMN_INPUT_ID, TvContract.Channels.COLUMN_BROWSABLE}, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                if (!cur.isNull(4) && cur.getInt(4) == 0) continue;   // hidden by the person in the TV's channel list
                Channel ch = new Channel();
                ch.id = cur.getLong(0);
                ch.number = nz(cur.getString(1));
                ch.name = nz(cur.getString(2));
                ch.inputId = cur.getString(3);
                byId.put(ch.id, ch);
                out.channels.add(ch);
            }
        } catch (RuntimeException e) {
            out.error = String.valueOf(e.getMessage());
            AppLog.w("guide: channels: " + e.getMessage());
            return out;
        }
        if (out.channels.isEmpty()) {
            AppLog.w("guide: the TV shares no channels");
            return out;
        }
        // All channels' programs in the window (the provider filters by these two parameters).
        Uri programs = TvContract.Programs.CONTENT_URI.buildUpon()
                .appendQueryParameter("start_time", String.valueOf(from))
                .appendQueryParameter("end_time", String.valueOf(to)).build();
        try (Cursor cur = cr.query(programs, new String[] {
                TvContract.Programs.COLUMN_CHANNEL_ID, TvContract.Programs.COLUMN_TITLE,
                TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS},
                null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                Channel ch = byId.get(cur.getLong(0));
                if (ch == null) continue;
                Program p = new Program();
                p.title = nz(cur.getString(1));
                p.start = cur.getLong(2);
                p.end = cur.getLong(3);
                if (p.end <= from || p.start >= to || p.end <= p.start) continue;
                ch.programs.add(p);
                out.programCount++;
            }
        } catch (RuntimeException e) {
            AppLog.w("guide: programs: " + e.getMessage());
        }
        for (Channel ch : out.channels) Collections.sort(ch.programs, (a, b) -> Long.compare(a.start, b.start));
        Collections.sort(out.channels, (a, b) -> compareNumbers(a.number, b.number));
        AppLog.w("guide: " + out.channels.size() + " channels, " + out.programCount + " programs");
        return out;
    }

    /** "2.1" before "12.1": number by number. */
    static int compareNumbers(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : -1, q = i < y.length ? parse(y[i]) : -1;
            if (p != q) return Integer.compare(p, q);
        }
        return a.compareTo(b);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String[] permissions() {
        return new String[] {READ_EPG, READ_LISTINGS};
    }
}
