package com.owner.lynk10remote;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.TextureView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends AppCompatActivity {
    private static final int REQUEST_PERMISSIONS = 100;

    private AppSettings settings;
    private Camera2SnapshotEngine cameraEngine;
    private WebDavUploader webDavUploader;
    private EditText serverField;
    private EditText deviceField;
    private EditText tokenField;
    private EditText cameraIdsField;
    private EditText webDavUrlField;
    private EditText webDavUserField;
    private EditText webDavPasswordField;
    private EditText webDavFolderField;
    private TextView statusView;
    private TextView reportView;
    private final List<TextureView> previews = new ArrayList<>();
    private String lastProbe = "尚未探测";
    private boolean autoGuardAttempted;
    private boolean startAfterPermission;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            if (status != null) statusView.setText(status);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new AppSettings(this);
        cameraEngine = new Camera2SnapshotEngine(this);
        webDavUploader = new WebDavUploader(settings);
        setContentView(buildUi());
        loadSettings();
        cameraEngine.attachPreviewViews(previews);
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        if (autoGuardAttempted) return;
        autoGuardAttempted = true;
        statusView.postDelayed(this::startGuardAutomatically, 400);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(22), dp(28), dp(28));
        scroll.addView(root);

        TextView title = text("领克10 EV 远程监看", 26);
        title.setTextColor(0xff155e63);
        root.addView(title);
        TextView subtitle = text("配置完成后，打开应用会自动开始守候；只抓当前画面，不做行车记录。", 15);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(columns, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        columns.addView(form, new LinearLayout.LayoutParams(0, -2, 1f));

        serverField = field("远程服务器，例如 wss://example.com/ws/vehicle", InputType.TYPE_TEXT_VARIATION_URI);
        deviceField = field("车辆编号，例如 lynk10ev-01", InputType.TYPE_CLASS_TEXT);
        tokenField = field("车机连接密钥（VEHICLE_TOKEN）",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        cameraIdsField = field("Camera ID顺序：前,后,左,右；可先试EVCam的 2,1,3,0", InputType.TYPE_CLASS_TEXT);
        form.addView(label("远程服务器")); form.addView(serverField);
        form.addView(label("车辆编号")); form.addView(deviceField);
        form.addView(label("车机连接密钥")); form.addView(tokenField);
        form.addView(label("相机ID")); form.addView(cameraIdsField);

        webDavUrlField = field("https://dav.example.com/remote.php/dav/files/user/",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        webDavUserField = field("WebDAV账号", InputType.TYPE_CLASS_TEXT);
        webDavPasswordField = field("WebDAV密码或应用密码",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        webDavFolderField = field("Lynk10EV/camera-probe", InputType.TYPE_CLASS_TEXT);
        form.addView(label("WebDAV地址")); form.addView(webDavUrlField);
        form.addView(label("WebDAV账号")); form.addView(webDavUserField);
        form.addView(label("WebDAV密码")); form.addView(webDavPasswordField);
        form.addView(label("报告目标文件夹")); form.addView(webDavFolderField);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(14), 0, dp(8));
        form.addView(buttons);
        addButton(buttons, "保存配置", v -> saveSettings());
        addButton(buttons, "开始守候", v -> startGuard());
        addButton(buttons, "停止", v -> stopGuard());

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        form.addView(tools);
        addButton(tools, "探测并上传", v -> probeCameras());
        addButton(tools, "本机试拍", v -> testCapture());
        addButton(tools, "复制报告", v -> copyProbe());

        LinearLayout webDavTools = new LinearLayout(this);
        webDavTools.setOrientation(LinearLayout.HORIZONTAL);
        webDavTools.setPadding(0, dp(6), 0, 0);
        form.addView(webDavTools);
        addButton(webDavTools, "测试WebDAV", v -> testWebDav());
        addButton(webDavTools, "省电白名单", v -> requestBatteryWhitelist());
        addButton(webDavTools, "后台唤起权限", v -> requestOverlayPermission());

        statusView = text(RemoteGuardService.lastStatus, 16);
        statusView.setTextColor(0xff155e63);
        statusView.setPadding(0, dp(14), 0, dp(10));
        form.addView(statusView);

        reportView = text("点“探测相机”读取领克10 EV的Camera2入口。", 13);
        reportView.setTextIsSelectable(true);
        reportView.setPadding(dp(12), dp(12), dp(12), dp(12));
        reportView.setBackgroundColor(0xffeef3f3);
        form.addView(reportView, new LinearLayout.LayoutParams(-1, dp(250)));

        GridLayout previewGrid = new GridLayout(this);
        previewGrid.setColumnCount(2);
        previewGrid.setPadding(dp(24), 0, 0, 0);
        columns.addView(previewGrid, new LinearLayout.LayoutParams(0, -2, 1f));
        for (int i = 0; i < 4; i++) {
            TextureView preview = new TextureView(this);
            preview.setBackgroundColor(0xffdfe8e8);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = dp(300);
            params.height = dp(180);
            params.setMargins(dp(6), dp(6), dp(6), dp(6));
            previewGrid.addView(preview, params);
            previews.add(preview);
        }
        return scroll;
    }

    private void loadSettings() {
        serverField.setText(settings.serverUrl());
        deviceField.setText(settings.deviceId());
        tokenField.setText(settings.token());
        cameraIdsField.setText(settings.cameraIdsText());
        webDavUrlField.setText(settings.webDavUrl());
        webDavUserField.setText(settings.webDavUser());
        webDavPasswordField.setText(settings.webDavPassword());
        webDavFolderField.setText(settings.webDavFolder());
    }

    private void saveSettings() {
        saveSettings(true);
    }

    private void saveSettings(boolean showToast) {
        settings.save(serverField.getText().toString(), deviceField.getText().toString(),
                tokenField.getText().toString(), cameraIdsField.getText().toString(),
                webDavUrlField.getText().toString(), webDavUserField.getText().toString(),
                webDavPasswordField.getText().toString(), webDavFolderField.getText().toString());
        if (showToast) toast("配置已保存，下次打开会自动开始守候");
    }

    private void startGuard() {
        saveSettings(false);
        if (!hasCameraPermission()) {
            startAfterPermission = true;
            requestRequiredPermissions();
            toast("先允许相机权限，再开始守候");
            return;
        }
        startGuardService(false);
    }

    private void startGuardAutomatically() {
        if (!hasGuardConfiguration()) {
            statusView.setText("请先配置服务器、车辆编号、车机连接密钥和4个Camera ID");
            return;
        }
        if (!hasCameraPermission()) {
            startAfterPermission = true;
            statusView.setText("允许相机权限后会自动开始守候");
            requestRequiredPermissions();
            return;
        }
        startGuardService(true);
    }

    private boolean hasGuardConfiguration() {
        return !settings.serverUrl().trim().isEmpty()
                && !settings.deviceId().trim().isEmpty()
                && !settings.token().trim().isEmpty()
                && settings.cameraIds().size() == 4;
    }

    private void startGuardService(boolean automatic) {
        Intent intent = new Intent(this, RemoteGuardService.class).setAction(RemoteGuardService.ACTION_START);
        ContextCompat.startForegroundService(this, intent);
        cameraEngine.startPreviews(settings.cameraIds(), status -> runOnUiThread(() ->
                statusView.setText(status)));
        statusView.setText(automatic ? "已自动开始守候，正在连接服务器" : "正在启动远程守候");
    }

    private void stopGuard() {
        Intent intent = new Intent(this, RemoteGuardService.class).setAction(RemoteGuardService.ACTION_STOP);
        startService(intent);
        cameraEngine.stopPreviews();
        statusView.setText("远程守候已停止");
    }

    private void probeCameras() {
        if (!hasCameraPermission()) {
            requestRequiredPermissions();
            toast("先允许相机权限");
            return;
        }
        saveSettings();
        statusView.setText("正在读取Camera2入口");
        new Thread(() -> {
            String report = cameraEngine.probe();
            List<String> ids = cameraEngine.availableCameraIds();
            String fullReport = "设备：领克10 EV / Snapdragon 8295\n应用版本："
                    + BuildConfig.VERSION_NAME + "\n探测时间：" + new java.util.Date()
                    + "\n\n" + report;
            runOnUiThread(() -> {
                lastProbe = fullReport;
                reportView.setText(lastProbe);
                statusView.setText("发现" + ids.size() + "个入口，正在按EVCam方式打开前4路预览");
            });
            cameraEngine.captureAllAvailableForProbe(captures -> {
                if (!settings.hasWebDavConfig()) {
                    runOnUiThread(() -> statusView.setText(captures.hasFrames()
                            ? "EVCam预览取流成功：" + captures.frames.size() + "路"
                            : "预览取流失败：" + String.join("；", captures.errors)));
                    return;
                }
                runOnUiThread(() -> statusView.setText("诊断完成，正在上传报告和样图"));
                webDavUploader.uploadProbeAsync(fullReport, captures, (success, message) ->
                        runOnUiThread(() -> {
                            statusView.setText(message);
                            if (!success) toast("上传失败，请检查WebDAV设置");
                        }));
            });
        }, "Lynk10-Probe").start();
    }

    private void testWebDav() {
        saveSettings();
        statusView.setText("正在测试WebDAV");
        webDavUploader.testAsync((success, message) -> runOnUiThread(() -> {
            statusView.setText(message);
            toast(success ? "WebDAV连接成功" : "WebDAV连接失败");
        }));
    }

    private void testCapture() {
        if (!hasCameraPermission()) {
            requestRequiredPermissions();
            return;
        }
        saveSettings();
        statusView.setText("正在按EVCam方式建立预览流");
        cameraEngine.startPreviews(settings.cameraIds(), status -> runOnUiThread(() ->
                statusView.setText(status)));
        statusView.postDelayed(() -> cameraEngine.capture(settings.cameraIds(), result ->
                runOnUiThread(() -> {
                    if (result.hasFrames()) {
                        statusView.setText("本机抓拍完成：" + result.frames.size() + "张"
                                + (result.errors.isEmpty() ? "" : "；" + String.join("；", result.errors)));
                    } else {
                        statusView.setText("没有抓到画面：" + String.join("；", result.errors));
                    }
                })), 4_000);
    }

    private void copyProbe() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("lynk10-camera-probe", lastProbe));
        toast("探测报告已复制");
    }

    private void requestBatteryWhitelist() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("已经在省电白名单里");
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            toast("已经允许后台唤起预览界面");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast("请在系统权限中允许悬浮窗/后台弹出界面");
        }
    }

    private void requestRequiredPermissions() {
        List<String> missing = new ArrayList<>();
        if (!hasCameraPermission()) missing.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) ActivityCompat.requestPermissions(this,
                missing.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                                     @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && hasCameraPermission()) {
            if (startAfterPermission && hasGuardConfiguration()) {
                startAfterPermission = false;
                statusView.postDelayed(() -> startGuardService(true), 250);
            } else {
                statusView.setText("相机权限已允许，可以开始探测");
            }
        }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter("com.owner.lynk10remote.STATUS");
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        statusView.setText(RemoteGuardService.lastStatus);
    }

    @Override protected void onStop() {
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) { }
        super.onStop();
    }

    @Override protected void onDestroy() {
        cameraEngine.close();
        super.onDestroy();
    }

    private TextView text(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 13);
        view.setPadding(0, dp(8), 0, dp(3));
        return view;
    }

    private EditText field(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setTextSize(14);
        return field;
    }

    private void addButton(LinearLayout row, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        row.addView(button, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
