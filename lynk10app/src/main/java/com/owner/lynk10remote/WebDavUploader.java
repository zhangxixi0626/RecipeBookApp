package com.owner.lynk10remote;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class WebDavUploader {
    interface Callback {
        void onComplete(boolean success, String message);
    }

    private static final MediaType TEXT = MediaType.parse("text/plain; charset=utf-8");
    private static final MediaType JPEG = MediaType.parse("image/jpeg");
    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private static final MediaType EMPTY = MediaType.parse("application/octet-stream");

    private final AppSettings settings;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    WebDavUploader(AppSettings settings) {
        this.settings = settings;
    }

    void testAsync(Callback callback) {
        new Thread(() -> {
            try {
                HttpUrl folder = ensureConfiguredFolder();
                String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                        + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/>"
                        + "</d:prop></d:propfind>";
                Request request = authorized(new Request.Builder().url(folder))
                        .header("Depth", "0")
                        .method("PROPFIND", RequestBody.create(xml, XML))
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() < 200 || response.code() >= 300) {
                        throw new IllegalStateException("WebDAV返回HTTP " + response.code());
                    }
                }
                callback.onComplete(true, "WebDAV连接正常，目标文件夹可访问");
            } catch (Exception e) {
                callback.onComplete(false, readableError(e));
            }
        }, "Lynk10-WebDAV-Test").start();
    }

    void uploadProbeAsync(String probeReport, CaptureBundle captures, Callback callback) {
        new Thread(() -> {
            try {
                HttpUrl target = ensureConfiguredFolder();
                String sessionName = "probe-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                        .format(new Date(captures.capturedAt));
                HttpUrl session = append(target, sessionName);
                ensureCollection(session);

                StringBuilder report = new StringBuilder(probeReport);
                report.append("\n\n请根据样图填写四路映射\n")
                        .append("front=\nrear=\nleft=\nright=\n");
                report.append("\n\n诊断样张\n");
                if (captures.frames.isEmpty()) report.append("没有入口成功返回JPEG。\n");
                for (CaptureBundle.Frame frame : captures.frames) {
                    report.append("Camera ID ").append(frame.cameraId)
                            .append(" -> camera-").append(safeName(frame.cameraId)).append(".jpg")
                            .append("  ").append(frame.width).append('x').append(frame.height).append('\n');
                }
                if (!captures.errors.isEmpty()) {
                    report.append("\n抓拍错误\n");
                    for (String error : captures.errors) report.append("- ").append(error).append('\n');
                }

                put(append(session, "camera-report.txt"),
                        report.toString().getBytes(StandardCharsets.UTF_8), TEXT);
                for (CaptureBundle.Frame frame : captures.frames) {
                    put(append(session, "camera-" + safeName(frame.cameraId) + ".jpg"), frame.jpeg, JPEG);
                }
                String relative = normalizedFolder() + "/" + sessionName;
                callback.onComplete(true, "已上传报告和" + captures.frames.size()
                        + "张样图到 " + relative);
            } catch (Exception e) {
                callback.onComplete(false, readableError(e));
            }
        }, "Lynk10-WebDAV-Upload").start();
    }

    private HttpUrl ensureConfiguredFolder() throws Exception {
        String rawUrl = settings.webDavUrl().trim();
        if (!rawUrl.startsWith("https://")) {
            throw new IllegalArgumentException("WebDAV地址必须以https://开头");
        }
        HttpUrl current = HttpUrl.get(rawUrl);
        for (String segment : folderSegments()) {
            current = append(current, segment);
            ensureCollection(current);
        }
        return current;
    }

    private void ensureCollection(HttpUrl url) throws Exception {
        Request request = authorized(new Request.Builder().url(url))
                .method("MKCOL", RequestBody.create(new byte[0], EMPTY))
                .build();
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            if ((code >= 200 && code < 300) || code == 405) return;
            throw new IllegalStateException("创建WebDAV目录失败，HTTP " + code);
        }
    }

    private void put(HttpUrl url, byte[] body, MediaType type) throws Exception {
        Request request = authorized(new Request.Builder().url(url))
                .put(RequestBody.create(body, type))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() < 200 || response.code() >= 300) {
                throw new IllegalStateException("上传失败，HTTP " + response.code());
            }
        }
    }

    private Request.Builder authorized(Request.Builder builder) {
        String user = settings.webDavUser();
        if (!user.isEmpty()) {
            builder.header("Authorization", Credentials.basic(user,
                    settings.webDavPassword(), StandardCharsets.UTF_8));
        }
        return builder.header("User-Agent", "Lynk10Remote/" + BuildConfig.VERSION_NAME);
    }

    private List<String> folderSegments() {
        String folder = normalizedFolder();
        if (folder.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(folder.split("/")));
    }

    private String normalizedFolder() {
        return settings.webDavFolder().trim()
                .replace('\\', '/')
                .replaceAll("^/+|/+$", "")
                .replaceAll("/{2,}", "/");
    }

    private static HttpUrl append(HttpUrl base, String segment) {
        return base.newBuilder().addPathSegment(segment).build();
    }

    private static String safeName(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "unknown" : safe;
    }

    private static String readableError(Exception error) {
        String detail = error.getMessage();
        return "WebDAV失败：" + (detail == null || detail.isEmpty()
                ? error.getClass().getSimpleName() : detail);
    }
}
