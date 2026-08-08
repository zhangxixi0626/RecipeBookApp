package com.reasonix.recipebook;

import android.content.Context;
import android.content.SharedPreferences;

public class WebDavSettingsStore {
    private static final String PREFS = "webdav_settings";
    private static final String KEY_URL = "url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";

    private final SharedPreferences prefs;

    public WebDavSettingsStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getUrl() {
        return prefs.getString(KEY_URL, "");
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    public String getPassword() {
        return prefs.getString(KEY_PASSWORD, "");
    }

    public void save(String url, String username, String password) {
        prefs.edit()
                .putString(KEY_URL, url.trim())
                .putString(KEY_USERNAME, username.trim())
                .putString(KEY_PASSWORD, password)
                .apply();
    }
}
