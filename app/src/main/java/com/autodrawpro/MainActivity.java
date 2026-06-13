package com.autodrawpro;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.*;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final int REQ_IMAGE = 1001;
    private static final int REQ_OVERLAY = 1002;

    public static Bitmap selectedBitmap = null;
    public static int drawDetail = 50;

    private ImageView imgPreview;
    private TextView tvImageInfo, tvDetailVal;
    private SeekBar seekDetail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        imgPreview  = findViewById(R.id.imgPreview);
        tvImageInfo = findViewById(R.id.tvImageInfo);
        tvDetailVal = findViewById(R.id.tvDetailVal);
        seekDetail  = findViewById(R.id.seekDetail);

        seekDetail.setProgress(drawDetail);
        tvDetailVal.setText("Nét vẽ: " + drawDetail);
        seekDetail.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                drawDetail = p;
                tvDetailVal.setText("Nét vẽ: " + p);
                AutoDrawService.instance_drawDetail = p;
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        findViewById(R.id.btnChooseImage).setOnClickListener(v -> pickImage());
        findViewById(R.id.btnOverlayPerm).setOnClickListener(v -> requestOverlayPermission());
        findViewById(R.id.btnAccessibility).setOnClickListener(v -> openAccessibilitySettings());
        findViewById(R.id.btnOpenMenu).setOnClickListener(v -> openFloatingMenu());
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, REQ_IMAGE);
    }

    private void requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
        } else {
            toast("Đã có quyền cửa sổ nổi ✓");
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            toast("Lỗi mở cài đặt quyền!");
        }
    }

    private void openFloatingMenu() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Hãy cấp quyền cửa sổ nổi trước!"); return;
        }
        if (selectedBitmap == null) {
            toast("Hãy chọn ảnh trước!"); return;
        }
        if (AutoDrawService.getInstance() == null) {
            toast("Hãy bật Accessibility Service trước!"); return;
        }
        AutoDrawService.getInstance().showFloatingMenu();
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK && data != null) {
            try {
                Uri uri = data.getData();
                InputStream is = getContentResolver().openInputStream(uri);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, opts);
                is.close();
                int maxPx = 1200, sample = 1;
                while (opts.outWidth/sample > maxPx || opts.outHeight/sample > maxPx) sample *= 2;
                is = getContentResolver().openInputStream(uri);
                opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                is.close();
                if (bmp != null) {
                    selectedBitmap = bmp;
                    if (AutoDrawService.getInstance() != null)
                        AutoDrawService.getInstance().setBitmap(bmp);
                    imgPreview.setImageBitmap(bmp);
                    imgPreview.setVisibility(android.view.View.VISIBLE);
                    tvImageInfo.setText("Ảnh: " + bmp.getWidth() + "×" + bmp.getHeight() + " px");
                    toast("Đã tải ảnh: " + bmp.getWidth() + "×" + bmp.getHeight());
                }
            } catch (Exception e) { toast("Lỗi: " + e.getMessage()); }
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
                                    }
