package com.reasonix.recipebook;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ShoppingMemoStore {
    private static final String PREFS = "shopping_memo";
    private static final String KEY_ITEMS = "items";

    private final SharedPreferences prefs;

    public ShoppingMemoStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<ShoppingItem> load() {
        List<ShoppingItem> items = new ArrayList<>();
        String raw = prefs.getString(KEY_ITEMS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                String name = object.optString("name").trim();
                if (!name.isEmpty()) {
                    items.add(new ShoppingItem(name, object.optBoolean("done", false)));
                }
            }
        } catch (JSONException ignored) {
        }
        return items;
    }

    public void add(String name) {
        List<ShoppingItem> items = load();
        items.add(new ShoppingItem(name.trim(), false));
        save(items);
    }

    public void toggle(int index) {
        List<ShoppingItem> items = load();
        if (index >= 0 && index < items.size()) {
            ShoppingItem item = items.get(index);
            items.set(index, new ShoppingItem(item.name, !item.done));
            save(items);
        }
    }

    public void remove(int index) {
        List<ShoppingItem> items = load();
        if (index >= 0 && index < items.size()) {
            items.remove(index);
            save(items);
        }
    }

    public void save(List<ShoppingItem> items) {
        JSONArray array = new JSONArray();
        for (ShoppingItem item : items) {
            JSONObject object = new JSONObject();
            try {
                object.put("name", item.name);
                object.put("done", item.done);
                array.put(object);
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply();
    }

    public static class ShoppingItem {
        public final String name;
        public final boolean done;

        public ShoppingItem(String name, boolean done) {
            this.name = name;
            this.done = done;
        }
    }
}
