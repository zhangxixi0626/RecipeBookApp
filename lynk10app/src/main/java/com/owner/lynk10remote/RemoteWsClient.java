package com.owner.lynk10remote;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

final class RemoteWsClient {
    interface Listener {
        void onStatus(String status);
        void onCaptureRequest(String requestId);
        boolean isCameraArmed();
    }

    private static final long[] RETRY_DELAYS_MS = {5_000, 10_000, 20_000, 40_000, 60_000};
    private static final long HEARTBEAT_INTERVAL_MS = 30_000;
    private final AppSettings settings;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private WebSocket socket;
    private boolean shouldRun;
    private int retryIndex;
    private final Runnable heartbeatTask = new Runnable() {
        @Override public void run() {
            sendHeartbeat();
            if (shouldRun) mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    RemoteWsClient(AppSettings settings, Listener listener) {
        this.settings = settings;
        this.listener = listener;
    }

    synchronized void start() {
        shouldRun = true;
        retryIndex = 0;
        connect();
    }

    synchronized void stop() {
        shouldRun = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (socket != null) socket.close(1000, "vehicle service stopped");
        socket = null;
        listener.onStatus("远程守候已停止");
    }

    private synchronized void connect() {
        if (!shouldRun) return;
        String url = settings.serverUrl();
        if (!url.startsWith("wss://") && !url.startsWith("ws://127.0.0.1") && !url.startsWith("ws://localhost")) {
            listener.onStatus("服务器地址必须使用wss://；本机调试可用ws://127.0.0.1");
            scheduleReconnect();
            return;
        }
        if (settings.token().isEmpty()) {
            listener.onStatus("还没有填写配对密钥");
            return;
        }
        listener.onStatus("正在连接远程服务");
        Request request = new Request.Builder().url(url).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (webSocket != socket) return;
                retryIndex = 0;
                JsonObject hello = new JsonObject();
                hello.addProperty("type", "vehicle_hello");
                hello.addProperty("deviceId", settings.deviceId());
                hello.addProperty("token", settings.token());
                hello.addProperty("platform", "lynk10ev-sa8295p");
                hello.addProperty("appVersion", BuildConfig.VERSION_NAME);
                hello.addProperty("armed", listener.isCameraArmed());
                webSocket.send(hello.toString());
                listener.onStatus("服务器已连接，正在验证车辆");
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                if (webSocket != socket) return;
                try {
                    JsonObject message = JsonParser.parseString(text).getAsJsonObject();
                    String type = message.has("type") ? message.get("type").getAsString() : "";
                    if ("vehicle_ready".equals(type)) {
                        listener.onStatus("远程守候在线");
                        mainHandler.removeCallbacks(heartbeatTask);
                        mainHandler.post(heartbeatTask);
                    } else if ("capture".equals(type)) {
                        String requestId = message.has("requestId")
                                ? message.get("requestId").getAsString()
                                : String.valueOf(System.currentTimeMillis());
                        listener.onCaptureRequest(requestId);
                    } else if ("ping".equals(type)) {
                        sendSimple("pong", null, null);
                    }
                } catch (Exception ignored) {
                    sendError("unknown", "命令格式不正确");
                }
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                if (webSocket != socket) return;
                webSocket.close(code, reason);
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (webSocket != socket) return;
                listener.onStatus("连接已断开，准备重连");
                scheduleReconnect();
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (webSocket != socket) return;
                listener.onStatus("网络连接失败，稍后重试");
                scheduleReconnect();
            }
        });
    }

    synchronized void sendCapture(String requestId, CaptureBundle bundle) {
        if (socket == null) return;
        for (CaptureBundle.Frame frame : bundle.frames) {
            JsonObject message = base("capture_frame", requestId);
            message.addProperty("cameraId", frame.cameraId);
            message.addProperty("position", frame.position);
            message.addProperty("capturedAt", bundle.capturedAt);
            message.addProperty("width", frame.width);
            message.addProperty("height", frame.height);
            message.addProperty("mime", "image/jpeg");
            message.addProperty("imageBase64", Base64.encodeToString(frame.jpeg, Base64.NO_WRAP));
            socket.send(message.toString());
        }
        JsonObject done = base("capture_complete", requestId);
        done.addProperty("capturedAt", bundle.capturedAt);
        done.addProperty("frameCount", bundle.frames.size());
        done.addProperty("errors", String.join("；", bundle.errors));
        socket.send(done.toString());
    }

    synchronized void sendError(String requestId, String error) {
        sendSimple("capture_error", requestId, error);
    }

    synchronized void notifyArmedStateChanged() {
        sendHeartbeat();
    }

    private synchronized void sendHeartbeat() {
        if (socket == null) return;
        JsonObject message = base("heartbeat", null);
        message.addProperty("armed", listener.isCameraArmed());
        message.addProperty("sentAt", System.currentTimeMillis());
        socket.send(message.toString());
    }

    private void sendSimple(String type, String requestId, String error) {
        if (socket == null) return;
        JsonObject message = base(type, requestId);
        if (error != null) message.addProperty("error", error);
        socket.send(message.toString());
    }

    private JsonObject base(String type, String requestId) {
        JsonObject message = new JsonObject();
        message.addProperty("type", type);
        message.addProperty("deviceId", settings.deviceId());
        if (requestId != null) message.addProperty("requestId", requestId);
        return message;
    }

    private synchronized void scheduleReconnect() {
        if (!shouldRun) return;
        long delay = RETRY_DELAYS_MS[Math.min(retryIndex, RETRY_DELAYS_MS.length - 1)];
        retryIndex++;
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(this::connect, delay);
    }
}
