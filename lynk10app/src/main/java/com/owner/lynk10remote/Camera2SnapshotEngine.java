package com.owner.lynk10remote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EVCam-style Camera2 preview pipeline.
 *
 * Cameras remain connected to TextureView/SurfaceTexture repeating preview streams. A remote
 * snapshot copies the latest preview bitmap instead of opening a one-shot ImageReader session.
 * This follows the proven EVCam path and avoids re-querying automotive HAL metadata per capture.
 */
final class Camera2SnapshotEngine implements AutoCloseable {
    interface Callback {
        void onComplete(CaptureBundle result);
    }

    interface PreviewListener {
        void onStatus(String status);
    }

    private static final int MAX_LONG_EDGE = 1600;
    private static final int JPEG_QUALITY = 82;
    private static final String[] POSITIONS = {"front", "rear", "left", "right"};
    private static volatile Camera2SnapshotEngine activeEngine;

    private final Context context;
    private final CameraManager cameraManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, CameraCharacteristics> characteristicsCache = new ConcurrentHashMap<>();
    private final List<TextureView> previewViews = new ArrayList<>();
    private final List<PreviewSlot> slots = new ArrayList<>();
    private int generation;

    Camera2SnapshotEngine(Context context) {
        this.context = context.getApplicationContext();
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        activeEngine = this;
    }

    static Camera2SnapshotEngine getActiveEngine() {
        return activeEngine;
    }

    void attachPreviewViews(List<TextureView> views) {
        runOnMain(() -> {
            previewViews.clear();
            previewViews.addAll(views.subList(0, Math.min(4, views.size())));
        });
    }

    String probe() {
        if (!hasCameraPermission()) return "没有相机权限，请先在车机上允许。";
        if (cameraManager == null) return "CameraManager不可用。";
        StringBuilder out = new StringBuilder();
        try {
            String[] ids = cameraManager.getCameraIdList();
            out.append("发现 ").append(ids.length).append(" 个Camera2入口\n");
            for (String id : ids) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                characteristicsCache.put(id, c);
                Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                out.append("\nID ").append(id)
                        .append("  facing=").append(facingName(facing))
                        .append("  level=").append(levelName(level))
                        .append("\n  capabilities=").append(caps == null ? "[]" : Arrays.toString(caps));
                if (map == null) {
                    out.append("\n  没有StreamConfigurationMap\n");
                    continue;
                }
                appendSizes(out, "JPEG", map.getOutputSizes(ImageFormat.JPEG));
                appendSizes(out, "YUV", map.getOutputSizes(ImageFormat.YUV_420_888));
                appendSizes(out, "PRIVATE", map.getOutputSizes(ImageFormat.PRIVATE));
                out.append('\n');
            }
        } catch (Exception e) {
            out.append("读取失败：").append(e.getClass().getSimpleName())
                    .append(" - ").append(e.getMessage());
        }
        return out.toString();
    }

    List<String> availableCameraIds() {
        try {
            if (cameraManager == null) return new ArrayList<>();
            return Arrays.asList(cameraManager.getCameraIdList());
        } catch (CameraAccessException e) {
            return new ArrayList<>();
        }
    }

    void startPreviews(List<String> requestedIds, PreviewListener listener) {
        List<String> ids = new ArrayList<>(requestedIds);
        if (ids.size() > 4) ids = new ArrayList<>(ids.subList(0, 4));
        final List<String> finalIds = ids;
        runOnMain(() -> startPreviewsOnMain(finalIds, listener));
    }

    void stopPreviews() {
        generation++;
        runOnMain(this::closeSlotsOnMain);
    }

    private void startPreviewsOnMain(List<String> ids, PreviewListener listener) {
        closeSlotsOnMain();
        generation++;
        int currentGeneration = generation;
        if (!hasCameraPermission()) {
            listener.onStatus("没有相机权限，无法启动预览");
            return;
        }
        if (ids.size() != 4) {
            listener.onStatus("请填写按前、后、左、右排序的4个Camera ID");
            return;
        }
        if (previewViews.size() != 4) {
            listener.onStatus("四路预览控件尚未准备好");
            return;
        }
        for (int i = 0; i < 4; i++) {
            PreviewSlot slot = new PreviewSlot(i, ids.get(i), POSITIONS[i], previewViews.get(i));
            slots.add(slot);
            prepareTexture(slot, listener, currentGeneration);
        }
        listener.onStatus("正在按EVCam方式建立四路预览流");
    }

    private void prepareTexture(PreviewSlot slot, PreviewListener listener, int currentGeneration) {
        slot.view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                openSlot(slot, surface, listener, currentGeneration);
            }

            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) { }

            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                closeSlot(slot);
                return true;
            }

            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                slot.hasFrame = true;
            }
        });
        if (slot.view.isAvailable() && slot.view.getSurfaceTexture() != null) {
            openSlot(slot, slot.view.getSurfaceTexture(), listener, currentGeneration);
        }
    }

    @SuppressLint("MissingPermission")
    private void openSlot(PreviewSlot slot, SurfaceTexture texture, PreviewListener listener,
                          int currentGeneration) {
        if (slot.opening || slot.device != null || currentGeneration != generation) return;
        slot.opening = true;
        try {
            CameraCharacteristics characteristics = characteristicsCache.get(slot.cameraId);
            if (characteristics == null) {
                characteristics = cameraManager.getCameraCharacteristics(slot.cameraId);
                characteristicsCache.put(slot.cameraId, characteristics);
            }
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IllegalStateException("没有输出配置");
            Size[] sizes = map.getOutputSizes(ImageFormat.PRIVATE);
            if (sizes == null || sizes.length == 0) sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null || sizes.length == 0) throw new IllegalStateException("没有PRIVATE/SurfaceTexture输出");
            slot.previewSize = choosePreviewSize(sizes);
            texture.setDefaultBufferSize(slot.previewSize.getWidth(), slot.previewSize.getHeight());
            slot.surface = new Surface(texture);
            slot.thread = new HandlerThread("Lynk10-Preview-" + slot.cameraId);
            slot.thread.start();
            slot.handler = new Handler(slot.thread.getLooper());
            cameraManager.openCamera(slot.cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice camera) {
                    if (currentGeneration != generation) {
                        camera.close();
                        return;
                    }
                    slot.device = camera;
                    slot.opening = false;
                    createPreviewSession(slot, listener, currentGeneration);
                }

                @Override public void onDisconnected(@NonNull CameraDevice camera) {
                    slot.error = "被系统断开";
                    listener.onStatus("相机" + slot.cameraId + slot.error);
                    camera.close();
                    slot.device = null;
                    slot.ready = false;
                    slot.opening = false;
                }

                @Override public void onError(@NonNull CameraDevice camera, int error) {
                    slot.error = "打开失败，CameraDevice错误码" + error;
                    listener.onStatus("相机" + slot.cameraId + slot.error);
                    camera.close();
                    slot.device = null;
                    slot.ready = false;
                    slot.opening = false;
                }
            }, slot.handler);
        } catch (Exception e) {
            slot.opening = false;
            slot.error = "预览启动失败：" + e.getClass().getSimpleName() + " - " + e.getMessage();
            listener.onStatus("相机" + slot.cameraId + slot.error);
            closeSlot(slot);
        }
    }

    private void createPreviewSession(PreviewSlot slot, PreviewListener listener, int currentGeneration) {
        try {
            CaptureRequest.Builder request = slot.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(slot.surface);
            request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            slot.device.createCaptureSession(Collections.singletonList(slot.surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (currentGeneration != generation || slot.device == null) {
                                session.close();
                                return;
                            }
                            slot.session = session;
                            try {
                                session.setRepeatingRequest(request.build(), null, slot.handler);
                                slot.ready = true;
                                slot.error = null;
                                listener.onStatus("预览已连接 " + connectedCount() + "/4：" + slot.position
                                        + "=Camera " + slot.cameraId);
                            } catch (Exception e) {
                                slot.ready = false;
                                slot.error = "启动取流失败：" + e.getClass().getSimpleName()
                                        + " - " + e.getMessage();
                                listener.onStatus("相机" + slot.cameraId + slot.error);
                            }
                        }

                        @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            slot.ready = false;
                            slot.error = "预览会话配置失败";
                            listener.onStatus("相机" + slot.cameraId + slot.error);
                            session.close();
                        }
                    }, slot.handler);
        } catch (Exception e) {
            slot.ready = false;
            slot.error = "创建预览失败：" + e.getClass().getSimpleName()
                    + " - " + e.getMessage();
            listener.onStatus("相机" + slot.cameraId + slot.error);
        }
    }

    boolean hasReadyPreviews() {
        return readyCount() == 4;
    }

    int readyCount() {
        int count = 0;
        for (PreviewSlot slot : slots) if (slot.ready && slot.hasFrame) count++;
        return count;
    }

    private int connectedCount() {
        int count = 0;
        for (PreviewSlot slot : slots) if (slot.ready) count++;
        return count;
    }

    void capture(List<String> requestedIds, Callback callback) {
        runOnMain(() -> captureOnMain(callback));
    }

    void captureAllAvailableForProbe(Callback callback) {
        List<String> ids = availableCameraIds();
        if (ids.size() > 4) ids = new ArrayList<>(ids.subList(0, 4));
        startPreviews(ids, status -> { });
        mainHandler.postDelayed(() -> captureOnMain(callback), 4_000);
    }

    private void captureOnMain(Callback callback) {
        CaptureBundle bundle = new CaptureBundle();
        List<BitmapFrame> bitmaps = new ArrayList<>();
        for (PreviewSlot slot : slots) {
            if (!slot.ready || !slot.hasFrame || !slot.view.isAvailable()) {
                bundle.errors.add("相机" + slot.cameraId + "："
                        + (slot.error == null ? "预览帧尚未就绪" : slot.error));
                continue;
            }
            try {
                Bitmap bitmap = slot.view.getBitmap(
                        slot.previewSize.getWidth(), slot.previewSize.getHeight());
                if (bitmap == null) throw new IllegalStateException("TextureView没有返回画面");
                bitmaps.add(new BitmapFrame(slot, bitmap));
            } catch (Exception e) {
                bundle.errors.add("相机" + slot.cameraId + "：" + e.getMessage());
            }
        }
        new Thread(() -> {
            for (BitmapFrame item : bitmaps) {
                try {
                    byte[] jpeg = bitmapToJpeg(item.bitmap);
                    bundle.frames.add(new CaptureBundle.Frame(item.slot.cameraId, item.slot.position,
                            jpeg, item.width, item.height));
                } catch (Exception e) {
                    bundle.errors.add("相机" + item.slot.cameraId + "：JPEG编码失败 - " + e.getMessage());
                } finally {
                    item.bitmap.recycle();
                }
            }
            callback.onComplete(bundle);
        }, "Lynk10-Preview-Snapshot").start();
    }

    private static byte[] bitmapToJpeg(Bitmap source) {
        Bitmap output = source;
        int longEdge = Math.max(source.getWidth(), source.getHeight());
        if (longEdge > MAX_LONG_EDGE) {
            float ratio = MAX_LONG_EDGE / (float) longEdge;
            output = Bitmap.createScaledBitmap(source,
                    Math.max(2, Math.round(source.getWidth() * ratio)),
                    Math.max(2, Math.round(source.getHeight() * ratio)), true);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256_000);
        output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes);
        if (output != source) output.recycle();
        return bytes.toByteArray();
    }

    private static Size choosePreviewSize(Size[] sizes) {
        return Arrays.stream(sizes)
                .filter(size -> Math.max(size.getWidth(), size.getHeight()) <= 1920)
                .min(Comparator.comparingLong(size ->
                        Math.abs((long) size.getWidth() * size.getHeight() - 1280L * 720L)))
                .orElseGet(() -> Arrays.stream(sizes)
                        .min(Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight()))
                        .orElse(sizes[0]));
    }

    private void closeSlotsOnMain() {
        for (PreviewSlot slot : slots) closeSlot(slot);
        slots.clear();
    }

    private static void closeSlot(PreviewSlot slot) {
        slot.ready = false;
        slot.hasFrame = false;
        try { if (slot.session != null) slot.session.close(); } catch (Exception ignored) { }
        try { if (slot.device != null) slot.device.close(); } catch (Exception ignored) { }
        try { if (slot.surface != null) slot.surface.release(); } catch (Exception ignored) { }
        slot.session = null;
        slot.device = null;
        slot.surface = null;
        if (slot.thread != null) slot.thread.quitSafely();
        slot.thread = null;
        slot.handler = null;
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else mainHandler.post(action);
    }

    private static void appendSizes(StringBuilder out, String name, Size[] sizes) {
        out.append("\n  ").append(name).append('=');
        if (sizes == null || sizes.length == 0) {
            out.append("[]");
            return;
        }
        int limit = Math.min(6, sizes.length);
        for (int i = 0; i < limit; i++) {
            if (i > 0) out.append(", ");
            out.append(sizes[i].getWidth()).append('x').append(sizes[i].getHeight());
        }
        if (sizes.length > limit) out.append(" ...");
    }

    private static String facingName(Integer value) {
        if (value == null) return "unknown";
        if (value == CameraCharacteristics.LENS_FACING_FRONT) return "front";
        if (value == CameraCharacteristics.LENS_FACING_BACK) return "back";
        if (value == CameraCharacteristics.LENS_FACING_EXTERNAL) return "external";
        return String.format(Locale.ROOT, "%d", value);
    }

    private static String levelName(Integer value) {
        if (value == null) return "unknown";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) return "legacy";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) return "limited";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) return "full";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) return "level3";
        if (value == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) return "external";
        return String.valueOf(value);
    }

    @Override public void close() {
        stopPreviews();
        if (activeEngine == this) activeEngine = null;
    }

    private static final class PreviewSlot {
        final int index;
        final String cameraId;
        final String position;
        final TextureView view;
        HandlerThread thread;
        Handler handler;
        CameraDevice device;
        CameraCaptureSession session;
        Surface surface;
        Size previewSize;
        boolean opening;
        volatile boolean ready;
        volatile boolean hasFrame;
        volatile String error;

        PreviewSlot(int index, String cameraId, String position, TextureView view) {
            this.index = index;
            this.cameraId = cameraId;
            this.position = position;
            this.view = view;
        }
    }

    private static final class BitmapFrame {
        final PreviewSlot slot;
        final Bitmap bitmap;
        final int width;
        final int height;

        BitmapFrame(PreviewSlot slot, Bitmap bitmap) {
            this.slot = slot;
            this.bitmap = bitmap;
            this.width = bitmap.getWidth();
            this.height = bitmap.getHeight();
        }
    }
}
