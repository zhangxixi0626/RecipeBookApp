package com.reasonix.recipebook;

import android.content.Context;
import android.content.SharedPreferences;

public class FavoriteStore {
    private static final String PREFS = "recipe_favorites";
    private final SharedPreferences prefs;

    public FavoriteStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isFavorite(int id) {
        return prefs.getBoolean(String.valueOf(id), false);
    }

    public void toggle(int id) {
        prefs.edit().putBoolean(String.valueOf(id), !isFavorite(id)).apply();
    }
}
