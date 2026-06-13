package com.autodrawpro;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.*;

public class ColorUtils {

    // ─── RGB ↔ HSV ───────────────────────────────────────────────────────────

    public static float[] rgbToHsv(int r, int g, int b) {
        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        return hsv;
    }

    public static int hsvToRgb(float h, float s, float v) {
        return Color.HSVToColor(new float[]{h, s, v});
    }

    public static int adjustHsv(int color, float hShift, float sMult, float vMult,
                                  boolean vibrance, boolean boostShadows) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);

        hsv[0] = (hsv[0] + hShift + 360f) % 360f;
        hsv[1] = Math.max(0f, Math.min(1f, hsv[1] + sMult));
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] + vMult));

        if (vibrance && hsv[1] < 0.6f) {
            hsv[1] = Math.min(1f, hsv[1] * 1.25f + 0.05f);
        }
        if (boostShadows && hsv[2] < 0.3f) {
            hsv[2] = Math.min(1f, hsv[2] * 1.5f + 0.05f);
        }

        return Color.HSVToColor(hsv);
    }

    // ─── RGB → CIE Lab ───────────────────────────────────────────────────────

    public static float[] rgbToLab(int r, int g, int b) {
        double rr = r / 255.0, gg = g / 255.0, bb = b / 255.0;
        rr = rr > 0.04045 ? Math.pow((rr + 0.055) / 1.055, 2.4) : rr / 12.92;
        gg = gg > 0.04045 ? Math.pow((gg + 0.055) / 1.055, 2.4) : gg / 12.92;
        bb = bb > 0.04045 ? Math.pow((bb + 0.055) / 1.055, 2.4) : bb / 12.92;

        double x = (rr * 0.4124564 + gg * 0.3575761 + bb * 0.1804375) / 0.95047;
        double y = (rr * 0.2126729 + gg * 0.7151522 + bb * 0.0721750);
        double z = (rr * 0.0193339 + gg * 0.1191920 + bb * 0.9503041) / 1.08883;

        x = x > 0.008856 ? Math.cbrt(x) : 7.787 * x + 16.0 / 116;
        y = y > 0.008856 ? Math.cbrt(y) : 7.787 * y + 16.0 / 116;
        z = z > 0.008856 ? Math.cbrt(z) : 7.787 * z + 16.0 / 116;

        return new float[]{
            (float)(116 * y - 16),
            (float)(500 * (x - y)),
            (float)(200 * (y - z))
        };
    }

    public static float deltaE(int c1, int c2) {
        float[] lab1 = rgbToLab(Color.red(c1), Color.green(c1), Color.blue(c1));
        float[] lab2 = rgbToLab(Color.red(c2), Color.green(c2), Color.blue(c2));
        float dL = lab1[0] - lab2[0];
        float da = lab1[1] - lab2[1];
        float db = lab1[2] - lab2[2];
        return (float) Math.sqrt(dL * dL + da * da + db * db);
    }

    // ─── Median Cut Palette ───────────────────────────────────────────────────

    public static List<Integer> medianCutPalette(Bitmap bmp, int maxColors) {
        // Sample pixels
        int w = bmp.getWidth(), h = bmp.getHeight();
        int step = Math.max(1, (w * h) / 4000);
        List<int[]> pixels = new ArrayList<>();
        int[] rowBuf = new int[w];

        for (int y = 0; y < h; y += Math.max(1, step / w + 1)) {
            bmp.getPixels(rowBuf, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x += Math.max(1, step)) {
                int px = rowBuf[x];
                if (Color.alpha(px) < 128) continue;
                pixels.add(new int[]{Color.red(px), Color.green(px), Color.blue(px)});
            }
        }

        if (pixels.isEmpty()) return Collections.singletonList(Color.BLACK);

        List<List<int[]>> buckets = new ArrayList<>();
        buckets.add(new ArrayList<>(pixels));

        while (buckets.size() < maxColors) {
            // Find bucket with largest range
            int splitIdx = -1;
            int maxRange = -1;
            int splitCh = 0;

            for (int i = 0; i < buckets.size(); i++) {
                List<int[]> bucket = buckets.get(i);
                if (bucket.size() < 2) continue;
                for (int ch = 0; ch < 3; ch++) {
                    int mn = 255, mx = 0;
                    for (int[] p : bucket) { mn = Math.min(mn, p[ch]); mx = Math.max(mx, p[ch]); }
                    if (mx - mn > maxRange) { maxRange = mx - mn; splitIdx = i; splitCh = ch; }
                }
            }
            if (splitIdx == -1) break;

            final int sch = splitCh;
            List<int[]> bucket = buckets.remove(splitIdx);
            bucket.sort((a, b) -> Integer.compare(a[sch], b[sch]));
            int mid = bucket.size() / 2;
            buckets.add(new ArrayList<>(bucket.subList(0, mid)));
            buckets.add(new ArrayList<>(bucket.subList(mid, bucket.size())));
        }

        List<Integer> palette = new ArrayList<>();
        for (List<int[]> bucket : buckets) {
            if (bucket.isEmpty()) continue;
            long sr = 0, sg = 0, sb = 0;
            for (int[] p : bucket) { sr += p[0]; sg += p[1]; sb += p[2]; }
            int n = bucket.size();
            palette.add(Color.rgb((int)(sr/n), (int)(sg/n), (int)(sb/n)));
        }
        return palette;
    }

    // ─── Nearest Color (LAB Delta-E) ─────────────────────────────────────────

    public static int nearestColorIndex(int color, int[] palette) {
        float best = Float.MAX_VALUE;
        int idx = 0;
        for (int i = 0; i < palette.length; i++) {
            float d = deltaE(color, palette[i]);
            if (d < best) { best = d; idx = i; }
        }
        return idx;
    }

    // ─── Quantize + Floyd-Steinberg dithering ─────────────────────────────────

    public static int[] quantizeFloydSteinberg(Bitmap bmp, int[] palette) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        // Work in float error buffer
        float[] er = new float[w * h];
        float[] eg = new float[w * h];
        float[] eb = new float[w * h];

        int[] out = new int[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int px = pixels[idx];
                if (Color.alpha(px) < 128) { out[idx] = 0; continue; }

                int nr = Math.max(0, Math.min(255, Color.red(px)   + (int)er[idx]));
                int ng = Math.max(0, Math.min(255, Color.green(px) + (int)eg[idx]));
                int nb = Math.max(0, Math.min(255, Color.blue(px)  + (int)eb[idx]));

                int ci = nearestColorIndex(Color.rgb(nr, ng, nb), palette);
                out[idx] = ci;

                int qr = nr - Color.red(palette[ci]);
                int qg = ng - Color.green(palette[ci]);
                int qb = nb - Color.blue(palette[ci]);

                // Distribute error: 7/16, 3/16, 5/16, 1/16
                if (x + 1 < w) {
                    er[idx+1]   += qr * 7f/16; eg[idx+1]   += qg * 7f/16; eb[idx+1]   += qb * 7f/16;
                }
                if (y + 1 < h) {
                    if (x - 1 >= 0) { er[idx+w-1] += qr * 3f/16; eg[idx+w-1] += qg * 3f/16; eb[idx+w-1] += qb * 3f/16; }
                    er[idx+w]   += qr * 5f/16; eg[idx+w]   += qg * 5f/16; eb[idx+w]   += qb * 5f/16;
                    if (x + 1 < w) { er[idx+w+1] += qr * 1f/16; eg[idx+w+1] += qg * 1f/16; eb[idx+w+1] += qb * 1f/16; }
                }
            }
        }
        return out;
    }

    // ─── Quantize no dither ───────────────────────────────────────────────────

    public static int[] quantizeNone(Bitmap bmp, int[] palette) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] out = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            if (Color.alpha(pixels[i]) < 128) { out[i] = 0; continue; }
            out[i] = nearestColorIndex(pixels[i], palette);
        }
        return out;
    }

    // ─── Ordered (Bayer) dithering ────────────────────────────────────────────

    public static int[] quantizeBayer(Bitmap bmp, int[] palette) {
        int[][] bayer = {{0,8,2,10},{12,4,14,6},{3,11,1,9},{15,7,13,5}};
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int px = pixels[idx];
                if (Color.alpha(px) < 128) { out[idx] = 0; continue; }
                int t = (int)((bayer[y%4][x%4] / 16f - 0.5f) * 24);
                int nr = Math.max(0, Math.min(255, Color.red(px) + t));
                int ng = Math.max(0, Math.min(255, Color.green(px) + t));
                int nb = Math.max(0, Math.min(255, Color.blue(px) + t));
                out[idx] = nearestColorIndex(Color.rgb(nr, ng, nb), palette);
            }
        }
        return out;
    }

    // ─── Atkinson dithering ───────────────────────────────────────────────────

    public static int[] quantizeAtkinson(Bitmap bmp, int[] palette) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        float[] er = new float[w * h];
        float[] eg = new float[w * h];
        float[] eb = new float[w * h];
        int[] out = new int[w * h];
        int[][] spread = {{1,0},{2,0},{-1,1},{0,1},{1,1},{0,2}};

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int px = pixels[idx];
                if (Color.alpha(px) < 128) { out[idx] = 0; continue; }
                int nr = Math.max(0, Math.min(255, Color.red(px)   + (int)er[idx]));
                int ng = Math.max(0, Math.min(255, Color.green(px) + (int)eg[idx]));
                int nb = Math.max(0, Math.min(255, Color.blue(px)  + (int)eb[idx]));
                int ci = nearestColorIndex(Color.rgb(nr, ng, nb), palette);
                out[idx] = ci;
                float fr = (nr - Color.red(palette[ci]))   / 8f;
                float fg = (ng - Color.green(palette[ci])) / 8f;
                float fb = (nb - Color.blue(palette[ci]))  / 8f;
                for (int[] d : spread) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx < 0 || nx >= w || ny >= h) continue;
                    int ni = ny * w + nx;
                    er[ni] += fr; eg[ni] += fg; eb[ni] += fb;
                }
            }
        }
        return out;
    }

    // ─── Merge similar palette colors ─────────────────────────────────────────

    public static List<Integer> mergeSimilar(List<Integer> palette, float threshold) {
        boolean[] used = new boolean[palette.size()];
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) {
            if (used[i]) continue;
            int c = palette.get(i);
            for (int j = i + 1; j < palette.size(); j++) {
                if (!used[j] && deltaE(c, palette.get(j)) < threshold) {
                    used[j] = true;
                }
            }
            result.add(c);
        }
        return result;
    }
    }
                                     
