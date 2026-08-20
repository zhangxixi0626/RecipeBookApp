package com.owner.lynk10remote;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.atomic.AtomicBoolean;

public final class RemoteGuardService extends Service implements RemoteWsClient.Listener {
    static final String ACTION_START = "com.owner.lynk10remote.START";
    static final String ACTION_STOP = "com.owner.lynk10remote.STOP";
    static final String ACTION_BOOT_RECONNECT = "com.owner.lynk10remote.BOOT_RECONNECT";
    private static final int NOTIFICATION_ID = 8295;
    private static final String CHANNEL_ID = "lynk10_remote_guard";

    static volatile String lastStatus = "远程守候未启动";

    private AppSettings settings;
    private RemoteWsClient remoteClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean captureInProgress = new AtomicBoolean(false);
    private volatile boolean cameraArmed;

    @Override public void onCreate() {
        super.onCreate();
        settings = new AppSettings(this);
        createNotificationChannel();
        startNetworkForeground("正在准备远程守候");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            settings.setGuardEnabled(false);
            cameraArmed = false;
            Camera2SnapshotEngine engine = Camera2SnapshotEngine.getActiveEngine();
            if (engine != null) engine.stopPreviews();
            if (remoteClient != null) remoteClient.stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        settings.setGuardEnabled(true);
        if (ACTION_BOOT_RECONNECT.equals(action)) {
            cameraArmed = false;
            startNetworkForeground("车机已启动，网络守候恢复；相机需要打开应用重新布防");
        } else if (settings.cameraIds().size() != 4) {
            cameraArmed = false;
            startNetworkForeground("请先填写按前、后、左、右排序的4个Camera ID");
        } else {
            cameraArmed = startCameraForeground("四路相机已布防，正在连接服务器");
        }
        if (remoteClient != null) remoteClient.stop();
        remoteClient = new RemoteWsClient(settings, this);
        remoteClient.start();
        if (ACTION_BOOT_RECONNECT.equals(action)) {
            setStatus("网络守候正在恢复；打开应用点击开始守候后才能远程拍照");
        }
        return START_STICKY;
    }

    @Override public void onStatus(String status) {
        if (!cameraArmed && "远程守候在线".equals(status)) {
            setStatus("网络守候在线，相机尚未重新布防");
        } else {
            setStatus(status);
        }
    }

    @Override public void onCaptureRequest(String requestId) {
        if (!cameraArmed) {
            remoteClient.sendError(requestId, "相机尚未布防，请在车机打开应用并点击开始守候");
            return;
        }
        if (settings.cameraIds().size() != 4) {
            remoteClient.sendError(requestId, "请先填写按前、后、左、右排序的4个Camera ID");
            return;
        }
        if (!captureInProgress.compareAndSet(false, true)) {
            remoteClient.sendError(requestId, "上一次抓拍还没有完成");
            return;
        }
        PowerManager.WakeLock wakeLock = acquireShortWakeLock();
        setStatus("正在抓取车辆周围画面");
        mainHandler.post(() -> captureFromPreview(requestId, wakeLock, 0));
    }

    private void captureFromPreview(String requestId, PowerManager.WakeLock wakeLock, int attempt) {
        Camera2SnapshotEngine engine = Camera2SnapshotEngine.getActiveEngine();
        if (engine == null) {
            if (attempt == 0) launchPreviewActivity();
            if (attempt < 12) {
                mainHandler.postDelayed(() -> captureFromPreview(requestId, wakeLock, attempt + 1), 500);
            } else {
                finishCaptureError(requestId, wakeLock,
                        "EVCam预览界面没有启动，请在车机允许应用从后台打开界面");
            }
            return;
        }
        if (!engine.hasReadyPreviews()) {
            if (attempt == 0) {
                engine.startPreviews(settings.cameraIds(), this::setStatus);
                launchPreviewActivity();
            }
            if (attempt < 16) {
                mainHandler.postDelayed(() -> captureFromPreview(requestId, wakeLock, attempt + 1), 500);
            } else {
                finishCaptureError(requestId, wakeLock,
                        "四路EVCam预览未能在8秒内建立，当前就绪" + engine.readyCount() + "/4");
            }
            return;
        }
        engine.capture(settings.cameraIds(), result -> {
            try {
                if (result.hasFrames()) {
                    remoteClient.sendCapture(requestId, result);
                    setStatus(result.errors.isEmpty() ? "抓拍完成，远程守候在线" : "部分相机抓拍失败");
                } else {
                    remoteClient.sendError(requestId, String.join("；", result.errors));
                    setStatus("抓拍失败，请在车机查看原因");
                }
            } finally {
                captureInProgress.set(false);
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                if (cameraArmed) startCameraForeground(lastStatus);
                else startNetworkForeground(lastStatus);
            }
        });
    }

    private void finishCaptureError(String requestId, PowerManager.WakeLock wakeLock, String error) {
        remoteClient.sendError(requestId, error);
        setStatus("抓拍失败：" + error);
        captureInProgress.set(false);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void launchPreviewActivity() {
        try {
            Intent open = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
            setStatus("正在按EVCam方式唤起预览界面");
        } catch (Exception e) {
            setStatus("系统阻止后台打开预览界面：" + e.getClass().getSimpleName());
        }
    }

    @Override public boolean isCameraArmed() {
        return cameraArmed;
    }

    private boolean startCameraForeground(String text) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setStatus("没有相机权限，无法布防");
            return false;
        }
        Notification notification = buildNotification(text);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                int networkType = networkForegroundType();
                startForeground(NOTIFICATION_ID, notification,
                        networkType | ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, networkForegroundType());
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            cameraArmed = true;
            if (remoteClient != null) remoteClient.notifyArmedStateChanged();
            return true;
        } catch (Exception e) {
            cameraArmed = false;
            setStatus("相机布防失败，请保持应用在前台后重试：" + e.getClass().getSimpleName());
            return false;
        }
    }

    private void startNetworkForeground(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, networkForegroundType());
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private int networkForegroundType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING;
        }
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
    }

    private PowerManager.WakeLock acquireShortWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "Lynk10Remote:Capture");
            lock.acquire(90_000);
            return lock;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void setStatus(String status) {
        lastStatus = status;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(status));
        Intent update = new Intent("com.owner.lynk10remote.STATUS")
                .setPackage(getPackageName())
                .putExtra("status", status);
        sendBroadcast(update);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.owner.lynk10remote.R.drawable.ic_launcher)
                .setContentTitle("领克10远程监看")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_description));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    @Override public void onDestroy() {
        if (remoteClient != null) remoteClient.stop();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
