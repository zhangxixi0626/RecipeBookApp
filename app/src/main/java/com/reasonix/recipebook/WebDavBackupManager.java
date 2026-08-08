package com.reasonix.recipebook;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class WebDavBackupManager {
    private static final String DEFAULT_BACKUP_FILE = "recipebook-backup.json";

    public String buildBackupJson(List<String> customDishNames, List<String> weeklyPlan) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("app", "RecipeBookApp");
        root.put("backedUpAt", utcNow());
        root.put("customDishNames", toJsonArray(customDishNames));
        root.put("weeklyPlan", toJsonArray(weeklyPlan));
        return root.toString(2);
    }

    public BackupData parseBackupJson(String raw) throws JSONException {
        JSONObject root = new JSONObject(raw);
        return new BackupData(
                toStringList(root.optJSONArray("customDishNames")),
                toStringList(root.optJSONArray("weeklyPlan"))
        );
    }

    public void upload(String serverUrl, String targetPath, String username, String password, String json) throws IOException {
        HttpURLConnection connection = openConnection(serverUrl, targetPath, username, password, "PUT");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream();
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            writer.write(json);
        }
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("WebDAV 备份失败，服务器返回：" + code);
        }
        connection.disconnect();
    }

    public String download(String serverUrl, String targetPath, String username, String password) throws IOException {
        HttpURLConnection connection = openConnection(serverUrl, targetPath, username, password, "GET");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("WebDAV 恢复失败，服务器返回：" + code);
        }
        String result = readAll(connection.getInputStream());
        connection.disconnect();
        return result;
    }

    public String normalizeBackupUrl(String rawServerUrl, String rawTargetPath) {
        String serverUrl = rawServerUrl.trim();
        String targetPath = rawTargetPath.trim();
        if (!serverUrl.startsWith("https://")) {
            throw new IllegalArgumentException("WebDAV服务器地址需要使用 https://");
        }
        if (targetPath.isEmpty()) {
            targetPath = DEFAULT_BACKUP_FILE;
        }
        while (targetPath.startsWith("/")) {
            targetPath = targetPath.substring(1);
        }
        if (targetPath.isEmpty()) {
            targetPath = DEFAULT_BACKUP_FILE;
        }
        return serverUrl.endsWith("/") ? serverUrl + targetPath : serverUrl + "/" + targetPath;
    }

    private HttpURLConnection openConnection(String serverUrl, String targetPath, String username, String password, String method) throws IOException {
        URL target = new URL(normalizeBackupUrl(serverUrl, targetPath));
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/json");
        if (!username.trim().isEmpty() || !password.isEmpty()) {
            String token = username + ":" + password;
            String encoded = Base64.encodeToString(token.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            connection.setRequestProperty("Authorization", "Basic " + encoded);
        }
        return connection;
    }

    private JSONArray toJsonArray(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private List<String> toStringList(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i).trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private String readAll(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private String utcNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    public static class BackupData {
        public final List<String> customDishNames;
        public final List<String> weeklyPlan;

        public BackupData(List<String> customDishNames, List<String> weeklyPlan) {
            this.customDishNames = customDishNames;
            this.weeklyPlan = weeklyPlan;
        }
    }
}
