package com.reasonix.recipebook;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ReminderStore {
    private static final String PREFS = "reminders";
    private static final String KEY_MESSAGES = "messages";
    private static final int MAX_MESSAGES = 30;

    private final SharedPreferences prefs;

    public ReminderStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<String> load() {
        List<String> messages = new ArrayList<>();
        String raw = prefs.getString(KEY_MESSAGES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String message = array.optString(i).trim();
                if (!message.isEmpty()) {
                    messages.add(message);
                }
            }
        } catch (JSONException ignored) {
        }
        return messages;
    }

    public void add(String message) {
        List<String> messages = load();
        messages.add(0, timePrefix() + "  " + message);
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(messages.size() - 1);
        }
        save(messages);
    }

    public void clear() {
        save(new ArrayList<>());
    }

    private void save(List<String> messages) {
        JSONArray array = new JSONArray();
        for (String message : messages) {
            array.put(message);
        }
        prefs.edit().putString(KEY_MESSAGES, array.toString()).apply();
    }

    private String timePrefix() {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date());
    }
}
