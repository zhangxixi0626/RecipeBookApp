package com.reasonix.recipebook;

import android.content.Context;
import android.content.SharedPreferences;

public class WebDavSettingsStore {
    private static final String PREFS = "webdav_settings";
    private static final String KEY_URL = "url";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_TARGET_PATH = "target_path";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String DEFAULT_TARGET_PATH = "recipebook-backup.json";

    private final SharedPreferences prefs;

    public WebDavSettingsStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getServerUrl() {
        String saved = prefs.getString(KEY_SERVER_URL, "");
        if (!saved.isEmpty()) {
            return saved;
        }
        return splitLegacyUrl(true);
    }

    public String getTargetPath() {
        String saved = prefs.getString(KEY_TARGET_PATH, "");
        if (!saved.isEmpty()) {
            return saved;
        }
        String legacyTarget = splitLegacyUrl(false);
        return legacyTarget.isEmpty() ? DEFAULT_TARGET_PATH : legacyTarget;
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    public String getPassword() {
        return prefs.getString(KEY_PASSWORD, "");
    }

    public void save(String serverUrl, String targetPath, String username, String password) {
        prefs.edit()
                .putString(KEY_SERVER_URL, serverUrl.trim())
                .putString(KEY_TARGET_PATH, targetPath.trim())
                .putString(KEY_USERNAME, username.trim())
                .putString(KEY_PASSWORD, password)
                .apply();
    }

    private String splitLegacyUrl(boolean serverPart) {
        String legacyUrl = prefs.getString(KEY_URL, "");
        if (legacyUrl.isEmpty()) {
            return "";
        }
        if (legacyUrl.endsWith("/")) {
            return serverPart ? legacyUrl : DEFAULT_TARGET_PATH;
        }
        int slash = legacyUrl.lastIndexOf('/');
        if (slash < "https://".length()) {
            return serverPart ? legacyUrl : DEFAULT_TARGET_PATH;
        }
        return serverPart ? legacyUrl.substring(0, slash + 1) : legacyUrl.substring(slash + 1);
    }
}
