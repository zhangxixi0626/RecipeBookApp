package com.owner.lynk10remote;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class AppSettings {
    private static final String PREFS = "lynk10_remote_settings";
    private static final String KEY_SERVER = "server_url";
    private static final String KEY_DEVICE = "device_id";
    private static final String KEY_TOKEN = "pair_token";
    private static final String KEY_CAMERAS = "camera_ids";
    private static final String KEY_GUARD = "guard_enabled";
    private static final String KEY_WEBDAV_URL = "webdav_url";
    private static final String KEY_WEBDAV_USER = "webdav_user";
    private static final String KEY_WEBDAV_PASSWORD = "webdav_password";
    private static final String KEY_WEBDAV_FOLDER = "webdav_folder";

    private final SharedPreferences prefs;

    AppSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String serverUrl() {
        return prefs.getString(KEY_SERVER, "wss://your-server.example/ws/vehicle");
    }

    String deviceId() {
        return prefs.getString(KEY_DEVICE, "lynk10ev-01");
    }

    String token() {
        return prefs.getString(KEY_TOKEN, "");
    }

    String cameraIdsText() {
        return prefs.getString(KEY_CAMERAS, "");
    }

    List<String> cameraIds() {
        String value = cameraIdsText().trim();
        if (value.isEmpty()) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (String id : Arrays.asList(value.split(","))) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    boolean guardEnabled() {
        return prefs.getBoolean(KEY_GUARD, false);
    }

    String webDavUrl() {
        return prefs.getString(KEY_WEBDAV_URL, "");
    }

    String webDavUser() {
        return prefs.getString(KEY_WEBDAV_USER, "");
    }

    String webDavPassword() {
        return prefs.getString(KEY_WEBDAV_PASSWORD, "");
    }

    String webDavFolder() {
        return prefs.getString(KEY_WEBDAV_FOLDER, "Lynk10EV/camera-probe");
    }

    boolean hasWebDavConfig() {
        return !webDavUrl().trim().isEmpty();
    }

    void save(String serverUrl, String deviceId, String token, String cameraIds,
              String webDavUrl, String webDavUser, String webDavPassword, String webDavFolder) {
        prefs.edit()
                .putString(KEY_SERVER, serverUrl.trim())
                .putString(KEY_DEVICE, deviceId.trim())
                .putString(KEY_TOKEN, token.trim())
                .putString(KEY_CAMERAS, cameraIds.trim())
                .putString(KEY_WEBDAV_URL, webDavUrl.trim())
                .putString(KEY_WEBDAV_USER, webDavUser.trim())
                .putString(KEY_WEBDAV_PASSWORD, webDavPassword)
                .putString(KEY_WEBDAV_FOLDER, webDavFolder.trim())
                .apply();
    }

    void setGuardEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_GUARD, enabled).apply();
    }
}
