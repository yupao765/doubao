package com.codex.doubaomictracker;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final int EXPORT_DIAGNOSTICS = 1002;
    private static final String DOUBAO_PACKAGE = "com.larus.nova";

    private TextView permissionStatusView;
    private TextView sensitivityValueView;
    private TextView endingVolumeValueView;
    private TextView releaseDelayValueView;
    private Switch automaticEndingSwitch;
    private Button setupButton;
    private Button overlayButton;
    private Button doubaoButton;
    private boolean openAccessibilityAfterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();

        if (isAccessibilityEnabled()) {
            DoubaoAccessibilityService service = DoubaoAccessibilityService.getInstance();
            if (service != null) {
                service.showOverlay();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        updateStatus();
        if (hasAudioPermission() && openAccessibilityAfterPermission) {
            openAccessibilityAfterPermission = false;
            openAccessibilitySettings();
        }
    }

    private View createContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(0xFFF5F7FA);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(34), dp(22), dp(30));
        scrollView.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("豆包语音跟随", 27, 0xFF111827);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView subtitle = text("3.0.0", 15, 0xFF596273);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.setMargins(0, dp(10), 0, dp(24));
        root.addView(subtitle, subtitleParams);

        root.addView(sectionTitle("首次设置"), matchWrap());

        permissionStatusView = text("", 16, 0xFF1F2937);
        permissionStatusView.setLineSpacing(dp(5), 1f);
        permissionStatusView.setPadding(dp(16), dp(14), dp(16), dp(14));
        permissionStatusView.setBackground(background(0xFFFFFFFF, dp(8), 0xFFE3E7EE));
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.setMargins(0, dp(10), 0, dp(12));
        root.addView(permissionStatusView, statusParams);

        setupButton = actionButton("完成首次设置", true, v -> completeSetup());
        root.addView(setupButton);

        overlayButton = actionButton("显示悬浮控制", false, v -> showFloatingControls());
        root.addView(overlayButton);

        root.addView(sectionTitle("麦克风灵敏度"), withTopMargin(dp(22)));

        sensitivityValueView = text("", 18, 0xFF111827);
        LinearLayout.LayoutParams sensitivityParams = matchWrap();
        sensitivityParams.setMargins(0, dp(10), 0, 0);
        root.addView(sensitivityValueView, sensitivityParams);

        SeekBar sensitivityBar = new SeekBar(this);
        sensitivityBar.setMax(TrackerSettings.MAX_SENSITIVITY - TrackerSettings.MIN_SENSITIVITY);
        sensitivityBar.setProgress(TrackerSettings.getSensitivity(this) - TrackerSettings.MIN_SENSITIVITY);
        sensitivityBar.setPadding(0, dp(8), 0, dp(4));
        sensitivityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int level = progress + TrackerSettings.MIN_SENSITIVITY;
                TrackerSettings.setSensitivity(MainActivity.this, level);
                updateSensitivityLabel(level);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Toast.makeText(MainActivity.this, "灵敏度已保存，跟踪中也会立即生效", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(sensitivityBar, matchWrap());

        root.addView(sectionTitle("说完判定"), withTopMargin(dp(22)));

        automaticEndingSwitch = new Switch(this);
        automaticEndingSwitch.setText("自动适应环境噪音");
        automaticEndingSwitch.setTextSize(16);
        automaticEndingSwitch.setTextColor(0xFF111827);
        automaticEndingSwitch.setChecked(TrackerSettings.isAutomaticEndingEnabled(this));
        automaticEndingSwitch.setPadding(0, dp(8), 0, dp(4));
        automaticEndingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            TrackerSettings.setAutomaticEndingEnabled(MainActivity.this, isChecked);
            updateEndingVolumeLabel(TrackerSettings.getEndingVolumeTenthsPercent(MainActivity.this));
            Toast.makeText(
                    MainActivity.this,
                    isChecked ? "已开启自动判停" : "已改为手动判停",
                    Toast.LENGTH_SHORT
            ).show();
        });
        root.addView(automaticEndingSwitch, matchWrap());

        endingVolumeValueView = text("", 17, 0xFF111827);
        LinearLayout.LayoutParams endingVolumeParams = matchWrap();
        endingVolumeParams.setMargins(0, dp(14), 0, 0);
        root.addView(endingVolumeValueView, endingVolumeParams);

        SeekBar endingVolumeBar = new SeekBar(this);
        endingVolumeBar.setMax(
                TrackerSettings.MAX_ENDING_VOLUME_TENTHS_PERCENT
                        - TrackerSettings.MIN_ENDING_VOLUME_TENTHS_PERCENT
        );
        endingVolumeBar.setProgress(
                TrackerSettings.getEndingVolumeTenthsPercent(this)
                        - TrackerSettings.MIN_ENDING_VOLUME_TENTHS_PERCENT
        );
        endingVolumeBar.setPadding(0, dp(8), 0, dp(4));
        endingVolumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + TrackerSettings.MIN_ENDING_VOLUME_TENTHS_PERCENT;
                TrackerSettings.setEndingVolumeTenthsPercent(MainActivity.this, value);
                updateEndingVolumeLabel(value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Toast.makeText(MainActivity.this, "结束音量已保存", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(endingVolumeBar, matchWrap());

        releaseDelayValueView = text("", 17, 0xFF111827);
        LinearLayout.LayoutParams releaseDelayParams = matchWrap();
        releaseDelayParams.setMargins(0, dp(16), 0, 0);
        root.addView(releaseDelayValueView, releaseDelayParams);

        SeekBar releaseDelayBar = new SeekBar(this);
        releaseDelayBar.setMax(
                (TrackerSettings.MAX_RELEASE_DELAY_MS - TrackerSettings.MIN_RELEASE_DELAY_MS)
                        / TrackerSettings.RELEASE_DELAY_STEP_MS
        );
        releaseDelayBar.setProgress(
                (TrackerSettings.getReleaseDelayMs(this) - TrackerSettings.MIN_RELEASE_DELAY_MS)
                        / TrackerSettings.RELEASE_DELAY_STEP_MS
        );
        releaseDelayBar.setPadding(0, dp(8), 0, dp(4));
        releaseDelayBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = TrackerSettings.MIN_RELEASE_DELAY_MS
                        + progress * TrackerSettings.RELEASE_DELAY_STEP_MS;
                TrackerSettings.setReleaseDelayMs(MainActivity.this, value);
                updateReleaseDelayLabel(value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Toast.makeText(MainActivity.this, "松手时间已保存", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(releaseDelayBar, matchWrap());

        root.addView(sectionTitle("开始使用"), withTopMargin(dp(22)));

        doubaoButton = actionButton("打开豆包", true, v -> openDoubao());
        root.addView(doubaoButton);

        root.addView(actionButton("导出诊断记录", false, v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.setType("text/plain");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_TITLE, "doubao-diagnostics.txt");
            startActivityForResult(intent, EXPORT_DIAGNOSTICS);
        }));

        updateSensitivityLabel(TrackerSettings.getSensitivity(this));
        updateEndingVolumeLabel(TrackerSettings.getEndingVolumeTenthsPercent(this));
        updateReleaseDelayLabel(TrackerSettings.getReleaseDelayMs(this));
        updateStatus();
        return scrollView;
    }

    private void completeSetup() {
        if (!hasAudioPermission()) {
            openAccessibilityAfterPermission = true;
            requestRequiredPermissions();
            return;
        }
        if (!isAccessibilityEnabled()) {
            openAccessibilitySettings();
            return;
        }
        showFloatingControls();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_DIAGNOSTICS || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        DoubaoAccessibilityService service = DoubaoAccessibilityService.getInstance();
        String log = service == null ? "服务尚未连接，无诊断记录" : service.getDiagnostics();
        try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
            if (output == null) throw new java.io.IOException("无法打开文件");
            output.write(log.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "诊断记录已导出", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void requestRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (permissions.isEmpty()) {
            updateStatus();
            return;
        }
        requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "找到“豆包语音跟随”，打开后返回即可", Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "系统没有提供无障碍设置入口", Toast.LENGTH_LONG).show();
        }
    }

    private void showFloatingControls() {
        if (!hasAudioPermission()) {
            completeSetup();
            return;
        }
        if (!isAccessibilityEnabled()) {
            openAccessibilitySettings();
            return;
        }

        DoubaoAccessibilityService service = DoubaoAccessibilityService.getInstance();
        if (service == null) {
            Toast.makeText(this, "服务正在连接，请等两秒再点一次", Toast.LENGTH_LONG).show();
            return;
        }
        service.showOverlay();
        Toast.makeText(this, "悬浮控制已显示", Toast.LENGTH_SHORT).show();
    }

    private void openDoubao() {
        if (!hasAudioPermission() || !isAccessibilityEnabled()) {
            completeSetup();
            return;
        }
        DoubaoAccessibilityService service = DoubaoAccessibilityService.getInstance();
        if (service != null) {
            service.showOverlay();
        }
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(DOUBAO_PACKAGE);
        if (launchIntent == null) {
            Toast.makeText(this, "没有找到豆包应用，请先安装或手动打开豆包", Toast.LENGTH_LONG).show();
            return;
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launchIntent);
    }

    private boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager != null) {
            List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            );
            for (AccessibilityServiceInfo info : services) {
                if (info.getResolveInfo() != null
                        && info.getResolveInfo().serviceInfo != null
                        && getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) {
                    return true;
                }
            }
        }
        return DoubaoAccessibilityService.isRunning();
    }

    private void updateStatus() {
        if (permissionStatusView == null) {
            return;
        }
        boolean audioEnabled = hasAudioPermission();
        boolean accessibilityEnabled = isAccessibilityEnabled();
        permissionStatusView.setText(
                (audioEnabled ? "✓" : "○") + " 麦克风权限：" + (audioEnabled ? "已开启" : "未开启")
                        + "\n"
                        + (accessibilityEnabled ? "✓" : "○") + " 无障碍服务：" + (accessibilityEnabled ? "已开启" : "未开启")
        );

        boolean ready = audioEnabled && accessibilityEnabled;
        setupButton.setText(ready ? "首次设置已完成" : "完成首次设置");
        overlayButton.setEnabled(ready);
        doubaoButton.setEnabled(ready);
    }

    private void updateSensitivityLabel(int level) {
        if (sensitivityValueView == null) {
            return;
        }
        String description;
        if (level <= 3) {
            description = "低，减少误触发";
        } else if (level <= 7) {
            description = "中等";
        } else {
            description = "高，小声音也容易触发";
        }
        sensitivityValueView.setText("当前：" + level + " / 10（" + description + "）");
    }

    private void updateEndingVolumeLabel(int tenthsPercent) {
        if (endingVolumeValueView == null) {
            return;
        }
        String prefix = "结束音量设置：";
        endingVolumeValueView.setText(
                prefix
                        + String.format(Locale.CHINA, "%.1f%%", tenthsPercent / 10f)
        );
    }

    private void updateReleaseDelayLabel(int milliseconds) {
        if (releaseDelayValueView == null) {
            return;
        }
        releaseDelayValueView.setText(
                "低音量持续多久松开："
                        + String.format(Locale.CHINA, "%.1f 秒", milliseconds / 1000f)
        );
    }

    private TextView sectionTitle(String value) {
        return text(value, 18, 0xFF111827);
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    private Button actionButton(String label, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(primary ? Color.WHITE : 0xFF174EA6);
        button.setGravity(Gravity.CENTER);
        button.setBackground(background(primary ? 0xFF176BFF : 0xFFFFFFFF, dp(8), primary ? 0xFF176BFF : 0xFFB8C7DF));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.setMargins(0, 0, 0, dp(10));
        button.setLayoutParams(params);
        return button;
    }

    private GradientDrawable background(int fill, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), border);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams withTopMargin(int margin) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, margin, 0, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
