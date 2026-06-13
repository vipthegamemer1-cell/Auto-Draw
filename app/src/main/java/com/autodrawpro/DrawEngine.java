package com.autodrawpro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import java.util.*;

public class DrawEngine {

    public interface DrawCallback {
        void onProgress(int drawn, int total, int currentColor, int totalColors);
        void onDone(long timeMs);
        void onCancelled();
        void onColorChanged(int colorIndex, int color);
    }

    // ─── Config ──────────────────────────────────────────────────────────────

    public static class Config {
        public int detailLevel       = 50;   // 1-100 -> controls pixel grouping
        public long delayMs          = 8;    // delay between strokes
        public int brushSizePx       = 2;    // stroke width in screen pixels
        public int ditherMode        = 0;    // 0=none,1=floyd,2=bayer,3=atkinson
        public int maxColors         = 16;
        public float hueShift        = 0f;
        public float satAdj          = 0f;
        public float valAdj          = 0f;
        public boolean vibrance      = true;
        public boolean boostShadows  = false;
        public boolean drawByColor   = true;
        public float mergeThreshold  = 12f;
        // Drawing frame bounds on screen
        public int frameX            = 100;
        public int frameY            = 300;
        public int frameW            = 800;
        public int frameH            = 800;
    }

    // ─── State ───────────────────────────────────────────────────────────────

    private volatile boolean running  = false;
    private volatile boolean paused   = false;
    private volatile boolean cancelled = false;

    private final AccessibilityService service;
    private final Handler mainHandler  = new Handler(Looper.getMainLooper());
    private DrawCallback callback;
    private Thread drawThread;

    public DrawEngine(AccessibilityService svc) {
        this.service = svc;
    }

    public void setCallback(DrawCallback cb) { this.callback = cb; }
    public boolean isRunning()  { return running; }
    public boolean isPaused()   { return paused;  }

    // ─── Start ───────────────────────────────────────────────────────────────

    public void start(Bitmap bitmap, Config cfg) {
        if (running) return;
        running   = true;
        paused    = false;
        cancelled = false;

        drawThread = new Thread(() -> {
            try {
                doDrawing(bitmap, cfg);
            } catch (InterruptedException e) {
                mainHandler.post(() -> { if (callback != null) callback.onCancelled(); });
            } finally {
                running = false;
            }
        }, "DrawThread");
        drawThread.start();
    }

    public void pause()  { paused = true; }
    public void resume() { paused = false; }

    public void stop() {
        cancelled = true;
        paused    = false;
        if (drawThread != null) drawThread.interrupt();
    }

    // ─── Core drawing algorithm ───────────────────────────────────────────────

    private void doDrawing(Bitmap srcBitmap, Config cfg) throws InterruptedException {
        long t0 = System.currentTimeMillis();

        // 1. Build palette
        List<Integer> rawPalette = ColorUtils.medianCutPalette(srcBitmap, cfg.maxColors);
        List<Integer> mergedPalette = ColorUtils.mergeSimilar(rawPalette, cfg.mergeThreshold);

        // 2. Apply HSV adjustments to palette
        int[] palette = new int[mergedPalette.size()];
        for (int i = 0; i < mergedPalette.size(); i++) {
            palette[i] = ColorUtils.adjustHsv(mergedPalette.get(i),
                    cfg.hueShift, cfg.satAdj, cfg.valAdj, cfg.vibrance, cfg.boostShadows);
        }

        // 3. Quantize source image
        int[] quantized;
        switch (cfg.ditherMode) {
            case 1: quantized = ColorUtils.quantizeFloydSteinberg(srcBitmap, palette); break;
            case 2: quantized = ColorUtils.quantizeBayer(srcBitmap, palette); break;
            case 3: quantized = ColorUtils.quantizeAtkinson(srcBitmap, palette); break;
            default: quantized = ColorUtils.quantizeNone(srcBitmap, palette); break;
        }

        int imgW = srcBitmap.getWidth(), imgH = srcBitmap.getHeight();

        // 4. Scale factors: image pixel → screen coordinate
        float scaleX = (float) cfg.frameW / imgW;
        float scaleY = (float) cfg.frameH / imgH;

        // 5. Group pixels by color if drawByColor
        List<List<Integer>> pixelGroups = new ArrayList<>();
        if (cfg.drawByColor) {
            // Group indices by color
            Map<Integer, List<Integer>> grouped = new LinkedHashMap<>();
            for (int i = 0; i < palette.length; i++) grouped.put(i, new ArrayList<>());
            for (int i = 0; i < quantized.length; i++) {
                if (quantized[i] >= 0 && quantized[i] < palette.length) {
                    grouped.get(quantized[i]).add(i);
                }
            }
            // Sort groups by pixel count desc (most common color first)
            List<Map.Entry<Integer, List<Integer>>> entries = new ArrayList<>(grouped.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
            for (Map.Entry<Integer, List<Integer>> e : entries) {
                if (!e.getValue().isEmpty()) pixelGroups.add(e.getValue());
            }
        } else {
            // All pixels in one group, scanline order
            List<Integer> all = new ArrayList<>(quantized.length);
            for (int i = 0; i < quantized.length; i++) all.add(i);
            pixelGroups.add(all);
        }

        // 6. Count total drawable pixels
        int total = 0;
        for (List<Integer> g : pixelGroups) total += g.size();
        int drawn = 0;

        // 7. Draw each group
        for (int gi = 0; gi < pixelGroups.size() && !cancelled; gi++) {
            List<Integer> group = pixelGroups.get(gi);
            if (group.isEmpty()) continue;

            int colorIdx = quantized[group.get(0)];
            if (colorIdx < 0 || colorIdx >= palette.length) colorIdx = 0;

            final int fc = palette[Math.min(colorIdx, palette.length-1)];
            final int fgi = gi;

            mainHandler.post(() -> {
                if (callback != null) callback.onColorChanged(fgi, fc);
            });

            // Group pixels into horizontal runs for faster drawing (no broken lines)
            List<long[]> strokes = buildStrokes(group, quantized, palette, cfg,
                    imgW, imgH, scaleX, scaleY, colorIdx);

            for (long[] stroke : strokes) {
                if (cancelled) break;

                // Wait if paused
                while (paused && !cancelled) {
                    Thread.sleep(100);
                }

                float x1 = Float.intBitsToFloat((int)(stroke[0] >> 32));
                float y1 = Float.intBitsToFloat((int)(stroke[0] & 0xFFFFFFFFL));
                float x2 = Float.intBitsToFloat((int)(stroke[1] >> 32));
                float y2 = Float.intBitsToFloat((int)(stroke[1] & 0xFFFFFFFFL));

                performStroke(x1, y1, x2, y2, cfg.brushSizePx);

                drawn += (int)stroke[2];
                final int fd = drawn, ft = total, fgi2 = gi, fpLen = palette.length;
                mainHandler.post(() -> {
                    if (callback != null) callback.onProgress(fd, ft, fgi2, fpLen);
                });

                if (cfg.delayMs > 0) Thread.sleep(cfg.delayMs);
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        if (!cancelled) {
            mainHandler.post(() -> { if (callback != null) callback.onDone(elapsed); });
        } else {
            mainHandler.post(() -> { if (callback != null) callback.onCancelled(); });
        }
    }

    // ─── Build optimized strokes (horizontal runs = no broken lines) ──────────

    private List<long[]> buildStrokes(List<Integer> group, int[] quantized, int[] palette,
                                       Config cfg, int imgW, int imgH,
                                       float scaleX, float scaleY, int colorIdx) {
        // Sort group by row then col for horizontal runs
        group.sort((a, b) -> {
            int ya = a / imgW, yb = b / imgW;
            if (ya != yb) return Integer.compare(ya, yb);
            return Integer.compare(a % imgW, b % imgW);
        });

        List<long[]> strokes = new ArrayList<>();
        int detail = cfg.detailLevel; // higher = merge more pixels into one stroke

        // Step factor from detail level: detail 100 = draw every pixel individually
        // detail 1 = draw in large steps (fast but coarse)
        int step = Math.max(1, (int)(100f / detail));

        int i = 0;
        while (i < group.size()) {
            int pixelIdx = group.get(i);
            int px = pixelIdx % imgW;
            int py = pixelIdx / imgW;

            float sx = cfg.frameX + px * scaleX + scaleX * 0.5f;
            float sy = cfg.frameY + py * scaleY + scaleY * 0.5f;

            // Try to extend a horizontal run
            int runLen = 1;
            while (i + runLen < group.size()) {
                int nextIdx = group.get(i + runLen);
                int nx = nextIdx % imgW;
                int ny = nextIdx / imgW;
                // Same row, adjacent pixel, same color
                if (ny == py && nx == px + runLen && runLen < detail * 3) {
                    runLen++;
                } else {
                    break;
                }
            }

            float ex = cfg.frameX + (px + runLen - 1) * scaleX + scaleX * 0.5f;
            float ey = sy;

            // Encode as long pair + pixel count
            long p1 = ((long)Float.floatToIntBits(sx) << 32) | (Float.floatToIntBits(sy) & 0xFFFFFFFFL);
            long p2 = ((long)Float.floatToIntBits(ex) << 32) | (Float.floatToIntBits(ey) & 0xFFFFFFFFL);
            strokes.add(new long[]{p1, p2, runLen});

            i += runLen;
        }

        return strokes;
    }

    // ─── Dispatch gesture stroke ───────────────────────────────────────────────

    private void performStroke(float x1, float y1, float x2, float y2, int brushSize) {
        try {
            GestureDescription.Builder builder = new GestureDescription.Builder();
            Path path = new Path();
            path.moveTo(x1, y1);

            if (Math.abs(x2 - x1) < 2f && Math.abs(y2 - y1) < 2f) {
                // Single tap for isolated pixel
                path.lineTo(x1 + 0.5f, y1 + 0.5f);
            } else {
                path.lineTo(x2, y2);
            }

            // Duration: longer path = slightly longer duration for smoother strokes
            float dist = (float)Math.hypot(x2 - x1, y2 - y1);
            long dur = Math.max(10L, Math.min(80L, (long)(dist * 0.5f)));

            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, dur);
            builder.addStroke(stroke);

            service.dispatchGesture(builder.build(), null, null);
        } catch (Exception e) {
            // ignore gesture errors, continue drawing
        }
    }
}
