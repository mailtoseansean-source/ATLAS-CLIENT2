package dev.atlasclient.gui;

import dev.atlasclient.Atlas;
import dev.atlasclient.module.client.ThemeModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/** Drawing helpers and hit regions for the immediate-mode GUI screens. Client thread only. */
public final class UiKit {
    private UiKit() { }

    /** Theme colors (ARGB), read from the Theme module each frame. */
    public record Palette(int background, int panel, int card, int cardHover, int accent, int text, int textDim, int outline) {
        public static Palette current() {
            if (Atlas.ready()) {
                ThemeModule t = Atlas.get().modules.get(ThemeModule.class);
                if (t != null) {
                    return t.palette();
                }
            }
            return new Palette(0xC0101014, 0xF0181820, 0xFF22222C, 0xFF2C2C3A, 0xFF8B5CF6, 0xFFECECF4, 0xFF9A9AB0, 0xFF34344A);
        }
    }

    public static TextRenderer font() { return MinecraftClient.getInstance().textRenderer; }

    /** Rounded rectangle built from horizontal fills (no textures needed). */
    public static void roundRect(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) {
            ctx.fill(x, y, x + w, y + h, color);
            return;
        }
        ctx.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
            ctx.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            ctx.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
        }
    }

    public static void text(DrawContext ctx, String s, int x, int y, int color) {
        ctx.drawText(font(), s, x, y, color, false);
    }

    public static void textShadow(DrawContext ctx, String s, int x, int y, int color) {
        ctx.drawTextWithShadow(font(), s, x, y, color);
    }

    public static String ellipsize(String s, int maxWidth) {
        TextRenderer f = font();
        if (f.getWidth(s) <= maxWidth) return s;
        String dots = "...";
        int end = s.length();
        while (end > 0 && f.getWidth(s.substring(0, end) + dots) > maxWidth) end--;
        return s.substring(0, Math.max(0, end)) + dots;
    }

    /** Word-wraps text to a pixel width. */
    public static List<String> wrap(String s, int maxWidth) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font().getWidth(candidate) > maxWidth && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    public static int lerpColor(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    public static int withAlpha(int color, double alpha) {
        int a = (int) Math.round(((color >>> 24) & 0xFF) * Math.max(0, Math.min(1, alpha)));
        return (a << 24) | (color & 0xFFFFFF);
    }

    /** HSV (all 0..1) to ARGB. Implemented locally to avoid loading AWT inside the game. */
    public static int hsvToArgb(float h, float s, float v, int alpha) {
        float hh = (h - (float) Math.floor(h)) * 6f;
        int sector = (int) hh;
        float f = hh - sector;
        float p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (sector) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (alpha << 24) | (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    /** ARGB to HSV {h, s, v} in 0..1. */
    public static float[] argbToHsv(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h;
        if (d == 0) h = 0;
        else if (max == r) h = ((g - b) / d) / 6f;
        else if (max == g) h = ((b - r) / d + 2) / 6f;
        else h = ((r - g) / d + 4) / 6f;
        if (h < 0) h += 1;
        return new float[]{h, max == 0 ? 0 : d / max, max};
    }

    /** Clickable region registered while drawing; handled in mouseClicked in reverse order. */
    public record Hit(int x, int y, int w, int h, String tooltip, Runnable onLeft, Runnable onRight) {
        public boolean contains(double mx, double my) { return mx >= x && my >= y && mx < x + w && my < y + h; }
    }

    public static final class Hits {
        private final List<Hit> hits = new ArrayList<>();

        public void clear() { hits.clear(); }

        public void add(int x, int y, int w, int h, String tooltip, Runnable left, Runnable right) {
            hits.add(new Hit(x, y, w, h, tooltip, left, right));
        }

        public Hit at(double mx, double my) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                if (hits.get(i).contains(mx, my)) return hits.get(i);
            }
            return null;
        }
    }
}
