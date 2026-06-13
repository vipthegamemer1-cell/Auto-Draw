package com.autodrawpro;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.widget.*;
import java.util.*;

public class AutoDrawService extends AccessibilityService {

    // ─── Singleton ────────────────────────────────────────────────────────────
    private static AutoDrawService instance;
    public static AutoDrawService getInstance() { return instance; }
    public static int instance_drawDetail = 50;

    // ─── State ────────────────────────────────────────────────────────────────
    private WindowManager wm;
    private View          floatingRoot;
    private View          drawingFrameView;
    private DrawEngine    drawEngine;
    private Bitmap        sourceBitmap;
    private List<Integer> detectedPalette = new ArrayList<>();
    private int[]         adjustedPalette;
    private int[]         quantizedData;

    // Current config
    private DrawEngine.Config cfg = new DrawEngine.Config();

    // Frame/crosshair drag state
    private float lastTouchX, lastTouchY;
    private int frameX, frameY, frameW = 600, frameH = 600;
    private boolean frameVisible = false;

    // UI references
    private TextView  tvStatus, tvProgress, tvColorInfo;
    private SeekBar   sbDelay, sbDetail, sbBrush, sbHue, sbSat, sbVal;
    private Spinner   spDither;
    private View      colorPreviewBox;
    private LinearLayout paletteRow;
    private ProgressBar progressBar;
    private Button    btnStart, btnPause, btnStop;
    private View      frameHandle;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // ─── Service lifecycle ────────────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        drawEngine = new DrawEngine(this);
        setupDrawEngineCallback();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        hideFloatingMenu();
    }

    // ─── Show/Hide floating window ────────────────────────────────────────────

    public void showFloatingMenu() {
        if (floatingRoot != null) return;
        buildFloatingUI();
    }

    public void hideFloatingMenu() {
        if (floatingRoot != null) {
            try { wm.removeView(floatingRoot); } catch (Exception ignore) {}
            floatingRoot = null;
        }
        hideDrawingFrame();
    }

    // ─── Build floating UI ─────────────────────────────────────────────────────

    private void buildFloatingUI() {
        Context ctx = this;

        // ── Root container ──────────────────────────────────────────────────
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(0xF2101318);
        rootBg.setCornerRadius(dp(14));
        rootBg.setStroke(dp(1), 0xFF2a2d38);
        root.setBackground(rootBg);

        // ── Drag handle / title bar ──────────────────────────────────────────
        LinearLayout titleBar = row(ctx);
        TextView title = tv(ctx, "AUTO DRAW PRO", 15, true);
        title.setTextColor(0xFF7c6bff);
        setShadow(title, 0xFF7c6bff);
        Button btnClose = miniBtn(ctx, "✕", 0xFFef4444);

        titleBar.addView(title, wrapWeight());
        titleBar.addView(btnClose, wrap());
        root.addView(titleBar, matchWrap());

        // ── Image info ────────────────────────────────────────────────────────
        tvStatus = tv(ctx, "Sẵn sàng", 11, false);
        tvStatus.setTextColor(0xFF8a8d9e);
        root.addView(tvStatus, matchWrap(0, dp(4)));

        // ── Palette row ───────────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "🎨 BẢNG MÀU"), matchWrap(0, dp(8)));
        paletteRow = new LinearLayout(ctx);
        paletteRow.setOrientation(LinearLayout.HORIZONTAL);
        ScrollView palScroll = new ScrollView(ctx);
        palScroll.setHorizontalScrollBarEnabled(true);
        HorizontalScrollView palHScroll = new HorizontalScrollView(ctx);
        palHScroll.addView(paletteRow);
        root.addView(palHScroll, matchWrap());

        colorPreviewBox = new View(ctx);
        colorPreviewBox.setBackgroundColor(0xFF333333);
        root.addView(colorPreviewBox, new LinearLayout.LayoutParams(matchParent(), dp(28)));
        ((LinearLayout.LayoutParams) colorPreviewBox.getLayoutParams()).topMargin = dp(4);

        tvColorInfo = tv(ctx, "HEX: -", 10, false);
        tvColorInfo.setTextColor(0xFF8a8d9e);
        root.addView(tvColorInfo, matchWrap());

        // ── Buttons: Phân tích màu ──────────────────────────────────────────
        Button btnAnalyze = bigBtn(ctx, "🔍 PHÂN TÍCH MÀU", 0xFF7c6bff);
        root.addView(btnAnalyze, matchWrap(dp(8), 0));

        // ── Separator ─────────────────────────────────────────────────────────
        root.addView(separator(ctx), matchWrap(dp(6), dp(6)));

        // ── HSV Controls ──────────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "🌈 HSV"), matchWrap(0, dp(4)));

        sbHue = seekRow(ctx, root, "Hue Shift", -180, 180, 0);
        sbSat = seekRow(ctx, root, "Saturation", -100, 100, 0);
        sbVal = seekRow(ctx, root, "Brightness", -100, 100, 0);

        // ── Drawing settings ─────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "✏️ VẼ"), matchWrap(dp(8), dp(4)));

        sbDetail = seekRow(ctx, root, "Chi tiết", 1, 100, cfg.detailLevel);
        sbBrush  = seekRow(ctx, root, "Brush px", 1, 10, cfg.brushSizePx);
        sbDelay  = seekRow(ctx, root, "Delay ms", 0, 150, (int)cfg.delayMs);

        // Dither spinner
        LinearLayout ditherRow = row(ctx);
        ditherRow.addView(tv(ctx, "Dithering: ", 11, false), wrap());
        spDither = new Spinner(ctx);
        ArrayAdapter<String> ditherAdapter = new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item,
                new String[]{"Không", "Floyd-Steinberg", "Bayer", "Atkinson"});
        ditherAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDither.setAdapter(ditherAdapter);
        ditherRow.addView(spDither, wrapWeight());
        root.addView(ditherRow, matchWrap());

        // ── Frame controls ────────────────────────────────────────────────────
        root.addView(separator(ctx), matchWrap(dp(6), dp(6)));
        root.addView(sectionLabel(ctx, "🖼️ KHUNG VẼ"), matchWrap(0, dp(4)));

        LinearLayout frameRow = row(ctx);
        Button btnShowFrame = miniBtn(ctx, "Hiện khung", 0xFF2a2d38);
        Button btnFrameSize = miniBtn(ctx, "Size +/-", 0xFF2a2d38);
        frameRow.addView(btnShowFrame, wrapWeight());
        frameRow.addView(btnFrameSize, wrapWeight());
        root.addView(frameRow, matchWrap());

        // ── Progress bar ──────────────────────────────────────────────────────
        root.addView(separator(ctx), matchWrap(dp(6), dp(6)));
        progressBar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        root.addView(progressBar, matchWrap());

        tvProgress = tv(ctx, "0%", 10, false);
        tvProgress.setTextColor(0xFF7c6bff);
        tvProgress.setGravity(Gravity.CENTER);
        root.addView(tvProgress, matchWrap());

        // ── Main control buttons ──────────────────────────────────────────────
        btnStart = bigBtn(ctx, "▶ BẮT ĐẦU VẼ", 0xFF22c55e);
        btnPause = bigBtn(ctx, "⏸ TẠM DỪNG", 0xFFf59e0b);
        btnStop  = bigBtn(ctx, "⏹ DỪNG", 0xFFef4444);
        btnPause.setEnabled(false);
        btnStop.setEnabled(false);

        LinearLayout ctrlRow = row(ctx);
        ctrlRow.addView(btnStart, wrapWeight());
        ctrlRow.addView(btnPause, wrapWeight());
        ctrlRow.addView(btnStop,  wrapWeight());
        root.addView(ctrlRow, matchWrap(dp(8), 0));

        // ── Listeners ────────────────────────────────────────────────────────
        btnClose.setOnClickListener(v -> hideFloatingMenu());

        btnAnalyze.setOnClickListener(v -> {
            if (sourceBitmap == null) { toast("Chọn ảnh trước!"); return; }
            tvStatus.setText("Đang phân tích màu...");
            new Thread(() -> {
                detectedPalette = ColorUtils.medianCutPalette(sourceBitmap, cfg.maxColors);
                detectedPalette = ColorUtils.mergeSimilar(detectedPalette, cfg.mergeThreshold);
                handler.post(this::renderPalette);
            }).start();
        });

        sbHue.setOnSeekBarChangeListener(simpleSeek(p -> cfg.hueShift = p - 180));
        sbSat.setOnSeekBarChangeListener(simpleSeek(p -> cfg.satAdj = (p - 100) / 100f));
        sbVal.setOnSeekBarChangeListener(simpleSeek(p -> cfg.valAdj = (p - 100) / 100f));
        sbDetail.setOnSeekBarChangeListener(simpleSeek(p -> cfg.detailLevel = Math.max(1, p)));
        sbBrush.setOnSeekBarChangeListener(simpleSeek(p -> cfg.brushSizePx = Math.max(1, p)));
        sbDelay.setOnSeekBarChangeListener(simpleSeek(p -> cfg.delayMs = p));
        spDither.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { cfg.ditherMode = pos; }
            public void onNothingSelected(AdapterView<?> p) {}
        });

        btnShowFrame.setOnClickListener(v -> {
            if (!frameVisible) showDrawingFrame();
            else hideDrawingFrame();
            btnShowFrame.setText(frameVisible ? "Ẩn khung" : "Hiện khung");
        });

        btnFrameSize.setOnClickListener(v -> showFrameSizeDialog());

        btnStart.setOnClickListener(v -> startDrawing());
        btnPause.setOnClickListener(v -> {
            if (drawEngine.isPaused()) {
                drawEngine.resume();
                btnPause.setText("⏸ TẠM DỪNG");
                tvStatus.setText("Đang vẽ...");
            } else {
                drawEngine.pause();
                btnPause.setText("▶ TIẾP TỤC");
                tvStatus.setText("Tạm dừng");
            }
        });
        btnStop.setOnClickListener(v -> {
            drawEngine.stop();
            btnStart.setEnabled(true);
            btnPause.setEnabled(false);
            btnStop.setEnabled(false);
            tvStatus.setText("Đã dừng");
        });

        // ── Window params ─────────────────────────────────────────────────────
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 20; lp.y = 100;

        // Drag to move
        titleBar.setOnTouchListener(moveTouchListener(lp, root));

        floatingRoot = root;
        wm.addView(root, lp);

        // Init from MainActivity
        if (MainActivity.selectedBitmap != null) {
            setBitmap(MainActivity.selectedBitmap);
        }
        cfg.detailLevel = instance_drawDetail;
        sbDetail.setProgress(cfg.detailLevel);
    }

    // ─── Drawing Frame (shows the target area on screen) ─────────────────────

    private void showDrawingFrame() {
        if (drawingFrameView != null) return;
        frameVisible = true;

        frameX = 100; frameY = 300;
        if (frameW == 0) frameW = 600;
        if (frameH == 0) frameH = 600;

        FrameLayout frameLayout = new FrameLayout(this);

        // Dashed border
        View border = new View(this) {
            private final Paint paint = new Paint();
            {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(0xFF7c6bff);
                paint.setStrokeWidth(4f);
                paint.setPathEffect(new DashPathEffect(new float[]{20f, 10f}, 0f));
            }
            @Override
            protected void onDraw(Canvas canvas) {
                canvas.drawRect(2, 2, getWidth()-2, getHeight()-2, paint);
            }
        };
        border.setWillNotDraw(false);
        frameLayout.addView(border, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Corner crosshair label
        TextView label = new TextView(this);
        label.setText("↔ Kéo để di chuyển");
        label.setTextSize(10);
        label.setTextColor(0xFFe8e9f0);
        label.setBackgroundColor(0xCC000000);
        label.setPadding(dp(6), dp(2), dp(6), dp(2));
        FrameLayout.LayoutParams lbl = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lbl.gravity = Gravity.TOP | Gravity.START;
        lbl.topMargin = dp(2); lbl.leftMargin = dp(2);
        frameLayout.addView(label, lbl);

        // Resize handle (bottom-right corner)
        View resizeHandle = new View(this);
        GradientDrawable rhBg = new GradientDrawable();
        rhBg.setColor(0xFF7c6bff);
        rhBg.setCornerRadius(dp(4));
        resizeHandle.setBackground(rhBg);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(24), dp(24));
        rlp.gravity = Gravity.BOTTOM | Gravity.END;
        frameLayout.addView(resizeHandle, rlp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                frameW, frameH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = frameX; lp.y = frameY;

        // Drag move
        frameLayout.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = e.getRawX(); lastTouchY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x += (int)(e.getRawX() - lastTouchX);
                    lp.y += (int)(e.getRawY() - lastTouchY);
                    frameX = lp.x; frameY = lp.y;
                    lastTouchX = e.getRawX(); lastTouchY = e.getRawY();
                    wm.updateViewLayout(drawingFrameView, lp);
                    return true;
            }
            return false;
        });

        // Resize handle touch
        resizeHandle.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = e.getRawX(); lastTouchY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int)(e.getRawX() - lastTouchX);
                    int dy = (int)(e.getRawY() - lastTouchY);
                    frameW = Math.max(100, lp.width + dx);
                    frameH = Math.max(100, lp.height + dy);
                    lp.width = frameW; lp.height = frameH;
                    lastTouchX = e.getRawX(); lastTouchY = e.getRawY();
                    wm.updateViewLayout(drawingFrameView, lp);
                    return true;
            }
            return false;
        });

        drawingFrameView = frameLayout;
        wm.addView(drawingFrameView, lp);
    }

    private void hideDrawingFrame() {
        if (drawingFrameView != null) {
            try { wm.removeView(drawingFrameView); } catch (Exception ignore) {}
            drawingFrameView = null;
        }
        frameVisible = false;
    }

    private void showFrameSizeDialog() {
        // Build inline size adjustment overlay
        toast("Kéo góc dưới-phải của khung để resize");
    }

    // ─── Start Drawing ────────────────────────────────────────────────────────

    private void startDrawing() {
        if (sourceBitmap == null) { toast("Chọn ảnh trước!"); return; }

        // Update cfg from frame
        cfg.frameX = frameX;
        cfg.frameY = frameY;
        cfg.frameW = frameW;
        cfg.frameH = frameH;
        cfg.detailLevel = sbDetail.getProgress();
        cfg.brushSizePx = sbBrush.getProgress() + 1;
        cfg.delayMs = sbDelay.getProgress();
        cfg.ditherMode = spDither.getSelectedItemPosition();
        cfg.maxColors = 16;

        btnStart.setEnabled(false);
        btnPause.setEnabled(true);
        btnStop.setEnabled(true);
        tvStatus.setText("Đang vẽ...");
        progressBar.setProgress(0);

        drawEngine.start(sourceBitmap, cfg);
    }

    // ─── DrawEngine Callback ──────────────────────────────────────────────────

    private void setupDrawEngineCallback() {
        drawEngine.setCallback(new DrawEngine.DrawCallback() {
            @Override
            public void onProgress(int drawn, int total, int colorIdx, int totalColors) {
                int pct = total > 0 ? (drawn * 100 / total) : 0;
                if (progressBar != null) progressBar.setProgress(pct);
                if (tvProgress != null) tvProgress.setText(pct + "% | " + drawn + "/" + total + " px");
                if (tvStatus != null)
                    tvStatus.setText("Vẽ màu " + (colorIdx+1) + "/" + totalColors);
            }

            @Override
            public void onDone(long timeMs) {
                if (tvStatus != null) tvStatus.setText("HOÀN TẤT! (" + (timeMs/1000) + "s)");
                if (btnStart != null) btnStart.setEnabled(true);
                if (btnPause != null) { btnPause.setEnabled(false); btnPause.setText("⏸ TẠM DỪNG"); }
                if (btnStop  != null) btnStop.setEnabled(false);
                if (progressBar != null) progressBar.setProgress(100);
                if (tvProgress != null) tvProgress.setText("HOÀN TẤT!");
            }

            @Override
            public void onCancelled() {
                if (tvStatus != null) tvStatus.setText("Đã dừng");
                if (btnStart != null) btnStart.setEnabled(true);
                if (btnPause != null) { btnPause.setEnabled(false); btnPause.setText("⏸ TẠM DỪNG"); }
                if (btnStop  != null) btnStop.setEnabled(false);
            }

            @Override
            public void onColorChanged(int colorIndex, int color) {
                if (colorPreviewBox != null) colorPreviewBox.setBackgroundColor(color);
                if (tvColorInfo != null)
                    tvColorInfo.setText("HEX: #" + String.format("%06X", color & 0xFFFFFF)
                            + "  |  Màu " + (colorIndex+1));
            }
        });
    }

    // ─── Palette render ───────────────────────────────────────────────────────

    private void renderPalette() {
        if (paletteRow == null) return;
        paletteRow.removeAllViews();

        for (int c : detectedPalette) {
            int ac = ColorUtils.adjustHsv(c, cfg.hueShift, cfg.satAdj, cfg.valAdj,
                    cfg.vibrance, cfg.boostShadows);
            View chip = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(ac);
            bg.setCornerRadius(dp(5));
            bg.setStroke(dp(1), 0xFF2a2d38);
            chip.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            final int fc = ac;
            chip.setOnClickListener(v -> {
                if (colorPreviewBox != null) colorPreviewBox.setBackgroundColor(fc);
                if (tvColorInfo != null)
                    tvColorInfo.setText("HEX: #" + String.format("%06X", fc & 0xFFFFFF));
            });
            paletteRow.addView(chip, lp);
        }
        tvStatus.setText("Tìm thấy " + detectedPalette.size() + " màu chính");
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    public void setBitmap(Bitmap bmp) {
        sourceBitmap = bmp;
        if (tvStatus != null) {
            handler.post(() -> tvStatus.setText(
                    "Ảnh: " + bmp.getWidth() + "×" + bmp.getHeight() + " px"));
        }
    }

    // ─── UI Helpers ───────────────────────────────────────────────────────────

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String msg) {
        handler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private TextView tv(Context ctx, String text, int sp, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(sp);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(0xFFe8e9f0);
        return t;
    }

    private void setShadow(TextView t, int color) {
        t.setShadowLayer(12f, 0, 0, color);
    }

    private Button miniBtn(Context ctx, String text, int bgColor) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextColor(0xFFffffff);
        b.setTextSize(11);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(7));
        b.setBackground(bg);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private Button bigBtn(Context ctx, String text, int color) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextColor(0xFFffffff);
        b.setTextSize(12);
        b.setTypeface(null, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(9));
        b.setBackground(bg);
        b.setPadding(dp(6), dp(8), dp(6), dp(8));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private View separator(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(0xFF2a2d38);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        v.setLayoutParams(lp);
        return v;
    }

    private TextView sectionLabel(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(9);
        t.setTextColor(0xFF8a8d9e);
        t.setTypeface(null, Typeface.BOLD);
        t.setAllCaps(false);
        return t;
    }

    private LinearLayout row(Context ctx) {
        LinearLayout ll = new LinearLayout(ctx);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        return ll;
    }

    private SeekBar seekRow(Context ctx, LinearLayout parent, String label, int min, int max, int init) {
        LinearLayout row = row(ctx);
        TextView lbl = tv(ctx, label + ": ", 10, false);
        lbl.setTextColor(0xFF8a8d9e);
        SeekBar sb = new SeekBar(ctx);
        sb.setMax(max - min);
        sb.setProgress(init - min);
        final TextView val = tv(ctx, String.valueOf(init), 10, false);
        val.setTextColor(0xFF7c6bff);
        val.setMinWidth(dp(28));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                val.setText(String.valueOf(p + min));
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        row.addView(lbl, wrap());
        row.addView(sb, wrapWeight());
        row.addView(val, wrap());
        parent.addView(row, matchWrap(0, dp(2)));
        return sb;
    }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams matchWrap(int topMargin, int bottomMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = topMargin; lp.bottomMargin = bottomMargin;
        return lp;
    }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2, -2); }
    private LinearLayout.LayoutParams wrapWeight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }
    private int matchParent() { return LinearLayout.LayoutParams.MATCH_PARENT; }

    // Drag-to-move touch listener
    private View.OnTouchListener moveTouchListener(WindowManager.LayoutParams lp, View root) {
        return (v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = e.getRawX(); lastTouchY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x += (int)(e.getRawX() - lastTouchX);
                    lp.y += (int)(e.getRawY() - lastTouchY);
                    lastTouchX = e.getRawX(); lastTouchY = e.getRawY();
                    wm.updateViewLayout(root, lp);
                    return true;
            }
            return false;
        };
    }

    // Simple seek listener helper
    interface IntConsumer { void accept(int v); }
    private SeekBar.OnSeekBarChangeListener simpleSeek(IntConsumer onChanged) {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) { onChanged.accept(p); }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        };
    }
    }
