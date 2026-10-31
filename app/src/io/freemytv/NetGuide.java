package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The guide from the internet, for TVs that keep the antenna's guide to themselves (TCL's Android 11 does): guia.json,
 * made every 8 hours by .github/workflows/guide.yml from the networks' national guide. The TV keeps its copy 8 hours,
 * then throws it away and downloads it again the next time the guide is needed. Blocks: call it off the UI thread.
 */
final class NetGuide {
    private NetGuide() {}

    private static final String URL_GUIDE = "https://github.com/brunofarnocchia/free-my-tv/releases/download/guia/guia.json";
    static final long KEEP_MS = 8 * 3600_000L;

    static Guide.Result read(Context c, long from, long to) {
        Guide.Result out = new Guide.Result();
        File file = new File(c.getFilesDir(), "guia.json");
        long age = System.currentTimeMillis() - file.lastModified();
        if (file.exists() && (age > KEEP_MS || age < 0)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();   // 8 hours old: gone, a new one comes now
        }
        try {
            if (!file.exists()) download(file);
            parse(new String(readAll(new java.io.FileInputStream(file)), StandardCharsets.UTF_8), from, to, out);
        } catch (Exception e) {
            out.error = String.valueOf(e.getMessage());
            AppLog.w("guide (internet): " + e.getMessage());
        }
        return out;
    }

    private static void download(File file) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(URL_GUIDE).openConnection();
        http.setConnectTimeout(15_000);
        http.setReadTimeout(20_000);
        http.setInstanceFollowRedirects(true);
        try {
            if (http.getResponseCode() != 200) throw new IOException("HTTP " + http.getResponseCode());
            byte[] body = readAll(http.getInputStream());
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(body);
            }
            if (!tmp.renameTo(file)) throw new IOException("couldn't save the guide");
            AppLog.w("guide (internet): downloaded " + body.length + " bytes");
        } finally {
            http.disconnect();
        }
    }

    private static void parse(String json, long from, long to, Guide.Result out) throws Exception {
        JSONArray chs = new JSONObject(json).getJSONArray("channels");
        for (int i = 0; i < chs.length(); i++) {
            JSONObject o = chs.getJSONObject(i);
            Guide.Channel ch = new Guide.Channel();
            ch.id = -1;
            ch.number = o.optString("n");
            ch.name = o.optString("name");
            JSONArray ps = o.optJSONArray("p");
            for (int k = 0; ps != null && k < ps.length(); k++) {
                JSONArray p = ps.getJSONArray(k);
                Guide.Program g = new Guide.Program();
                g.start = p.getLong(0) * 1000;
                g.end = p.getLong(1) * 1000;
                g.title = p.optString(2);
                if (g.end <= from || g.start >= to) continue;
                ch.programs.add(g);
                out.programCount++;
            }
            out.channels.add(ch);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }
}
