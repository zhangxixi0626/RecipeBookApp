package com.reasonix.recipebook;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public class WeeklyPlanStore {
    private static final String PREFS = "weekly_plan";
    private static final String KEY_DISHES = "dishes";

    private final SharedPreferences prefs;

    public WeeklyPlanStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void save(List<String> dishes) {
        JSONArray array = new JSONArray();
        for (String dish : dishes) {
            array.put(dish);
        }
        prefs.edit().putString(KEY_DISHES, array.toString()).apply();
    }

    public List<String> load() {
        List<String> dishes = new ArrayList<>();
        String raw = prefs.getString(KEY_DISHES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String dish = array.optString(i).trim();
                dishes.add(dish);
            }
        } catch (JSONException ignored) {
        }
        return dishes;
    }
}
