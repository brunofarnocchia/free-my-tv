package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.HashMap;

/**
 * The soft ("neumorphic") look of NeuView: raised shapes cast a dark shadow down-right and a light one up-left, sunken
 * ones (wells) the same inside. Every shadow is blurred once per corner radius into a small alpha mask and drawn as a
 * nine-slice tinted with the theme's colors, so a frame costs bitmap draws only (no blur while drawing).
 */
final class Neu {
    /** Theme colors, set by apply(). */
    int bg, dark, light, well, text, muted, danger, onDanger, accent, onAccent = 0xFF15171C;

    static final int[] ACCENTS = {
        0xFFF5A524, 0xFFFF8A65, 0xFFFF6B6B, 0xFFFF7EB0, 0xFFE879F9, 0xFFA78BFA,
        0xFF818CF8, 0xFF5B9DFF, 0xFF38BDF8, 0xFF2EC4B6, 0xFF62C973, 0xFFA3E635,
    };
    static final String[] ACCENT_NAMES = {
        "Laranja", "Coral", "Vermelho", "Rosa", "Magenta", "Lilás",
        "Índigo", "Azul", "Ciano", "Turquesa", "Verde", "Lima",
    };

    static final int RAISED = 0, RAISED_SMALL = 1, INSET = 2;

    private final HashMap<String, Bitmap> cache = new HashMap<>();
    private final Paint tint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final float[] xs = new float[4], ys = new float[4];
    private final int[] sx = new int[4], sy = new int[4];

    void apply(boolean darkTheme, int accentColor) {
        if (darkTheme) {
            bg = 0xFF262A31; dark = 0xFF191C21; light = 0xFF333842; well = 0xFF1F2228;
            text = 0xFFE8EBF0; muted = 0xFFA6AEBB; danger = 0xFFFF9B8F; onDanger = 0xFF2A0A06;
        } else {
            bg = 0xFFE4E8EE; dark = 0xFFBCC3CE; light = 0xFFFFFFFF; well = 0xFFD8DDE5;
            text = 0xFF2E3440; muted = 0xFF555E6E; danger = 0xFFB42318; onDanger = 0xFFFFFFFF;
        }
        accent = accentColor;
    }

    /** A raised shape filled with `color` (the background when 0). */
    void raised(Canvas c, RectF r, float radius, int kind, int color) {
        float off = kind == RAISED ? 12 : 6, blur = kind == RAISED ? 24 : 12;
        dst.set(r);
        dst.offset(off, off);
        shadow(c, dst, radius, blur, dark, false, 0);
        dst.set(r);
        dst.offset(-off, -off);
        shadow(c, dst, radius, blur, light, false, 0);
        fill.setColor(color == 0 ? bg : color);
        c.drawRoundRect(r, radius, radius, fill);
    }

    /** A sunken well: filled, then shaded inside (dark from the top left, light from the bottom right). */
    void inset(Canvas c, RectF r, float radius, int color) {
        fill.setColor(color == 0 ? well : color);
        c.drawRoundRect(r, radius, radius, fill);
        float blur = Math.min(10, Math.min(r.width(), r.height()) / 4);
        float off = Math.min(5, blur / 2);
        if (blur < 2) return;
        shadow(c, r, radius, blur, dark, true, off);
        shadow(c, r, radius, blur, light, true, -off);
    }

    // MARK: - Nine-slice masks

    private void shadow(Canvas c, RectF shape, float r, float blur, int color, boolean inner, float off) {
        int radius = Math.max(0, Math.round(r)), b = Math.max(1, Math.round(blur)), o = Math.round(off);
        int m = inner ? 0 : (int) Math.ceil(b * 1.8f) + 1;
        int corner = radius + 2 * b + Math.abs(o) + (inner ? 2 : 2 * m);
        int w = slices(shape.left - m, shape.right + m, corner, xs, sx);
        int h = slices(shape.top - m, shape.bottom + m, corner, ys, sy);
        Bitmap mask = mask(radius, b, m, w, h, inner, o);
        tint.setColor(color);
        for (int i = 0; i < 3; i++) {
            if (sy[i + 1] == sy[i]) continue;
            for (int j = 0; j < 3; j++) {
                if (sx[j + 1] == sx[j]) continue;
                src.set(sx[j], sy[i], sx[j + 1], sy[i + 1]);
                dst.set(xs[j], ys[i], xs[j + 1], ys[i + 1]);
                c.drawBitmap(mask, src, dst, tint);
            }
        }
    }

    private static int slices(float from, float to, int corner, float[] out, int[] src) {
        if (to - from >= 2 * corner + 1) {
            int size = 2 * corner + 1;
            out[0] = from; out[1] = from + corner; out[2] = to - corner; out[3] = to;
            src[0] = 0; src[1] = corner; src[2] = corner + 1; src[3] = size;
            return size;
        }
        int size = Math.max(8, Math.round(to - from));
        out[0] = from; out[1] = from; out[2] = to; out[3] = to;
        src[0] = 0; src[1] = 0; src[2] = size; src[3] = size;
        return size;
    }

    private Bitmap mask(int radius, int b, int m, int w, int h, boolean inner, int o) {
        String key = radius + "," + b + "," + m + "," + w + "," + h + "," + inner + "," + o;
        Bitmap mask = cache.get(key);
        if (mask != null) return mask;
        Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(full);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        p.setMaskFilter(new BlurMaskFilter(b, BlurMaskFilter.Blur.NORMAL));
        if (!inner) {
            cv.drawRoundRect(m, m, w - m, h - m, radius, radius, p);
        } else {
            // Everything outside the shape, moved by the offset and blurred, then kept only inside the shape.
            Path outside = new Path();
            outside.setFillType(Path.FillType.EVEN_ODD);
            outside.addRect(-4 * b - 8, -4 * b - 8, w + 4 * b + 8, h + 4 * b + 8, Path.Direction.CW);
            outside.addRoundRect(o, o, w + o, h + o, radius, radius, Path.Direction.CW);
            cv.drawPath(outside, p);
            Paint keep = new Paint(Paint.ANTI_ALIAS_FLAG);
            keep.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            cv.drawRoundRect(0, 0, w, h, radius, radius, keep);
        }
        mask = full.extractAlpha();
        full.recycle();
        if (cache.size() > 96) cache.clear();
        cache.put(key, mask);
        return mask;
    }

    void clear() {
        cache.clear();
    }
}
