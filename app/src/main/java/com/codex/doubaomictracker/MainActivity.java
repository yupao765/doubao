package com.codex.doubaomictracker;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 1001;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));
        root.setBackgroundColor(0xFFF6F7FB);

        TextView title = new TextView(this);
        title.setText("豆包麦克风跟踪");
        title.setTextColor(0xFF111111);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView desc = new TextView(this);
        desc.setText("先完成授权，然后显示悬浮按钮。豆包聊天页保持在前台时，检测到人声会按住底部“按住说话”。");
        desc.setTextColor(0xFF555B66);
        desc.setTextSize(15);
        desc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
        descParams.setMargins(0, dp(14), 0, dp(22));
        root.addView(desc, descParams);

        statusView = new TextView(this);
        statusView.setTextColor(0xFF222222);
        statusView.setTextSize(15);
        statusView.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, 0, 0, dp(18));
        root.addView(statusView, statusParams);

        root.addView(createButton("授予麦克风权限", v -> requestAudioPermission()));
        root.addView(createButton("打开辅助功能设置", v -> openAccessibilitySettings()));
        root.addView(createButton("显示悬浮按钮", v -> showFloatingControls()));

        return root;
    }

    private Button createButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.setMargins(0, 0, 0, dp(12));
        button.setLayoutParams(params);
        return button;
    }

    private void requestAudioPermission() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQUEST_AUDIO);
        }
        updateStatus();
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void showFloatingControls() {
        if (!hasAudioPermission()) {
            Toast.makeText(this, "请先授予麦克风权限", Toast.LENGTH_SHORT).show();
            requestAudioPermission();
            return;
        }
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先在辅助功能中启用本应用", Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
            return;
        }

        DoubaoAccessibilityService service = DoubaoAccessibilityService.getInstance();
        if (service != null) {
            service.showOverlay();
            Toast.makeText(this, "悬浮按钮已显示，可以切回豆包", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "辅助功能刚开启时可能要等几秒，请稍后再试", Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isAccessibilityEnabled() {
        String expected = new ComponentName(this, DoubaoAccessibilityService.class).flattenToString();
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabled == null) {
            return false;
        }
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            if (expected.equalsIgnoreCase(splitter.next())) {
                return true;
            }
        }
        return DoubaoAccessibilityService.isRunning();
    }

    private void updateStatus() {
        if (statusView == null) {
            return;
        }
        String audio = hasAudioPermission() ? "已开启" : "未开启";
        String accessibility = isAccessibilityEnabled() ? "已开启" : "未开启";
        statusView.setText("麦克风：" + audio + "\n辅助功能：" + accessibility);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
